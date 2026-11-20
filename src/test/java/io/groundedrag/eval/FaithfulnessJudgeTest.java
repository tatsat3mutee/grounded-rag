package io.groundedrag.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.groundedrag.chat.ChatAnswer;
import io.groundedrag.generation.ScriptedChatModel;
import io.groundedrag.retrieval.ConfidenceLevel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

class FaithfulnessJudgeTest {

    private static ChatAnswer answer(String text) {
        return new ChatAnswer(UUID.randomUUID(), "c", text, null, "q", new ChatAnswer.Confidence(ConfidenceLevel.HIGH, 1),
                List.of(new ChatAnswer.Source(1, UUID.randomUUID(), "product", "refunds.md", null, null, 0.9,
                        "Annual plans can be refunded within 30 days.", true)),
                null, false, Map.of());
    }

    @Test
    void parsesStructuredVerdictAndScoresSupportedShare() {
        var model = new ScriptedChatModel(p -> """
                {"claims": [
                  {"claim": "Annual plans are refundable within 30 days", "supported": true, "evidence": "within 30 days"},
                  {"claim": "Refunds are processed by the finance team", "supported": false, "evidence": ""}
                ]}""");
        var verdict = new FaithfulnessJudge(ChatClient.create(model))
                .judge(answer("Annual plans are refundable within 30 days [1], handled by finance [1]."));

        assertThat(verdict.claims()).hasSize(2);
        assertThat(verdict.score()).isEqualTo(0.5);
        String user = ScriptedChatModel.user(model.prompts.get(0));
        assertThat(user).contains("[1] Annual plans can be refunded within 30 days.").contains("handled by finance");
        // Structured output: Spring AI appends the JSON schema of Verdict to the prompt.
        assertThat(user).contains("\"supported\"");
    }

    @Test
    void refusalWithNoClaimsIsFullyFaithful() {
        var verdict = new FaithfulnessJudge(ChatClient.create(new ScriptedChatModel(p -> "{\"claims\": []}")))
                .judge(answer("I don't know based on the provided documents."));
        assertThat(verdict.score()).isEqualTo(1.0);
    }

    @Test
    void evaluatorReportsJudgeScoreAndUnsupportedClaims() {
        var f = new io.groundedrag.Fixtures();
        f.ingest("product", "refunds.md", "# Refunds\nAnnual plans can be refunded within 30 days of payment.");
        var judge = new FaithfulnessJudge(ChatClient.create(new ScriptedChatModel(p -> """
                {"claims": [{"claim": "refund within 30 days", "supported": true, "evidence": "30 days"},
                            {"claim": "made-up extra", "supported": false, "evidence": ""}]}""")));
        var cases = List.of(new EvalCase("r1", "factual", List.of("product"), "Within how many days can annual plans be refunded?",
                List.of("refunds.md"), List.of("30 days")));

        var scores = new Evaluator(f.store, f.ingestion, f.embedder, f.retriever).answers(cases, f.rag(), judge);

        assertThat(scores.faithfulness()).isEqualTo(0.5);
        assertThat(scores.failures()).anyMatch(s -> s.contains("unsupported claim: made-up extra"));
        assertThat(Evaluator.markdown(List.of(), scores, "s")).contains("| Faithfulness, claim by claim (FaithfulnessJudge) | 0.500 |");
    }

    @Test
    void malformedJudgeOutputIsRetriedWithTheValidationError() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var model = new ScriptedChatModel(p -> calls.incrementAndGet() == 1
                ? "Sure! Here is my verdict: the answer looks fine."
                : "{\"claims\": [{\"claim\": \"refund within 30 days\", \"supported\": true, \"evidence\": \"30 days\"}]}");
        var verdict = new FaithfulnessJudge(ChatClient.create(model)).judge(answer("Refunds within 30 days [1]."));
        assertThat(verdict.score()).isEqualTo(1.0);
        assertThat(model.prompts).hasSize(2);
    }

    @Test
    void springAiEvaluatorsAreReportedBesideTheJudge() {
        var f = new io.groundedrag.Fixtures();
        f.ingest("product", "refunds.md", "# Refunds\nAnnual plans can be refunded within 30 days of payment.");
        var grader = new ScriptedChatModel(p -> "YES");
        var graders = new Evaluator.Graders(null,
                org.springframework.ai.chat.evaluation.RelevancyEvaluator.builder()
                        .chatClientBuilder(ChatClient.builder(grader)).build(),
                org.springframework.ai.chat.evaluation.FactCheckingEvaluator.builder(ChatClient.builder(grader)).build());
        var cases = List.of(new EvalCase("r1", "factual", List.of("product"),
                "Within how many days can annual plans be refunded?", List.of("refunds.md"), List.of("30 days")));

        var scores = new Evaluator(f.store, f.ingestion, f.embedder, f.retriever).answers(cases, f.rag(), graders);

        assertThat(scores.contextConsistency()).isEqualTo(1.0);
        assertThat(scores.factCheck()).isEqualTo(1.0);
        assertThat(scores.faithfulness()).isNull();
        assertThat(ScriptedChatModel.user(grader.prompts.get(0))).contains("refunded within 30 days");
        assertThat(Evaluator.markdown(List.of(), scores, "s"))
                .contains("| In line with context (Spring AI RelevancyEvaluator) | 1.000 |")
                .contains("| Faithfulness, claim by claim (FaithfulnessJudge) | not run (no judge model) |");
    }
}

package io.groundedrag.eval;

import java.util.List;

import io.groundedrag.chat.ChatAnswer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.StructuredOutputValidationAdvisor;

/**
 * LLM-as-judge faithfulness: split the answer into atomic claims and decide, for each, whether
 * the cited sources support it. Complements {@code GroundingVerifier}, which only catches fake
 * citations and invented numbers; this catches unsupported claims without numbers.
 *
 * <p>Offline only (evaluation harness): it doubles cost and latency, and a judge model has its
 * own error rate, so it measures the system rather than gating individual answers. Uses Spring
 * AI structured output, so the model's reply is parsed into typed records.
 */
public class FaithfulnessJudge {

    public record Claim(String claim, boolean supported, String evidence) {
    }

    public record Verdict(List<Claim> claims) {

        /** Supported claims / all claims; 1.0 for an answer with no factual claims (e.g. a refusal). */
        public double score() {
            if (claims == null || claims.isEmpty()) {
                return 1.0;
            }
            return claims.stream().filter(Claim::supported).count() / (double) claims.size();
        }
    }

    static final String SYSTEM = """
            You are a strict fact-checking judge. You receive numbered sources and an answer.
            1. Split the answer into atomic factual claims (ignore citation markers and filler).
            2. For each claim, decide whether the sources explicitly support it. Paraphrase is fine;
               anything not stated in the sources is unsupported, even if it is true in the real world.
            3. For supported claims, quote the shortest supporting phrase as evidence; for unsupported
               claims, evidence is an empty string.
            A refusal ("I don't know...") has no claims.""";

    private final ChatClient chat;

    public FaithfulnessJudge(ChatClient chat) {
        this.chat = chat;
    }

    public Verdict judge(ChatAnswer answer) {
        StringBuilder sources = new StringBuilder();
        for (ChatAnswer.Source s : answer.sources()) {
            sources.append('[').append(s.number()).append("] ").append(s.excerpt()).append('\n');
        }
        return chat.prompt()
                .system(SYSTEM)
                .user("Sources:\n" + sources + "\nAnswer:\n" + answer.answer())
                // Spring AI validates the reply against Verdict's JSON schema and, if it does not
                // parse, asks the model again with the validation error (up to two more times).
                .advisors(StructuredOutputValidationAdvisor.builder().outputType(Verdict.class).maxRepeatAttempts(2).build())
                .call()
                .entity(Verdict.class);
    }
}

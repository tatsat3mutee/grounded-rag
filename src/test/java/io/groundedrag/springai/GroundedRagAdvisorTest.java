package io.groundedrag.springai;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import io.groundedrag.Fixtures;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.generation.HeuristicQueryRewriter;
import io.groundedrag.generation.ScriptedChatModel;
import io.groundedrag.retrieval.ContextAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.document.Document;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;

/** The service's retrieval used from a plain Spring AI ChatClient through RetrievalAugmentationAdvisor. */
class GroundedRagAdvisorTest {

    private Fixtures f;
    private GroundedDocumentRetriever retriever;
    private RetrievalAugmentationAdvisor advisor;

    @BeforeEach
    void setUp() {
        f = new Fixtures();
        f.ingest("product", "refunds.md", "# Refund window\nAnnual plans can be refunded within 30 days of the first payment.");
        f.ingest("handbook", "leave.md", "# Leave\nEmployees get 24 days of annual leave.");
        retriever = new GroundedDocumentRetriever(f.embedder, f.retriever, new ContextAssembler(f.store, Fixtures.TOKENS, 3000, true));
        advisor = RetrievalAugmentationAdvisor.builder()
                .queryTransformers(new StandaloneQueryTransformer(new HeuristicQueryRewriter()))
                .documentRetriever(retriever)
                .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(false).build())
                .build();
    }

    @Test
    void retrieverReturnsCitableDocumentsForTheCallersSpacesOnly() {
        List<Document> docs = retriever.retrieve(Query.builder().text("refund window for annual plans")
                .context(java.util.Map.of(GroundedDocumentRetriever.SPACES, Set.of(SpaceId.of("product")))).build());
        assertThat(docs).isNotEmpty();
        assertThat(docs.get(0).getMetadata()).containsEntry("source", "refunds.md").containsEntry("number", 1)
                .containsEntry("section", "Refund window");
        assertThat(docs).noneMatch(d -> "leave.md".equals(d.getMetadata().get("source")));
    }

    @Test
    void withoutSpacesInContextNothingIsRetrieved() {
        assertThat(retriever.retrieve(new Query("refund window"))).isEmpty();
    }

    @Test
    void chatClientWithTheAdvisorSendsRetrievedContextToTheModel() {
        var model = new ScriptedChatModel(p -> "Within 30 days.");
        String answer = ChatClient.create(model).prompt("What is the refund window for annual plans?")
                .advisors(advisor)
                .advisors(a -> a.param(GroundedDocumentRetriever.SPACES, Set.of(SpaceId.of("product"))))
                .call().content();

        assertThat(answer).isEqualTo("Within 30 days.");
        assertThat(ScriptedChatModel.user(model.prompts.get(0))).contains("within 30 days of the first payment");
    }

    @Test
    void offTopicQuestionReachesTheModelWithAnInstructionToDecline() {
        var model = new ScriptedChatModel(p -> "I can't answer that.");
        ChatClient.create(model).prompt("Which pizza toppings are served at the Austin party?")
                .advisors(advisor)
                .advisors(a -> a.param(GroundedDocumentRetriever.SPACES, Set.of(SpaceId.of("product"))))
                .call().content();
        String prompt = ScriptedChatModel.user(model.prompts.get(0));
        assertThat(prompt).doesNotContain("Annual plans can be refunded");
        assertThat(prompt).containsIgnoringCase("outside your knowledge base");
    }

    @Test
    void transformerRewritesFollowUpsUsingHistory() {
        Query q = Query.builder().text("and in Zurich?")
                .history(List.of(new UserMessage("What is the hotel limit in London?"), new AssistantMessage("350 USD")))
                .build();
        String standalone = new StandaloneQueryTransformer(new HeuristicQueryRewriter()).transform(q).text();
        assertThat(standalone).contains("Zurich").contains("hotel");
    }
}

package io.groundedrag.eval;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import io.groundedrag.chat.RagService;
import io.groundedrag.config.RagProperties;
import io.groundedrag.ingestion.IngestionService;
import io.groundedrag.retrieval.Embedder;
import io.groundedrag.retrieval.HybridRetriever;
import io.groundedrag.retrieval.Reranker;
import io.groundedrag.retrieval.RetrievalMode;
import io.groundedrag.store.KnowledgeStore;
import org.slf4j.Logger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code java -jar app.jar --spring.profiles.active=openai --rag.eval.run=true}
 * ingests eval/corpus into the configured store, scores the golden set with the configured
 * models and writes target/eval-report.md, then exits. Use a throwaway store, not production.
 */
@Component
@ConditionalOnProperty(name = "rag.eval.run", havingValue = "true")
class EvalRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);

    private final KnowledgeStore store;
    private final IngestionService ingestion;
    private final Embedder embedder;
    private final HybridRetriever retriever;
    private final Reranker reranker;
    private final RagService rag;
    private final RagProperties props;
    private final ConfigurableApplicationContext context;
    private final ObjectProvider<ChatModel> chatModel;

    EvalRunner(KnowledgeStore store, IngestionService ingestion, Embedder embedder, HybridRetriever retriever,
            Reranker reranker, RagService rag, RagProperties props, ConfigurableApplicationContext context,
            ObjectProvider<ChatModel> chatModel) {
        this.chatModel = chatModel;
        this.store = store;
        this.ingestion = ingestion;
        this.embedder = embedder;
        this.retriever = retriever;
        this.reranker = reranker;
        this.rag = rag;
        this.props = props;
        this.context = context;
    }

    /**
     * Model graders on the configured chat model (the utility model when one is set). Ideally a
     * stronger model than the generator: a judge has its own error rate.
     */
    private Evaluator.Graders graders() {
        ChatModel model = chatModel.getIfAvailable();
        if (model == null) {
            return Evaluator.Graders.none();
        }
        String utility = props.generation().utilityModel();
        ChatClient.Builder builder = ChatClient.builder(model);
        if (!utility.isBlank()) {
            builder.defaultOptions(org.springframework.ai.chat.prompt.ChatOptions.builder().model(utility));
        }
        return new Evaluator.Graders(new FaithfulnessJudge(builder.clone().build()),
                org.springframework.ai.chat.evaluation.RelevancyEvaluator.builder().chatClientBuilder(builder.clone()).build(),
                org.springframework.ai.chat.evaluation.FactCheckingEvaluator.builder(builder.clone()).build());
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Evaluator ev = new Evaluator(store, ingestion, embedder, retriever);
        int docs = ev.ingestCorpus(Path.of("eval/corpus"));
        List<EvalCase> cases = Evaluator.loadGoldenSet(Path.of("eval/golden-set.json"));
        int k = props.retrieval().topK();
        String report = Evaluator.markdown(
                List.of(ev.retrieval(cases, RetrievalMode.HYBRID, k), ev.retrieval(cases, RetrievalMode.VECTOR, k),
                        ev.retrieval(cases, RetrievalMode.KEYWORD, k)),
                ev.answers(cases, rag, graders()),
                "Setup: %d documents, %d questions, embedding=%s, reranker=%s, generator=%s, k=%d"
                        .formatted(docs, cases.size(), embedder.modelName(), reranker.name(),
                                props.generation().provider(), k));
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/eval-report.md"), report);
        log.info("\n{}", report);
        context.close();
    }
}

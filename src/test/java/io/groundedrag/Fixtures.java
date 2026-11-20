package io.groundedrag;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import io.groundedrag.cache.SemanticCache;
import io.groundedrag.chat.ChatAnswer;
import io.groundedrag.chat.ConversationMemory;
import io.groundedrag.chat.RagService;
import io.groundedrag.domain.DocumentRecord;
import io.groundedrag.domain.SpaceId;
import io.groundedrag.generation.AnswerGenerator;
import io.groundedrag.generation.ExtractiveAnswerGenerator;
import io.groundedrag.generation.GroundingVerifier;
import io.groundedrag.generation.HeuristicQueryRewriter;
import io.groundedrag.generation.QueryRewriter;
import io.groundedrag.ingestion.DocumentParser;
import io.groundedrag.ingestion.IngestionService;
import io.groundedrag.ingestion.ParentChildChunker;
import io.groundedrag.ingestion.PiiRedactor;
import io.groundedrag.observability.InMemoryTraceStore;
import io.groundedrag.retrieval.ContextAssembler;
import io.groundedrag.retrieval.Embedder;
import io.groundedrag.retrieval.HashingEmbeddingModel;
import io.groundedrag.retrieval.HybridRetriever;
import io.groundedrag.retrieval.NoopReranker;
import io.groundedrag.retrieval.RetrievalMode;
import io.groundedrag.retrieval.RetrievalSettings;
import io.groundedrag.store.InMemoryKnowledgeStore;
import io.groundedrag.store.KnowledgeStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;

/** Builds the real pipeline (no mocks, no Spring) around an in-memory store for fast tests. */
public final class Fixtures {

    public static final JTokkitTokenCountEstimator TOKENS = new JTokkitTokenCountEstimator();
    public static final RetrievalSettings RETRIEVAL = new RetrievalSettings(RetrievalMode.HYBRID, 30, 6, 60, 0.9);
    public static final RetrievalSettings.Thresholds CONFIDENCE = new RetrievalSettings.Thresholds(0.8, 0.6, 0.42);

    public final KnowledgeStore store;
    public final Embedder embedder = new Embedder(new HashingEmbeddingModel(384), HashingEmbeddingModel.MODEL_NAME, 384, 16);
    public final IngestionService ingestion;
    public final HybridRetriever retriever;
    public final InMemoryTraceStore traces = new InMemoryTraceStore();
    public final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    public Fixtures() {
        this(new InMemoryKnowledgeStore(), Runnable::run);
    }

    public Fixtures(KnowledgeStore store, Executor executor) {
        this.store = store;
        this.ingestion = new IngestionService(store,
                new DocumentParser(Set.of("md", "txt", "pdf", "html")),
                new PiiRedactor(true),
                new ParentChildChunker(TOKENS, 700, 180, 40),
                embedder, executor, meters);
        this.retriever = new HybridRetriever(store, new NoopReranker(), RETRIEVAL, CONFIDENCE);
    }

    public RagService rag() {
        return rag(new ExtractiveAnswerGenerator(), new HeuristicQueryRewriter());
    }

    public RagService rag(AnswerGenerator generator, QueryRewriter rewriter) {
        return new RagService(store, embedder, retriever, new NoopReranker(),
                new ContextAssembler(store, TOKENS, 3000, true), rewriter, generator, new GroundingVerifier(),
                new SemanticCache<ChatAnswer>(0.97, Duration.ofHours(1), 100, Clock.systemUTC()), true, true,
                new ConversationMemory(org.springframework.ai.chat.memory.MessageWindowChatMemory.builder()
                        .chatMemoryRepository(new io.groundedrag.chat.BoundedChatMemoryRepository(100)).maxMessages(12).build()), traces, meters);
    }

    /** Indexes synchronously and returns the resulting record. */
    public DocumentRecord ingest(String space, String filename, String content) {
        IngestionService.Submission s = ingestion.submit(SpaceId.of(space), filename, "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
        return store.findDocument(SpaceId.of(space), s.document().id()).orElseThrow();
    }

    public static Set<SpaceId> spaces(String... ids) {
        return java.util.Arrays.stream(ids).map(SpaceId::of).collect(java.util.stream.Collectors.toSet());
    }

    public static List<String> lines(String... l) {
        return List.of(l);
    }
}

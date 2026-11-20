package io.groundedrag.config;

import java.time.Clock;
import java.util.concurrent.Executor;

import javax.sql.DataSource;

import io.groundedrag.cache.SemanticCache;
import io.groundedrag.chat.ChatAnswer;
import io.groundedrag.chat.BoundedChatMemoryRepository;
import io.groundedrag.chat.ConversationMemory;
import io.groundedrag.chat.RagService;
import io.groundedrag.generation.AnswerGenerator;
import io.groundedrag.generation.ExtractiveAnswerGenerator;
import io.groundedrag.generation.GroundingVerifier;
import io.groundedrag.generation.HeuristicQueryRewriter;
import io.groundedrag.generation.LlmAnswerGenerator;
import io.groundedrag.generation.LlmQueryRewriter;
import io.groundedrag.generation.QueryRewriter;
import io.groundedrag.ingestion.DocumentParser;
import io.groundedrag.ingestion.IngestionService;
import io.groundedrag.ingestion.ParentChildChunker;
import io.groundedrag.ingestion.PiiRedactor;
import io.groundedrag.observability.InMemoryTraceStore;
import io.groundedrag.observability.JdbcTraceStore;
import io.groundedrag.observability.TraceStore;
import io.groundedrag.retrieval.ContextAssembler;
import io.groundedrag.retrieval.Embedder;
import io.groundedrag.retrieval.HashingEmbeddingModel;
import io.groundedrag.retrieval.HybridRetriever;
import io.groundedrag.retrieval.NoopReranker;
import io.groundedrag.retrieval.Reranker;
import io.groundedrag.retrieval.RetrievalMode;
import io.groundedrag.retrieval.RetrievalSettings;
import io.groundedrag.retrieval.TeiReranker;
import io.groundedrag.store.InMemoryKnowledgeStore;
import io.groundedrag.store.JdbcKnowledgeStore;
import io.groundedrag.store.KnowledgeStore;
import io.groundedrag.springai.GroundedDocumentRetriever;
import io.groundedrag.springai.StandaloneQueryTransformer;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.memory.repository.jdbc.PostgresChatMemoryRepositoryDialect;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor;
import org.springframework.ai.rag.generation.augmentation.ContextualQueryAugmenter;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.ai.tokenizer.TokenCountEstimator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Composition root. Domain classes have no Spring annotations; every implementation choice
 * (store, embedding, reranker, generator) is made here from {@link RagProperties}, which keeps the
 * domain unit-testable with plain constructors and makes the wiring readable in one file.
 */
@Configuration
public class RagConfiguration {

    // ---------- storage ----------

    @Bean
    @ConditionalOnProperty(name = "rag.store", havingValue = "memory", matchIfMissing = true)
    KnowledgeStore inMemoryKnowledgeStore() {
        return new InMemoryKnowledgeStore();
    }

    @Bean
    @ConditionalOnProperty(name = "rag.store", havingValue = "memory", matchIfMissing = true)
    TraceStore inMemoryTraceStore() {
        return new InMemoryTraceStore();
    }

    @Configuration
    @ConditionalOnProperty(name = "rag.store", havingValue = "postgres")
    static class PostgresStores {

        @Bean
        KnowledgeStore jdbcKnowledgeStore(JdbcTemplate jdbc, DataSource dataSource) {
            return new JdbcKnowledgeStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
        }

        @Bean
        TraceStore jdbcTraceStore(JdbcTemplate jdbc, JsonMapper json) {
            return new JdbcTraceStore(jdbc, json);
        }

        /** Conversations survive restarts and are shared by replicas; table from Flyway V2. */
        @Bean
        ChatMemoryRepository postgresChatMemoryRepository(JdbcTemplate jdbc) {
            return JdbcChatMemoryRepository.builder()
                    .jdbcTemplate(jdbc)
                    .dialect(new PostgresChatMemoryRepositoryDialect())
                    .build();
        }
    }

    // ---------- models ----------

    @Bean
    TokenCountEstimator tokenCountEstimator() {
        return new JTokkitTokenCountEstimator();
    }

    @Bean
    Embedder embedder(RagProperties props, ObjectProvider<EmbeddingModel> springAiModel) {
        RagProperties.Embedding cfg = props.embedding();
        if ("hashing".equals(cfg.provider())) {
            return new Embedder(new HashingEmbeddingModel(cfg.dimensions()), HashingEmbeddingModel.MODEL_NAME,
                    cfg.dimensions(), cfg.batchSize());
        }
        EmbeddingModel model = springAiModel.getIfAvailable(() -> {
            throw new IllegalStateException("rag.embedding.provider=spring-ai but no EmbeddingModel is configured; "
                    + "set spring.ai.model.embedding (openai|ollama)");
        });
        String name = cfg.modelName().isBlank() ? model.getClass().getSimpleName() : cfg.modelName();
        return new Embedder(model, name, cfg.dimensions(), cfg.batchSize());
    }

    @Bean
    Reranker reranker(RagProperties props) {
        return switch (props.rerank().provider()) {
            case "none" -> new NoopReranker();
            case "tei" -> new TeiReranker(props.rerank().url(), props.rerank().timeout());
            default -> throw new IllegalStateException("Unknown rag.rerank.provider " + props.rerank().provider());
        };
    }

    /**
     * The two ChatClients, built only in LLM mode: the main one answers; the utility one rewrites,
     * reformulates and judges, optionally on a cheaper model. Both log through SimpleLoggerAdvisor
     * when rag.generation.log-requests is on (it logs at DEBUG under its own logger).
     */
    public record ChatClients(ChatClient answer, ChatClient utility) {
    }

    @Bean
    @ConditionalOnProperty(name = "rag.generation.provider", havingValue = "llm")
    ChatClients chatClients(RagProperties props, ObjectProvider<ChatModel> chatModel) {
        ChatModel model = requireChat(chatModel);
        RagProperties.Generation g = props.generation();
        ChatClient answer = clientBuilder(model, g).build();
        ChatClient.Builder utility = clientBuilder(model, g);
        if (!g.utilityModel().isBlank()) {
            utility.defaultOptions(ChatOptions.builder().model(g.utilityModel()));
        }
        return new ChatClients(answer, utility.build());
    }

    private static ChatClient.Builder clientBuilder(ChatModel model, RagProperties.Generation g) {
        ChatClient.Builder b = ChatClient.builder(model);
        if (g.logRequests()) {
            b.defaultAdvisors(new SimpleLoggerAdvisor());
        }
        return b;
    }

    @Bean
    AnswerGenerator answerGenerator(RagProperties props, ObjectProvider<ChatClients> clients) {
        return switch (props.generation().provider()) {
            case "extractive" -> new ExtractiveAnswerGenerator();
            case "llm" -> new LlmAnswerGenerator(clients.getObject().answer());
            default -> throw new IllegalStateException("Unknown rag.generation.provider");
        };
    }

    @Bean
    QueryRewriter queryRewriter(RagProperties props, ObjectProvider<ChatClients> clients) {
        if ("llm".equals(props.generation().provider()) && props.generation().rewriteFollowUps()) {
            return new LlmQueryRewriter(clients.getObject().utility());
        }
        return new HeuristicQueryRewriter();
    }

    private static ChatModel requireChat(ObjectProvider<ChatModel> chatModel) {
        return chatModel.getIfAvailable(() -> {
            throw new IllegalStateException("rag.generation.provider=llm but no ChatModel is configured; "
                    + "set spring.ai.model.chat (openai|ollama)");
        });
    }

    // ---------- conversation memory (Spring AI ChatMemory) ----------

    @Bean
    @ConditionalOnProperty(name = "rag.store", havingValue = "memory", matchIfMissing = true)
    ChatMemoryRepository boundedChatMemoryRepository(RagProperties props) {
        return new BoundedChatMemoryRepository(props.conversation().maxConversations());
    }

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository repository, RagProperties props) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(props.conversation().maxTurns() * 2)
                .build();
    }

    // ---------- ingestion ----------

    @Bean(destroyMethod = "shutdown")
    ThreadPoolTaskExecutor ingestionExecutor(RagProperties props) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setThreadNamePrefix("ingest-");
        ex.setCorePoolSize(props.ingestion().workerThreads());
        ex.setMaxPoolSize(props.ingestion().workerThreads());
        // Bounded queue: under overload we reject (HTTP 503) instead of buffering uploads in memory.
        ex.setQueueCapacity(props.ingestion().queueCapacity());
        ex.setWaitForTasksToCompleteOnShutdown(true);
        ex.setAwaitTerminationSeconds(30);
        ex.initialize();
        return ex;
    }

    @Bean
    IngestionService ingestionService(RagProperties props, KnowledgeStore store, TokenCountEstimator tokens,
            Embedder embedder, ThreadPoolTaskExecutor ingestionExecutor, MeterRegistry meters) {
        RagProperties.Chunking c = props.chunking();
        return new IngestionService(store,
                new DocumentParser(props.ingestion().allowedExtensions()),
                new PiiRedactor(props.ingestion().redactPii()),
                new ParentChildChunker(tokens, c.parentTokens(), c.childTokens(), c.childOverlapTokens()),
                embedder, (Executor) ingestionExecutor, meters);
    }

    // ---------- query ----------

    /** Virtual threads: the two search legs block on I/O, so a thread each is free. */
    @Bean(destroyMethod = "shutdown")
    java.util.concurrent.ExecutorService retrievalExecutor() {
        return java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    }

    @Bean
    HybridRetriever hybridRetriever(RagProperties props, KnowledgeStore store, Reranker reranker,
            java.util.concurrent.ExecutorService retrievalExecutor) {
        RagProperties.Retrieval r = props.retrieval();
        RagProperties.Confidence c = props.confidence();
        return new HybridRetriever(store, reranker,
                new RetrievalSettings(RetrievalMode.parse(r.mode()), r.candidateK(), r.topK(), r.rrfK(), r.dedupJaccard()),
                new RetrievalSettings.Thresholds(c.high(), c.medium(), c.low()),
                retrievalExecutor);
    }

    @Bean
    ContextAssembler contextAssembler(RagProperties props, KnowledgeStore store, TokenCountEstimator tokens) {
        return new ContextAssembler(store, tokens, props.context().maxTokens(), props.context().parentExpansion());
    }

    @Bean
    RagService ragService(RagProperties props, KnowledgeStore store, Embedder embedder, HybridRetriever retriever,
            Reranker reranker, ContextAssembler assembler, QueryRewriter rewriter, AnswerGenerator generator,
            ChatMemory chatMemory, TraceStore traces, MeterRegistry meters) {
        RagProperties.Cache cache = props.cache();
        return new RagService(store, embedder, retriever, reranker, assembler,
                rewriter, generator, new GroundingVerifier(),
                new SemanticCache<ChatAnswer>(cache.similarity(), cache.ttl(), cache.maxEntries(), Clock.systemUTC()),
                cache.enabled(), props.confidence().correctiveRetry(),
                new ConversationMemory(chatMemory),
                traces, meters);
    }

    // ---------- Spring AI modular RAG ----------

    @Bean
    GroundedDocumentRetriever groundedDocumentRetriever(Embedder embedder, HybridRetriever retriever,
            ContextAssembler assembler) {
        return new GroundedDocumentRetriever(embedder, retriever, assembler);
    }

    /**
     * This service's retrieval as a drop-in advisor for any ChatClient:
     * {@code chatClient.prompt(q).advisors(groundedRagAdvisor)
     * .advisors(a -> a.param(GroundedDocumentRetriever.SPACES, callerSpaces))}.
     * The query is first rewritten into a standalone question; empty context makes the model
     * decline (allowEmptyContext = false) rather than answer from its own knowledge.
     */
    @Bean
    RetrievalAugmentationAdvisor groundedRagAdvisor(GroundedDocumentRetriever retriever, QueryRewriter rewriter) {
        return RetrievalAugmentationAdvisor.builder()
                .queryTransformers(new StandaloneQueryTransformer(rewriter))
                .documentRetriever(retriever)
                .queryAugmenter(ContextualQueryAugmenter.builder().allowEmptyContext(false).build())
                .build();
    }
}

package io.groundedrag.config;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Every tunable in one typed tree. Each value here is read by code (no dead flags):
 * see docs/CONFIGURATION.md for what each one changes and how it was chosen.
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        @DefaultValue("memory") String store,
        @DefaultValue Embedding embedding,
        @DefaultValue Generation generation,
        @DefaultValue Chunking chunking,
        @DefaultValue Retrieval retrieval,
        @DefaultValue Rerank rerank,
        @DefaultValue Confidence confidence,
        @DefaultValue Context context,
        @DefaultValue Cache cache,
        @DefaultValue Ingestion ingestion,
        @DefaultValue Conversation conversation,
        @DefaultValue Security security,
        @DefaultValue RateLimit rateLimit) {

    /** provider: hashing (offline, deterministic) or spring-ai (whatever EmbeddingModel Spring AI configured). */
    public record Embedding(
            @DefaultValue("hashing") String provider,
            @DefaultValue("384") int dimensions,
            @DefaultValue("64") int batchSize,
            @DefaultValue("") String modelName) {
    }

    /** provider: extractive (offline, no LLM) or llm (Spring AI ChatModel). */
    /**
     * utilityModel: an optional cheaper model (same provider) for side tasks such as follow-up
     * rewriting, reformulation and evaluation judging; empty means the main chat model.
     * logRequests: log every LLM request and response at DEBUG through Spring AI's SimpleLoggerAdvisor.
     */
    public record Generation(
            @DefaultValue("extractive") String provider,
            @DefaultValue("true") boolean rewriteFollowUps,
            @DefaultValue("") String utilityModel,
            @DefaultValue("false") boolean logRequests) {
    }

    public record Chunking(
            @DefaultValue("700") int parentTokens,
            @DefaultValue("180") int childTokens,
            @DefaultValue("40") int childOverlapTokens) {
    }

    /** mode: hybrid, vector or keyword. Non-hybrid modes exist for ablation in the evaluation harness. */
    public record Retrieval(
            @DefaultValue("hybrid") String mode,
            @DefaultValue("30") int candidateK,
            @DefaultValue("6") int topK,
            @DefaultValue("60") int rrfK,
            @DefaultValue("0.9") double dedupJaccard) {
    }

    /** provider: none or tei (HuggingFace text-embeddings-inference /rerank, e.g. bge-reranker). */
    public record Rerank(
            @DefaultValue("none") String provider,
            @DefaultValue("http://localhost:8081") String url,
            @DefaultValue("PT5S") Duration timeout) {
    }

    /**
     * Thresholds on retrieval confidence: evidence coverage without a reranker (model-independent),
     * the cross-encoder's score with one (the rerank profile sets its own).
     */
    public record Confidence(
            @DefaultValue("0.8") double high,
            @DefaultValue("0.6") double medium,
            @DefaultValue("0.42") double low,
            @DefaultValue("true") boolean correctiveRetry) {
    }

    public record Context(
            @DefaultValue("3000") int maxTokens,
            @DefaultValue("true") boolean parentExpansion) {
    }

    public record Cache(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0.97") double similarity,
            @DefaultValue("PT1H") Duration ttl,
            @DefaultValue("2000") int maxEntries) {
    }

    public record Ingestion(
            @DefaultValue("2") int workerThreads,
            @DefaultValue("100") int queueCapacity,
            @DefaultValue("true") boolean redactPii,
            @DefaultValue({"pdf", "docx", "doc", "pptx", "html", "htm", "md", "markdown", "txt", "csv"})
            Set<String> allowedExtensions) {
    }

    public record Conversation(
            @DefaultValue("6") int maxTurns,
            @DefaultValue("10000") int maxConversations) {
    }

    public record Security(@DefaultValue List<ApiKey> apiKeys, @DefaultValue Jwt jwt) {
    }

    /**
     * OIDC/JWT bearer tokens, enabled when issuer-uri (Keycloak, Okta, Entra ID, ...) or
     * hmac-secret (shared-secret HS256, for service-to-service and tests) is set.
     * The spaces claim may be a list or a space/comma separated string.
     */
    public record Jwt(
            @DefaultValue("") String issuerUri,
            @DefaultValue("") String hmacSecret,
            @DefaultValue("") String audience,
            @DefaultValue("spaces") String spacesClaim) {

        public boolean enabled() {
            return !issuerUri.isBlank() || !hmacSecret.isBlank();
        }
    }

    /** Token bucket per caller for the expensive endpoints (chat and upload). */
    public record RateLimit(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("30") int requestsPerMinute,
            @DefaultValue("10") int burst) {
    }

    /** spaces may contain "*" for an admin key that can address any space explicitly. */
    public record ApiKey(String key, String name, Set<String> spaces) {
    }
}

# Spring AI in this project

What this service uses from Spring AI 2.1, what it builds itself, and why. The checklist was
[Spring AI Recipes](https://github.com/habuma/spring-ai-recipes) (branch
`springai-2_1_0-dep-updates`, 80 recipes): each recipe relevant to a document Q&A service is
mapped below.

## Used, and where

| Spring AI feature | Recipe | Where | What it does here |
|---|---|---|---|
| `ChatModel`, `EmbeddingModel` with `spring.ai.model.*` selection | local-llms, multi-llm | `RagConfiguration`, profiles | OpenAI-compatible or Ollama, switched by profile, every unused model type off |
| `ChatClient` | all | `ChatClients` record | One client answers; a utility client rewrites, reformulates and judges, optionally on a cheaper model (`rag.generation.utility-model`) as in conversation-aware-rag's compression client |
| `SimpleLoggerAdvisor` | logging-requests-and-responses | `ChatClients` | Full LLM request/response logging at DEBUG when `rag.generation.log-requests=true` |
| Modular RAG: `DocumentRetriever` | hybrid-rag, rerank-rag | `springai/GroundedDocumentRetriever` | Our hybrid search, RRF, reranking, confidence and parent expansion, behind Spring AI's retriever interface; tenant spaces carried in `Query.context()`, failing closed |
| Modular RAG: `QueryTransformer` | conversation-aware-rag | `springai/StandaloneQueryTransformer` | Follow-up rewriting (the `CompressionQueryTransformer` role) backed by the same rewriter as the API |
| `RetrievalAugmentationAdvisor` + `ContextualQueryAugmenter` | essential-rag, conversation-aware-rag | `groundedRagAdvisor` bean | Drop-in RAG for any other `ChatClient`; empty context makes the model decline (`allowEmptyContext=false`) |
| `ChatMemory`, `MessageWindowChatMemory`, `ChatMemoryRepository` | persistent-chat-memory | `ConversationMemory`, `BoundedChatMemoryRepository` | Conversation history as Spring AI messages over a pluggable repository |
| `JdbcChatMemoryRepository` | persistent-chat-memory/jdbc-memory | postgres profile | Follow-ups survive restarts and work across replicas; table created by Flyway (`V2`), not by the library's auto-init |
| MCP server (`@McpTool`, streamable HTTP) | streamable-http-mcp-server, tool-rag | `mcp/KnowledgeBaseTools` | `search_documents` and `ask_documents` for agents (Claude Desktop/Code, any MCP client) |
| Secured MCP server | secured-mcp-server-api-key, secured-mcp-server-oauth | `SecurityConfig` | `/mcp` behind the same API-key / JWT chain and rate limit as the REST API; tools resolve the caller's spaces |
| Structured output (`.entity(...)`) | typesafe-intro | `FaithfulnessJudge` | Typed claim-by-claim verdicts |
| `StructuredOutputValidationAdvisor` | structured-output-validation | `FaithfulnessJudge` | Validates against the JSON schema and re-asks with the error. It caught a real contract bug on first use: `null` evidence in a `String` field |
| `RelevancyEvaluator`, `FactCheckingEvaluator` | fact-checking-evaluator | `Evaluator.Graders` | Two extra model-graded rows in the evaluation report beside our claim-level judge |
| Document readers (`PagePdfDocumentReader`, `TikaDocumentReader`) | essential-rag | `DocumentParser` | PDF per page (citations get page numbers), everything else via Tika |
| `JTokkitTokenCountEstimator` | | chunking, context budget | Token-accurate chunk sizes and the 3,000-token context budget |
| Observations (Micrometer) | | automatic | Token usage and latency of model calls, beside our own `rag.stage` timers |

## Built here on purpose, and why

| Recipe approach | What this project does instead | Why |
|---|---|---|
| `QuestionAnswerAdvisor` over a `VectorStore` (essential-rag) | Own pipeline in `RagService` | Needs keyword search, fusion, confidence-based decline *before* the LLM, verification after it and tracing per stage; the advisor chain has no place to stop without calling the model |
| `VectorStore` abstraction | `KnowledgeStore` port over pgvector + `tsvector` | Vector and full-text search plus the versioned swap must share one transaction (ADR 2); `VectorStore` covers only the vector half |
| BM25 via a separate Lucene index (hybrid-rag) | Postgres full-text in the same table | No second index to keep in sync with deletes and version swaps |
| LLM-as-reranker returning indexes (rerank-rag) | Cross-encoder over HTTP (`TeiReranker`) | Calibrated scores the confidence policy can threshold, and one model call less per query |
| Semantic cache in a vector store (semantic-caching) | `SemanticCache` keyed by corpus version | Re-ingesting a document must invalidate answers about it; a similarity-only cache cannot know |
| `SafeGuardAdvisor` / semantic safeguard (keyword or LLM-judged guardrails) | Not adopted yet | The domain risk here is unsupported answers, handled by abstention and verification. A guard on input topics is a policy decision per deployment; `SafeGuardAdvisor` is the right tool when one exists |
| HyDE query transformer (hyde-rag) | Not adopted | Unmeasured here: it needs a real model in the evaluation. It would slot in as one more `QueryTransformer` on the vector leg only, and has to beat the baseline on the golden set first |
| Graph workflows, Embabel, A2A, AG-UI | Not adopted | No agent loop in this product (ADR 10) |
| `InMemoryChatMemoryRepository` | `BoundedChatMemoryRepository` | The built-in one never evicts: a slow memory leak under real traffic |

## Using the service from Spring AI and from agents

From another Spring AI application in the same JVM, the retrieval is one advisor away:

```java
String answer = chatClient.prompt(question)
        .advisors(groundedRagAdvisor)
        .advisors(a -> a.param(GroundedDocumentRetriever.SPACES, callerSpaces)) // from the authenticated caller
        .call().content();
```

From an agent, use the MCP endpoint (`/mcp`, streamable HTTP) with an API key or bearer token:

```bash
claude mcp add --transport http grounded-rag http://localhost:8080/mcp --header "X-API-Key: dev-key-change-me-0001"
```

The agent sees `search_documents` and `ask_documents`. It can only reach the spaces that key or
token grants, and its calls count against the same per-caller rate limit.

# Configuration

## Profiles

| Profile | Turns on |
|---|---|
| default (none) | In-memory store, offline `hashing-v1` embeddings, extractive answers. No infrastructure or keys needed |
| `postgres` | Postgres + pgvector store, Flyway migrations, JDBC trace store (`DB_URL`, `DB_USER`, `DB_PASSWORD`) |
| `openai` | OpenAI-compatible chat and embeddings (`OPENAI_API_KEY`, `OPENAI_BASE_URL`, `CHAT_MODEL`) |
| `ollama` | Local chat and embeddings (`OLLAMA_BASE_URL`, `CHAT_MODEL`) |
| `rerank` | Cross-encoder reranking through text-embeddings-inference (`RERANK_URL`) |

Profiles combine, for example `postgres,openai,rerank`.

**Groq** serves chat only. Set `OPENAI_BASE_URL=https://api.groq.com/openai` and
`CHAT_MODEL=llama-3.3-70b-versatile`, and keep embeddings on another provider:
`rag.embedding.provider=hashing`, or Ollama with
`spring.ai.model.embedding=ollama rag.embedding.provider=spring-ai rag.embedding.dimensions=768`.

## Properties (`rag.*`)

| Property | Default | Effect |
|---|---|---|
| `store` | `memory` | `memory` or `postgres` |
| `embedding.provider` | `hashing` | `hashing` (offline) or `spring-ai` (whichever `EmbeddingModel` `spring.ai.model.embedding` selects) |
| `embedding.dimensions` | `384` | Must match the model; the pgvector column is created with it. A mismatch fails fast |
| `embedding.batch-size` | `64` | Texts per embedding request during ingest |
| `embedding.model-name` | | Stored on every document version, used to detect stale vectors after a model change |
| `generation.provider` | `extractive` | `extractive` (no LLM) or `llm` |
| `generation.rewrite-follow-ups` | `true` | Rewrite follow-ups into standalone questions with the LLM |
| `generation.utility-model` | | A cheaper model (same provider) for rewriting, reformulation and evaluation judging; empty means the main model |
| `generation.log-requests` | `false` | Log every LLM request and response through Spring AI's `SimpleLoggerAdvisor` (set `logging.level.org.springframework.ai.chat.client.advisor=DEBUG`) |
| `chunking.parent-tokens` / `child-tokens` / `child-overlap-tokens` | 700 / 180 / 40 | Small-to-big chunk sizes (ADR 4) |
| `retrieval.mode` | `hybrid` | `hybrid`, `vector` or `keyword` (the last two are for ablations) |
| `retrieval.candidate-k` | `30` | Candidates taken from each search before fusion |
| `retrieval.top-k` | `6` | Chunks kept after reranking |
| `retrieval.rrf-k` | `60` | RRF constant |
| `retrieval.dedup-jaccard` | `0.9` | Near-duplicate threshold |
| `rerank.provider` / `url` / `timeout` | `none` / / `5s` | `tei` for a cross-encoder; falls back to fused order on failure |
| `confidence.high` / `medium` / `low` | 0.8 / 0.6 / 0.42 | Thresholds on confidence (ADR 5) |
| `confidence.corrective-retry` | `true` | Reformulate and retry once when confidence is LOW or NONE |
| `context.max-tokens` | `3000` | Hard budget for the evidence sent to the model |
| `context.parent-expansion` | `true` | Send parent passages instead of child hits when they fit |
| `cache.enabled` / `similarity` / `ttl` / `max-entries` | true / 0.97 / 1h / 2000 | Semantic answer cache |
| `ingestion.worker-threads` / `queue-capacity` | 2 / 100 | Indexing concurrency and back-pressure |
| `ingestion.redact-pii` | `true` | Redact emails, phone numbers, SSNs, IBANs and Luhn-valid card numbers before indexing |
| `ingestion.allowed-extensions` | pdf, docx, doc, pptx, html, htm, md, markdown, txt, csv | Upload allow-list |
| `conversation.max-turns` / `max-conversations` | 6 / 10000 | Conversation memory bounds |
| `security.api-keys[]` | two dev keys | `{name, key, spaces[]}`; keys must be at least 16 characters; `*` is a wildcard |
| `security.jwt.issuer-uri` | | OIDC issuer (Keycloak realm, Okta, Entra ID). Enables bearer tokens; signing keys come from the issuer's JWKS |
| `security.jwt.hmac-secret` | | Alternative to issuer-uri: shared HS256 secret (≥ 32 bytes) for service-to-service calls and tests |
| `security.jwt.audience` | | When set, tokens must contain this audience |
| `security.jwt.spaces-claim` | `spaces` | Claim holding the caller's spaces: a list, or a space/comma separated string |
| `rate-limit.enabled` / `requests-per-minute` / `burst` | true / 30 / 10 | Per-caller token bucket on chat and upload |
| `eval.run` | `false` | Run the evaluation and exit (docs/EVALUATION.md) |

MCP server (`spring.ai.mcp.server.*`): `name: grounded-rag`, `protocol: streamable`, endpoint `/mcp`.
Set `spring.ai.mcp.server.enabled=false` to turn it off. Conversation memory keeps the last
`2 × conversation.max-turns` messages per conversation.

**Production checklist:**
- Replace the dev API keys (`RAG_DEMO_KEY`, `RAG_ADMIN_KEY`, or `RAG_SECURITY_APIKEYS_<n>_*`).
- Use the `postgres` profile.
- Set `embedding.model-name`.
- Expose `/actuator/prometheus` only to the metrics network.

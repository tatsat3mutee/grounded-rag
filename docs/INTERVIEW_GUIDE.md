# Interview guide

How to present this project, and answers to the questions it will draw. Every claim here points
to code, a test or a measured number, so you can back it up live.

## The 30-second version

> "I built a multi-tenant RAG service on Spring Boot and Spring AI that is designed around how RAG
> fails in production rather than the happy path. Every answer cites numbered sources and is
> verified before it's returned: citations must exist, and every number must appear in the
> evidence. When retrieval finds nothing relevant it declines without calling the LLM.
> Re-ingesting a document swaps versions atomically, so there's never a half-indexed document,
> and the cache keys on a corpus version, so it can't serve stale answers. A golden-set evaluation
> runs in CI and fails the build if retrieval regresses."

## The 2-minute walkthrough (draw this)

1. **Ingest** (async, bounded queue):
   - Hash for dedup.
   - Parse: PDF pages and Markdown sections become citation metadata.
   - Redact PII before anything is stored or sent to a provider.
   - Parent/child chunking: embed small chunks, show the model big ones.
   - Write the new version inactive, then one transaction swaps versions and bumps the corpus version.
2. **Query:**
   - Rewrite follow-ups into standalone questions, then check the cache (keyed by corpus version).
   - Hybrid search: pgvector plus Postgres full-text, fused with RRF.
   - Rerank, then score confidence.
   - If confidence is weak, reformulate and retry once. If there is no evidence, decline.
   - Expand to parent passages under a token budget, generate with numbered sources, verify, trace.
3. **Measure:**
   - 37-question golden set: recall@k, MRR and nDCG per retrieval mode, plus abstention metrics.
   - Runs in CI with baselines.

## Questions you should expect

**Why Spring AI and not LangChain?**
The model call is the easy 10%. The rest is transactions, authorisation, back-pressure, metrics and
testability, and the JVM stack handles those well. I used Spring AI for the provider-neutral
`ChatModel` and `EmbeddingModel` and the document readers, and wrote retrieval, confidence and
verification myself, because those are the decisions I need to control and unit-test. The cost is
a smaller ecosystem. I call the cross-encoder over HTTP and wrote a small evaluation harness.
(ADR 0001)

**Why pgvector instead of Pinecone, Weaviate or Qdrant?**
I need vectors, full-text search and document metadata in one transaction for the version swap.
At millions of chunks pgvector's HNSW is fine. The known weakness is filtered ANN under-returning
for small tenants, which I handle with pgvector 0.8's iterative scan. The `KnowledgeStore` port
means moving to a dedicated engine is one adapter. (ADR 0002)

**Why hybrid search? Why RRF?**
Embeddings miss exact identifiers and BM25 misses paraphrases. In my evaluation, vector-only missed
an error-code question that hybrid found. RRF fuses by rank, so I don't need to normalise cosine
against ts_rank. That would need per-corpus tuning and is unstable with few candidates. One detail
that matters: Postgres `plainto_tsquery` ANDs all terms, so I build an OR query and let `ts_rank_cd`
reward matching more of them. (ADR 0003)

**Your own numbers show keyword-only beating hybrid. Doesn't that undercut you?**
It's what I'd expect from the offline embedding, which is a hashing embedding and therefore
lexical. I report it instead of hiding it. The point of the harness is that the hybrid claim is
re-measured per embedding model with one command, instead of being asserted.

**How do you decide when not to answer?**
Every hit keeps its scores separately. RRF scores are about 0.03 whatever the relevance, so they
are never used for confidence. I measured raw cosine as a confidence signal and it failed: an
off-topic question outscored 15 answerable ones. Without a reranker I use evidence coverage, the
share of query terms (identifiers weighted double) present in the top evidence. With a
cross-encoder I use its calibrated score. If confidence is NONE, the service declines and never
calls the LLM. The offline result is 3/3 off-topic questions declined and 3/34 answerable ones
wrongly declined, all paraphrases. That's my quantified argument for the reranker. (ADR 0005)

**How do you prevent hallucinations?**
In layers:
1. The prompt requires a citation on every sentence, numbers copied exactly, and refusal when the
   answer is absent. Sources are marked as untrusted data, and a source cannot close its own tag.
2. A deterministic verifier runs on every answer. Citations must exist, and every number must
   appear in the evidence, with normalisation so 1,250 equals 1250.
3. Failures get a user-visible notice and are not cached.
4. Semantic faithfulness (an LLM judge) runs offline, where its cost doesn't hit users. (ADR 0007)

**What happens when someone re-uploads a document while it's being queried?**
- The new version is written inactive, and the old one keeps serving.
- Activation is one transaction: supersede old, activate new, bump the corpus version. A partial
  unique index makes two active versions impossible.
- If embedding fails halfway, only the new version is discarded and marked FAILED with the reason.
- A crash mid-ingest is cleaned up at startup. (ADR 0006)

**How is the cache invalidated?**
It isn't, explicitly. Each space has a monotonic corpus version that every activation or deletion
bumps, and cache entries are scoped by `space@version`, so stale entries just stop matching. No
purge, and no race between "ingest done" and "cache cleared". The key is the standalone question,
so two conversations asking "and for annual plans?" can't collide.

**How do you handle multi-tenancy?**
The API key determines the spaces. The request can narrow them but never widen them, and anything
else is a 403. Space ids are normalised in one value object, because a casing mismatch between
ingest and query is a real bug I've seen. Multi-space filters use `= ANY`, never ANDed equality,
and a contract test proves it on both stores. Traces and conversation memory are per principal.
(ADR 0008)

**How do users authenticate? Is it SSO-ready?**
Two ways in, one identity out. API keys are for services. For users there are OIDC bearer tokens
from Keycloak, Okta or Entra ID: issuer, expiry and audience are validated against the issuer's
JWKS, and the spaces come from a claim the identity provider fills from group membership. Both
paths produce the same `Caller`, so nothing downstream branches on auth type. A token without the
claim gets nothing. Tests cover expired, forged and wrong-audience tokens. (ADR 0008)

**How do you stop one tenant from burning the LLM budget?**
A token bucket per authenticated caller on chat and upload only, the endpoints that cost money.
It is keyed by caller, not IP, because behind a load balancer everyone shares an IP. Over the
limit the response is `429` with `Retry-After`. Buckets are in-process; for a global limit across
replicas they'd move to Redis.

**How do you keep the architecture from rotting?**
ArchUnit rules in the build: no Spring web or security types in domain packages, only the store
touches JDBC, no package cycles. The first run found two real cycles: retrieval and security were
reading the Spring properties tree. I fixed them by giving each package its own settings types,
mapped in the composition root. Coverage is gated at 85% (currently about 93%). (ADR 0009)

**How do follow-up questions work?**
History plus the follow-up goes to the LLM, which returns a standalone question. Retrieval,
caching and tracing all use that form. First questions skip the call to save latency. Offline,
a heuristic carries the previous question's terms into dependent follow-ups.

**How would you scale this?**
- The app is stateless except for conversation memory and the cache. Move both to Redis; each is
  one class, and correctness doesn't depend on it because invalidation is by version.
- Ingestion moves to a queue (SQS or Kafka) when there are multiple instances. The document table
  is already the job record.
- Partition chunks by space, or move to a dedicated vector engine, past about 50M chunks.
- Streaming uses virtual threads, so held connections are cheap.

**How do you know it works in production?**
- Per-stage Micrometer timers (`rag.stage{stage}`) and answer counters by confidence, grounded
  and cached, plus Spring AI's token-usage observations.
- Every answer stores a trace: chunk ids, scores, verification, timings.
- Thumbs-down feedback links to the exact evidence, which is how the golden set should grow.

**Tell me about a bug you found.**
The UI showed "network error" after every streamed answer, even though the tests passed. When an
SSE stream completes, Spring re-dispatches the request as ASYNC. My API-key filter, a
`OncePerRequestFilter`, skips async dispatches by default, so Spring Security saw an anonymous
request and aborted the response mid-chunk. MockMvc doesn't run that re-dispatch through the
filter chain, which is why it passed. The fix is a one-line dispatcher-type rule. The real lesson
is the test: I added one that streams over a real socket, and confirmed it fails without the fix.

**Tell me about a performance problem you found.**
The benchmark showed 36 ms p50 retrieval on Postgres with about 9k chunks, so I split it by leg
and ran `EXPLAIN ANALYZE`:
1. **Keyword search was 38 ms.** The OR query matched thousands of rows, and I was computing the
   384-dimension cosine distance for every matched row before sorting. Ranking in a subquery and
   computing cosine only for the top 30 brought it to about 10 ms.
2. **The two legs ran one after the other.** Running them concurrently on virtual threads made
   retrieval cost the slower leg.
3. **An apparent 22 ms vector search was JIT warm-up** in my own benchmark: Postgres was measured
   first. A proper warm-up showed 2 ms. Measuring the measurement matters.

The result was 36 ms to 12.5 ms p50. The same `EXPLAIN` session proved why the iterative scan
setting is needed. Without it, filtered HNSW returned 0 of 30 rows, because the space filter
removed all 40 candidates the index produced. (docs/PERFORMANCE.md)

**How much of Spring AI do you actually use, beyond the model client?**
I checked it against the Spring AI Recipes collection, recipe by recipe (docs/SPRING_AI.md).
- **Adopted where the framework is as good as or better than mine:**
  - `ChatMemory` with the JDBC repository, so conversations survive restarts.
  - `SimpleLoggerAdvisor`.
  - `StructuredOutputValidationAdvisor` on my judge. On its first run it caught a real contract
    bug: `null` evidence in a `String` field.
  - The `RelevancyEvaluator` and `FactCheckingEvaluator`.
  - The MCP server.
- **The pipeline stays mine:** it has to decline before calling the model and verify after, and an
  advisor chain always calls the model.
- **But my components implement Spring AI's `DocumentRetriever` and `QueryTransformer`,** and I
  expose a `RetrievalAugmentationAdvisor`, so any other `ChatClient` gets my retrieval in one line.
  Tenant spaces ride in `Query.context()` and fail closed. (ADR 0012)

**Can agents use it?**
Yes, over MCP. `/mcp` exposes `search_documents` and `ask_documents` with the same API-key or JWT
auth, space rules and rate limit as REST. The test drives it with the official MCP Java client, and
a forbidden space comes back as a tool error, not data.

**Why not LangChain or LangGraph?**
Because the problems they solve aren't present yet. Spring AI already gives me the model and
embedding abstraction, so LangChain4j would be a second client, a second message type and a second
tracing path for the same calls. The query path is a pipeline with two early exits and one bounded
retry: no cycles, no durable state, no human approval mid-run. That's what LangGraph is for. I
designed it graph-shaped (each step typed, `Plan` as the state, exits as conditional edges), so
moving to LangGraph4j is mechanical when there's a real trigger: an agentic loop, tool use with
branching, resumable runs, or human-in-the-loop. My thesis uses LangGraph exactly for that: a
critic revision loop over four agents. (ADR 0010)

**Why Java 25? Just to be on the latest?**
For three runtime changes that apply here:
- Virtual threads no longer pin on `synchronized` (JDK 24). My request threads are virtual, and the
  cache, memory and rate-limit buckets use `synchronized`. On 21 I'd have had to rewrite them with
  `ReentrantLock`.
- The AOT cache halved startup, 4.2 s to 1.9 s.
- Compact object headers cut live heap by 7%. That's modest, because most of the heap is embedding
  arrays.

The migration taught me two things worth saying:
- An incremental build kept Java 21 bytecode while every test passed. Check the class-file version,
  not the green build.
- The AOT cache is silently rejected if you train it with different JVM flags than you run with.
  (ADR 0011)

**What would you do next?**
1. Run the evaluation with real models, a reranker and the faithfulness judge, and grow the golden
   set from traces.
2. Run the judged evaluation nightly in CI, and add answer relevance.
3. Per-document ACLs as a store pre-filter, and Redis-backed rate limits and cache for multiple replicas.
4. Connectors (Confluence, SharePoint) with scheduled re-sync and deletion.
5. Redis for memory and cache, and a queue for ingestion when scaling out.

## Numbers to remember
- **Query path:** 30 candidates from each search → RRF (k = 60) → top 6 → 3,000-token context budget.
- **Chunk sizes:** parents 700 tokens, children 180, overlap 40.
- **Evaluation set:** 37 golden questions, 8 documents, 2 spaces.
- **Offline results:** hybrid recall@6 1.0 and MRR 0.89; 3/3 off-topic declined; 3/34 false
  abstentions, all paraphrases.
- **Tests:** 100+ executions across unit, contract (in-memory and Postgres), API (including JWT and
  rate limits), architecture rules, socket-level streaming and the evaluation gate. Line coverage
  is about 93%.
- **Latency (offline models, about 9k chunks, Postgres, JDK 25):** retrieval 12.9 ms p50 and 39 ms
  p95; vector search 2.5 ms; keyword search 8.5 ms. Down from 36 ms p50 before profiling.
- **Java 25:** startup 4.2 s → 1.9 s with the AOT cache; −7% live heap with compact object headers.

## Live demo script (5 minutes)
1. `./mvnw spring-boot:run`, open the UI, connect with the dev key, and upload `eval/corpus/product/*.md`.
2. Ask "What does error NW-4291 mean?": an identifier found via the keyword leg, cited.
3. Ask "Within how many days can a new annual customer get a full refund?", then follow up with
   "and monthly plans?": follow-up rewriting.
4. Ask "Which pizza toppings are served at the Austin office party?": declined, no LLM call (show
   the timings: no `generate` stage).
5. Ask the refund question again: answered from the cache. Re-upload an edited `refunds-and-cancellation.md`, ask
   again: not cached, new content (corpus version).
6. `curl /api/v1/answers/{id}` with the answer id: the full trace. Then show `target/eval-report.md` and the CI gate.
7. Call `/api/v1/chat` with `spaces: ["finance"]`: 403.

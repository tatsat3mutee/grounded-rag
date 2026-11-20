# Architecture decision records

Short records of decisions that shaped the design: context, decision, alternatives, consequences.

| # | Decision |
|---|---|
| [0001](0001-spring-ai-over-python.md) | Spring Boot + Spring AI rather than Python + LangChain |
| [0002](0002-postgres-pgvector.md) | Postgres + pgvector rather than a dedicated vector database |
| [0003](0003-hybrid-retrieval-rrf.md) | Hybrid retrieval fused with Reciprocal Rank Fusion |
| [0004](0004-parent-child-chunking.md) | Parent-child ("small-to-big") chunking |
| [0005](0005-confidence-and-abstention.md) | Confidence policy and abstention without an LLM call |
| [0006](0006-versioned-ingestion-and-state.md) | Versioned ingestion, corpus-version cache keys, where state lives |
| [0007](0007-verify-every-answer.md) | Deterministic verification on every answer; LLM judge offline |
| [0008](0008-tenancy-from-credentials.md) | Tenancy derived from credentials (API keys, OIDC/JWT), failing closed; per-caller rate limits |
| [0009](0009-enforced-quality-gates.md) | Quality enforced by the build: architecture rules, store contracts, eval gate, coverage |
| [0010](0010-no-langchain-langgraph-yet.md) | No LangChain4j or LangGraph4j yet, and the conditions that would change it |
| [0011](0011-java-25.md) | Java 25: virtual threads without pinning, AOT cache, compact object headers |
| [0012](0012-spring-ai-as-integration-surface.md) | Spring AI modular RAG and MCP as the integration surface, our pipeline as the engine |

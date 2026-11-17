# 0001: Spring Boot + Spring AI rather than Python + LangChain

## Context
The RAG ecosystem is Python-first, with LangChain, LlamaIndex and most rerankers and evaluation
libraries. Many enterprise backends that need RAG, though, are JVM services with Spring Security,
Micrometer, Flyway and established deployment pipelines. The hard parts of production RAG are not
model calls. They are ingestion jobs, transactions, authorisation, caching, observability and
testability, and those are the JVM stack's strengths.

## Decision
Java 25, Spring Boot 4, Spring AI 2 (Java 21 at first; see ADR 11). Spring AI provides the provider-neutral `ChatModel` and
`EmbeddingModel`, the document readers and the token estimator. Retrieval, ranking, confidence
and verification are this project's own code, because that is where the important decisions are
and they must be testable without a model.

## Alternatives
- **Python + LangChain or LlamaIndex.** Faster to prototype and richer in models. But framework
  abstractions hide the retrieval decisions that need tuning, and production concerns are rebuilt
  per project.
- **Spring AI's `QuestionAnswerAdvisor` and `VectorStore` end to end.** Less code, but no keyword
  search, no fusion, no versioned swap and no verification hook. It is used where it fits, not as
  the whole pipeline.

## Consequences
- **Gains:** typed configuration, compile-time contracts, Testcontainers, virtual threads for
  streaming, and one deployment model with existing JVM services.
- **Costs:** fewer ready-made rerankers and evaluation tools in Java. Mitigations: the reranker is
  called over HTTP (text-embeddings-inference), and evaluation is a small in-repo harness.

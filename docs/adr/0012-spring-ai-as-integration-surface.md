# 0012: Spring AI's modular RAG and MCP as the integration surface, our pipeline as the engine

## Context
Spring AI 2.1 offers modular RAG: `RetrievalAugmentationAdvisor` composed of a `QueryTransformer`,
a `DocumentRetriever`, `DocumentPostProcessor`s and a `QueryAugmenter`. It also offers chat memory
repositories, evaluators and an MCP server. The Spring AI Recipes collection builds RAG almost
entirely from these parts.

This service already had the same steps as its own classes. That was deliberate (ADR 1): the
pipeline must decline *before* calling the model, verify *after* it, and trace every stage. That
left two questions. Can other Spring AI code reuse our retrieval? And are we rebuilding things the
framework now does well?

## Decision
- **The pipeline stays the engine.** `RagService` keeps control of the order and the policy:
  confidence-based decline, corrective retry, verification and tracing. These have no slot in an
  advisor chain without calling the model.
- **Our components implement Spring AI's interfaces at the edge:**
  - `GroundedDocumentRetriever` implements `DocumentRetriever`.
  - `StandaloneQueryTransformer` implements `QueryTransformer`.
  - A `RetrievalAugmentationAdvisor` bean (`groundedRagAdvisor`) assembles them.

  Any `ChatClient` gets this service's retrieval with one `.advisors(...)` call. The caller's spaces
  travel in `Query.context()`, and missing spaces mean no documents: it fails closed.
- **The framework is adopted where it is as good or better than ours:**
  - `ChatMemory` over `ChatMemoryRepository`, with JDBC under Postgres. This replaces our in-process
    map and removes a documented limitation.
  - `SimpleLoggerAdvisor`.
  - `StructuredOutputValidationAdvisor` on the judge.
  - `RelevancyEvaluator` and `FactCheckingEvaluator` in the evaluation.
  - The MCP server.
- **MCP is the agent interface.** It exposes `search_documents` and `ask_documents` behind the
  same authentication, space authorisation and rate limit as REST.

## Alternatives
- **Rebuild on `RetrievalAugmentationAdvisor` end to end.** Less code, but abstention before
  generation, verification and per-stage traces would have to be bolted on as advisors around a
  chain that always calls the model.
- **Ignore the interfaces.** Keeps the codebase smaller, but makes the retrieval unusable from
  other Spring AI code and from agents. That is a poor trade for a shared knowledge service.

## Consequences
- **Two entry points to one implementation.** The API pipeline and the advisor share the same
  retriever, rewriter, reranker and context assembler, so their retrieval cannot drift. The advisor
  path has no abstention, verification or cache: it is meant for composition, not as the product's
  front door.
- **Spring AI's chat memory schema limits keys to 36 characters,** so the memory key is a
  name-based UUID of the principal and the conversation id.
- **Library starters that auto-configure beans we wire ourselves** (the JDBC chat memory starter)
  are replaced by their plain modules, so the composition root stays the single place where wiring
  happens.

# 0010: No LangChain4j or LangGraph4j, and when that changes

## Context
LangChain and LangGraph are the default RAG and agent frameworks in Python, and their Java ports,
LangChain4j and LangGraph4j, are mature. The question is not whether they are good, but whether
this service has the problems they solve.

What they would bring:

| Framework | What it provides | Already provided here by |
|---|---|---|
| LangChain4j | Model clients, embedding stores, retrievers, document loaders, "AI services", memory | Spring AI (`ChatModel`, `EmbeddingModel`, structured output, observations), our `KnowledgeStore`, Tika and PDF readers, `ConversationMemory` |
| LangGraph4j | A state graph: nodes, conditional edges, cycles, checkpointed state, human-in-the-loop interrupts, parallel branches with reducers | Nothing, because nothing needs it (below) |

The query path is a pipeline with two early exits and one bounded retry:

```
rewrite → embed → cache? ─hit→ done
                    └miss→ retrieve → (weak? reformulate, retrieve once) → none? ─→ decline
                                                                      └→ assemble → generate → verify → done
```

There are no cycles beyond the single retry. It is not long-running (sub-second without the model),
has no state to resume across processes, and needs no human approval step mid-run.

## Decision
- **Spring AI is the only LLM abstraction.** Adding LangChain4j beside it would give two model
  clients, two message types, two tracing paths and two retry policies, for capabilities already
  covered.
- **The pipeline stays plain Java in `RagService`,** with each step as a replaceable component. It
  is ordinary code to read, debug and unit-test, and its control flow is in one screen.
- **The design is graph-shaped on purpose,** so moving to a graph later is mechanical: each step is
  one call with typed inputs and outputs, `Plan` is the state, and the early exits are conditional
  edges.

## When this changes
Adopt LangGraph4j (or Spring AI's agent support, whichever is more mature at the time) when one of
these becomes a requirement:
1. **An agentic mode with a loop:** decompose a question, retrieve per sub-question, reflect or
   critique, and retry until a stop condition. That means more than one bounded retry, and real
   cycles.
2. **Tool use with branching:** for example SQL over billing tables, or an API lookup chosen by the
   model, mixed with retrieval.
3. **Durable, resumable runs:** long research tasks that must survive a restart or wait for a human
   to approve a step (checkpointing and interrupts).
4. **Parallel branches whose results are merged by policy,** beyond the two search legs we already
   run concurrently.

At that point the graph earns its overhead. The author's thesis project
([mtech-teleco-multiagent-project](https://github.com/tatsat3mutee/mtech-teleco-multiagent-project))
uses LangGraph exactly there: an investigator, reasoner, critic and reporter with a critic revision
loop.

## Consequences
- **Fewer moving parts:** one dependency tree for AI, one observation model (Micrometer through
  Spring AI), and one place to read the control flow.
- **We own the retrieval, ranking and verification code,** which is where the quality decisions are
  (ADR 1). A framework's default retriever would hide the decisions this project is about.
- **Revisit the moment a requirement from the list above appears.** It is the trigger, not the
  framework's popularity.

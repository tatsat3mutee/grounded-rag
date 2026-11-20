# 0006: Versioned ingestion, corpus-version cache keys, and where state lives

## Context
A typical "re-upload" is implemented as: delete old chunks, then embed new ones inside the HTTP
request. If embedding fails halfway, the document is gone. If the request is slow (vision models,
large PDFs), it times out. Separately, a semantic cache keyed only on question text keeps serving
answers about content that was replaced.

## Decision
- **Ingestion is asynchronous on a bounded pool.** Each upload creates a new document version, and
  the document row is the job record. The new chunks are written inactive. Activation swaps
  versions in one transaction, enforced by a partial unique index on the one active version.
  Failure discards only the new version.
- **Each space has a monotonic `corpus_version`, bumped on every activation or deletion.** Cache
  entries are scoped by `space@version` for every searched space, so stale entries can never
  match. There is no purge step to race with.
- **The cache key is the standalone question,** not the raw follow-up text.
- **The cache is in-process, bounded and LRU.** Documents, chunks, traces and (since ADR 12)
  conversation memory are in Postgres.

## Alternatives
- **A message queue (Kafka or SQS) for ingestion.** Worth it with multiple instances or very large
  volumes. Today the document table already provides durable job state and crash recovery.
- **Explicit cache purge on ingest.** Racy, and it needs a reverse index from documents to cached
  answers.

## Consequences
- For horizontal scaling, `SemanticCache` can move to Redis; correctness does not depend on it,
  because invalidation is by version. Conversation memory already uses Spring AI's
  `JdbcChatMemoryRepository` under the postgres profile.
- Ingestion jobs survive restarts as FAILED (with a reason) rather than disappearing.

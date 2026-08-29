-- Task 4-5: transactional outbox.
--
-- One row per domain event that must be published to RabbitMQ at-least-once.
-- The payload contains generated identifiers only; no filenames, eRef or
-- policy numbers, object keys, hashes, or document content are ever stored
-- here. Flyway is the only schema owner; this is plain SQLite SQL.

CREATE TABLE outbox_event (
    id TEXT PRIMARY KEY,
    aggregate_type TEXT NOT NULL CHECK (aggregate_type = 'INGESTION_JOB'),
    aggregate_id TEXT NOT NULL,
    event_type TEXT NOT NULL CHECK (event_type = 'INGESTION_REQUESTED'),
    payload_json TEXT NOT NULL,
    created_at_epoch_ms INTEGER NOT NULL,
    published_at_epoch_ms INTEGER,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at_epoch_ms INTEGER NOT NULL
);

CREATE INDEX ix_outbox_pending
    ON outbox_event (published_at_epoch_ms, next_attempt_at_epoch_ms, created_at_epoch_ms);

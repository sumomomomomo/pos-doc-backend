-- Task 3: initial persistence schema.
--
-- Flyway is the only schema owner: Hibernate runs with ddl-auto=none and
-- never creates or alters tables. Plain SQLite SQL only: no PRAGMA, BEGIN,
-- COMMIT, or rollback statements (Hikari connection properties own the
-- per-connection PRAGMAs; Flyway owns transactions).

CREATE TABLE storage_object (
    id TEXT PRIMARY KEY,
    object_key TEXT NOT NULL UNIQUE,
    original_filename TEXT NOT NULL,
    content_type TEXT NOT NULL,
    byte_size INTEGER NOT NULL CHECK (byte_size >= 0),
    sha256 TEXT NOT NULL CHECK (length(sha256) = 64),
    created_at_epoch_ms INTEGER NOT NULL
);

CREATE TABLE pos_record (
    id TEXT PRIMARY KEY,
    source_archive_id TEXT NOT NULL UNIQUE REFERENCES storage_object (id) ON DELETE RESTRICT,
    eref_number TEXT,
    eref_number_normalized TEXT,
    policy_number TEXT,
    policy_number_normalized TEXT,
    policyholder_name TEXT,
    policyholder_name_normalized TEXT,
    consultant_name TEXT,
    policy_create_date TEXT,
    status TEXT NOT NULL CHECK (status IN ('UPLOADED', 'VALIDATING', 'PROCESSING', 'REVIEW_REQUIRED', 'COMPLETED', 'FAILED')),
    uploaded_by TEXT NOT NULL,
    uploaded_at_epoch_ms INTEGER NOT NULL,
    updated_at_epoch_ms INTEGER NOT NULL,
    deleted_at_epoch_ms INTEGER,
    version INTEGER NOT NULL CHECK (version >= 0)
);

CREATE UNIQUE INDEX uq_pos_record_active_eref
    ON pos_record (eref_number_normalized)
    WHERE deleted_at_epoch_ms IS NULL
      AND eref_number_normalized IS NOT NULL;

CREATE UNIQUE INDEX uq_pos_record_active_policy
    ON pos_record (policy_number_normalized)
    WHERE deleted_at_epoch_ms IS NULL
      AND policy_number_normalized IS NOT NULL;

CREATE INDEX ix_pos_record_policyholder_name_normalized
    ON pos_record (policyholder_name_normalized);

CREATE TABLE pos_document (
    id TEXT PRIMARY KEY,
    pos_record_id TEXT NOT NULL REFERENCES pos_record (id) ON DELETE RESTRICT,
    storage_object_id TEXT NOT NULL UNIQUE REFERENCES storage_object (id) ON DELETE RESTRICT,
    sequence_number INTEGER NOT NULL CHECK (sequence_number >= 0),
    document_type TEXT NOT NULL CHECK (document_type IN ('FA_PRUPLANNER_REPORT', 'OTHER', 'UNKNOWN')),
    processing_status TEXT NOT NULL CHECK (processing_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'SKIPPED')),
    UNIQUE (pos_record_id, sequence_number)
);

CREATE TABLE ingestion_job (
    id TEXT PRIMARY KEY,
    pos_record_id TEXT NOT NULL REFERENCES pos_record (id) ON DELETE RESTRICT,
    status TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'RETRY_SCHEDULED', 'COMPLETED', 'FAILED')),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    error_code TEXT,
    error_message TEXT,
    created_at_epoch_ms INTEGER NOT NULL,
    started_at_epoch_ms INTEGER,
    completed_at_epoch_ms INTEGER,
    version INTEGER NOT NULL CHECK (version >= 0)
);

CREATE INDEX ix_ingestion_job_record_created
    ON ingestion_job (pos_record_id, created_at_epoch_ms);

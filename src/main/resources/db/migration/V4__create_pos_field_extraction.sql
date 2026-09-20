-- Task: durable per-document, per-field structured extraction outcomes.
--
-- One row per (document, field, prompt version). The structured workflow uses
-- prompt version 2; prompt version 1 belongs to the old generic OCR workflow
-- (historical document_ocr_result table, left in place). The composite primary
-- key makes each (document, field, prompt_version) outcome durable and
-- idempotent: INSERT ... ON CONFLICT DO NOTHING then re-read.
--
-- value_text holds only the canonical validated value (a validated name, or an
-- ISO yyyy-MM-dd date). The full model response, image, prompt, PDF content, or
-- response body is never stored here. error_code is a stable sanitized code with
-- no PII or remote response content.
--
-- The outcome/value/error constraints encode the three legal shapes:
--   RESOLVED -> value_text present, error_code NULL
--   UNKNOWN  -> value_text NULL, error_code NULL
--   FAILED   -> value_text NULL, error_code present
--
-- Flyway is the only schema owner; this is plain SQLite SQL.

CREATE TABLE pos_field_extraction (
    document_id TEXT NOT NULL REFERENCES pos_document (id) ON DELETE RESTRICT,
    field_name TEXT NOT NULL
        CHECK (field_name IN ('POLICYHOLDER_NAME', 'CONSULTANT_NAME', 'POLICY_CREATE_DATE')),
    prompt_version INTEGER NOT NULL CHECK (prompt_version > 0),
    outcome TEXT NOT NULL
        CHECK (outcome IN ('RESOLVED', 'UNKNOWN', 'FAILED')),
    value_text TEXT,
    model TEXT NOT NULL CHECK (length(trim(model)) > 0),
    finish_reason TEXT,
    attempt_count INTEGER NOT NULL CHECK (attempt_count BETWEEN 1 AND 3),
    error_code TEXT,
    completed_at_epoch_ms INTEGER NOT NULL,
    PRIMARY KEY (document_id, field_name, prompt_version),
    CHECK (
        (outcome = 'RESOLVED' AND value_text IS NOT NULL AND length(trim(value_text)) > 0 AND error_code IS NULL)
        OR
        (outcome = 'UNKNOWN' AND value_text IS NULL AND error_code IS NULL)
        OR
        (outcome = 'FAILED' AND value_text IS NULL AND error_code IS NOT NULL)
    )
);

CREATE INDEX ix_pos_field_extraction_document
    ON pos_field_extraction (document_id);

-- Task 9: durable per-document OCR results.
--
-- One row per (document, prompt version). Task 9 uses prompt version 1.
-- The composite primary key (document_id, prompt_version) prevents
-- duplicate results for the same document and version. A future task may
-- create another version for the same document; Task 9 never deletes
-- historical versions.
--
-- Raw OCR text is not a search surface: no OCR-text index is created.
-- Flyway is the only schema owner; this is plain SQLite SQL.

CREATE TABLE document_ocr_result (
    document_id TEXT NOT NULL REFERENCES pos_document (id) ON DELETE RESTRICT,
    prompt_version INTEGER NOT NULL CHECK (prompt_version > 0),
    ocr_text TEXT NOT NULL CHECK (length(trim(ocr_text)) > 0 AND length(ocr_text) <= 1000000),
    model TEXT NOT NULL CHECK (length(trim(model)) > 0),
    finish_reason TEXT NOT NULL CHECK (length(trim(finish_reason)) > 0),
    character_count INTEGER NOT NULL CHECK (character_count >= 0 AND character_count = length(ocr_text)),
    completed_at_epoch_ms INTEGER NOT NULL,
    PRIMARY KEY (document_id, prompt_version)
);

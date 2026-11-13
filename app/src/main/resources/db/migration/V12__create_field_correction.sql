-- Reviewer field corrections (REV-3, architecture.md §6.8, §7, ADR-30): the audit trail of every
-- edit, one row per changed field. A correction belongs to the extraction it corrected, so a new
-- Extract run starts from the model's values again. Values are pseudonymized like the extraction;
-- PII token fields can't be corrected. The current value of a field is its latest correction
-- (created_at, then id) over the extraction.
CREATE TABLE field_correction (
    id            UUID         PRIMARY KEY,
    document_id   UUID         NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    extraction_id UUID         NOT NULL REFERENCES extraction (id) ON DELETE CASCADE,
    field_path    VARCHAR(100) NOT NULL,
    old_value     TEXT         NOT NULL,
    new_value     TEXT         NOT NULL,
    corrected_by  VARCHAR(255) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX field_correction_extraction_idx ON field_correction (extraction_id, created_at);
CREATE INDEX field_correction_document_idx ON field_correction (document_id);

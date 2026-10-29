-- Outputs of the PII stage (architecture.md §5.2, §6.3, §7, M2.10b).

-- One row per kept (non-seller) span of a PII run. No raw values: `token` is the replacement text
-- (spec token, or the [REDACTED:TYPE] marker in redact mode and for PIN codes) and `bbox` is the
-- union box of the span's words in the page's unit. `detector` joins every contributing rule name
-- with '+', so it is TEXT.
CREATE TABLE pii_span (
    id          UUID        PRIMARY KEY,
    document_id UUID        NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    run_no      INTEGER     NOT NULL,
    page_no     INTEGER     NOT NULL,
    span_start  INTEGER     NOT NULL,
    span_end    INTEGER     NOT NULL,
    bbox        JSONB       NOT NULL,
    type        VARCHAR(32) NOT NULL,
    detector    TEXT        NOT NULL,
    token       TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pii_span_run_no_ck CHECK (run_no >= 1),
    CONSTRAINT pii_span_page_no_ck CHECK (page_no >= 1),
    CONSTRAINT pii_span_range_ck CHECK (span_start >= 0 AND span_end > span_start)
);

CREATE INDEX pii_span_document_run_idx ON pii_span (document_id, run_no);

-- Encrypted original values by token (PII-3, PII-7). Shared by all documents: the same value
-- always gives the same token, so a row is written once and never changed. Only the pii module
-- reads or writes it (§4.1); the key is never in the database.
CREATE TABLE pii_vault (
    token      TEXT        PRIMARY KEY,
    type       VARCHAR(32) NOT NULL,
    ciphertext BYTEA       NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

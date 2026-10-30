-- One row per succeeded Extract run (EXT-1, EXT-2, EXT-5, architecture.md §6.5, §7, M3.8).
-- `result` is the InvoiceExtraction JSON: pseudonymized values only (specs.md §6), stored only
-- after the leak check passed (PII-6). `prompt_version` is `name/v{n}`.
CREATE TABLE extraction (
    id             UUID        PRIMARY KEY,
    document_id    UUID        NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    run_no         INTEGER     NOT NULL,
    strategy       VARCHAR(16) NOT NULL,
    model_id       TEXT        NOT NULL,
    prompt_version TEXT        NOT NULL,
    result         JSONB       NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT extraction_run_uk UNIQUE (document_id, run_no),
    CONSTRAINT extraction_strategy_ck CHECK (strategy IN ('TEXT', 'VISION', 'HYBRID')),
    CONSTRAINT extraction_run_no_ck CHECK (run_no >= 1)
);

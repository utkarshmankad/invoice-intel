-- Output of the Text stage (architecture.md §5.2, §6.2, §7): the words and boxes of every page
-- as TextLayer JSON, one row per Text run. Contains PII; never exposed by the API (PII-8).
CREATE TABLE text_layer (
    id          UUID        PRIMARY KEY,
    document_id UUID        NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    run_no      INTEGER     NOT NULL,
    layer       JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT text_layer_run_no_ck CHECK (run_no >= 1),
    CONSTRAINT text_layer_run_uk UNIQUE (document_id, run_no)
);

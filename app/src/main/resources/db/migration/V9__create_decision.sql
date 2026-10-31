-- The VAL-8 decision of a document (architecture.md §6.6, §7, M4.6). One row per document,
-- replaced when the document is validated again. `reasons` is a JSON array of
-- {code, ruleId, message}; messages name rules, fields and amounts, never document text (PII-8).
-- A FAILED decision may have no extraction (the extraction is missing).
CREATE TABLE decision (
    id            UUID         PRIMARY KEY,
    document_id   UUID         NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    extraction_id UUID         REFERENCES extraction (id) ON DELETE CASCADE,
    score         NUMERIC(5,4) NOT NULL,
    outcome       VARCHAR(16)  NOT NULL,
    reasons       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT decision_document_uk UNIQUE (document_id),
    CONSTRAINT decision_outcome_ck CHECK (outcome IN ('AUTO_APPROVED', 'NEEDS_REVIEW', 'FAILED')),
    CONSTRAINT decision_score_ck CHECK (score BETWEEN 0 AND 1),
    CONSTRAINT decision_extraction_ck CHECK (extraction_id IS NOT NULL OR outcome = 'FAILED'),
    CONSTRAINT decision_reasons_ck CHECK (jsonb_typeof(reasons) = 'array')
);

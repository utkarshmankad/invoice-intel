-- One row per rule per extraction (VAL-1..7, architecture.md §6.6, §7, M4.1). Validating an
-- extraction again replaces its rows. `message` is set exactly for WARN and FAIL; it may name
-- fields and amounts, never document text (PII-8).
CREATE TABLE validation_result (
    id            UUID        PRIMARY KEY,
    extraction_id UUID        NOT NULL REFERENCES extraction (id) ON DELETE CASCADE,
    rule_id       VARCHAR(16) NOT NULL,
    outcome       VARCHAR(8)  NOT NULL,
    message       TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT validation_result_rule_uk UNIQUE (extraction_id, rule_id),
    CONSTRAINT validation_result_outcome_ck CHECK (outcome IN ('PASS', 'WARN', 'FAIL')),
    CONSTRAINT validation_result_message_ck CHECK ((outcome = 'PASS') = (message IS NULL))
);

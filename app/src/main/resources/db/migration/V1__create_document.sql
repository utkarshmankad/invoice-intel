-- One row per uploaded file (architecture.md §7). Statuses follow the state machine in §5.1.
CREATE TABLE document (
    id             UUID         PRIMARY KEY,
    sha256         CHAR(64)     NOT NULL,
    mime_type      VARCHAR(255) NOT NULL,
    status         VARCHAR(32)  NOT NULL,
    document_class VARCHAR(32),
    failed_stage   VARCHAR(32),
    failure_code   VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT document_sha256_uk UNIQUE (sha256),
    CONSTRAINT document_sha256_hex_ck CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT document_status_ck CHECK (status IN (
        'RECEIVED', 'TEXT_READY', 'PII_CLEAN', 'EXTRACTED', 'VALIDATED',
        'AUTO_APPROVED', 'NEEDS_REVIEW', 'REVIEWED', 'RECONCILED', 'FAILED')),
    -- FAILED always records the stage and reason code (§5.1); other statuses never do.
    CONSTRAINT document_failure_ck CHECK (
        (status = 'FAILED') = (failed_stage IS NOT NULL AND failure_code IS NOT NULL)
        AND (failed_stage IS NULL) = (failure_code IS NULL))
);

CREATE INDEX document_status_idx ON document (status);

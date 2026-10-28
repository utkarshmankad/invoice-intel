-- One row per run of a pipeline stage on a document (architecture.md §5.1, §7). A reprocess
-- supersedes the runs of the stage it restarts and of every later stage; the stage's outputs
-- are keyed by document, stage and run_no.
CREATE TABLE stage_run (
    id          UUID        PRIMARY KEY,
    document_id UUID        NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    stage       VARCHAR(32) NOT NULL,
    run_no      INTEGER     NOT NULL,
    status      VARCHAR(16) NOT NULL,
    started_at  TIMESTAMPTZ NOT NULL,
    ended_at    TIMESTAMPTZ,
    error_code  VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT stage_run_stage_ck CHECK (stage IN ('TEXT', 'PII', 'EXTRACT', 'VALIDATE')),
    CONSTRAINT stage_run_status_ck CHECK (status IN (
        'RUNNING', 'SUCCEEDED', 'FAILED', 'SUPERSEDED')),
    CONSTRAINT stage_run_run_no_ck CHECK (run_no >= 1),
    -- Only a running run has no end time.
    CONSTRAINT stage_run_ended_ck CHECK ((status = 'RUNNING') = (ended_at IS NULL)),
    -- A failed run records why; a running or succeeded one never does. Superseded runs keep
    -- the code they had.
    CONSTRAINT stage_run_error_ck CHECK (
        (status <> 'FAILED' OR error_code IS NOT NULL)
        AND (status NOT IN ('RUNNING', 'SUCCEEDED') OR error_code IS NULL)),
    CONSTRAINT stage_run_run_uk UNIQUE (document_id, stage, run_no)
);

-- At most one run of a stage per document at a time.
CREATE UNIQUE INDEX stage_run_one_running_uk ON stage_run (document_id, stage)
    WHERE status = 'RUNNING';

-- failed_stage names a stage of the chain.
ALTER TABLE document ADD CONSTRAINT document_failed_stage_ck CHECK (
    failed_stage IS NULL OR failed_stage IN ('TEXT', 'PII', 'EXTRACT', 'VALIDATE'));

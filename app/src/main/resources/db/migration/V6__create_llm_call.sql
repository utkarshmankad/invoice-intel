-- One row per model call of the Extract stage (EXT-4, NFR-4, architecture.md §6.5, §7, M3.3).
-- IDs, counts and codes only: no prompt or response text (PII-8). `prompt_version` is
-- `name/v{n}` (EXT-5); `attempt` is 2 for the repair retry. Per-call costs are fractions of a
-- cent, so `cost_usd` keeps 6 decimal places.
CREATE TABLE llm_call (
    id             UUID          PRIMARY KEY,
    document_id    UUID          NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    stage          VARCHAR(16)   NOT NULL,
    provider       TEXT          NOT NULL,
    model_id       TEXT          NOT NULL,
    prompt_version TEXT          NOT NULL,
    attempt        INTEGER       NOT NULL,
    input_tokens   INTEGER       NOT NULL,
    output_tokens  INTEGER       NOT NULL,
    cost_usd       NUMERIC(12,6) NOT NULL,
    latency_ms     BIGINT        NOT NULL,
    outcome        VARCHAR(16)   NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT llm_call_stage_ck CHECK (stage IN ('TEXT', 'PII', 'EXTRACT', 'VALIDATE')),
    CONSTRAINT llm_call_outcome_ck CHECK (outcome IN ('SUCCEEDED', 'INVALID_OUTPUT', 'ERROR')),
    CONSTRAINT llm_call_attempt_ck CHECK (attempt >= 1),
    CONSTRAINT llm_call_counts_ck CHECK (input_tokens >= 0 AND output_tokens >= 0
        AND cost_usd >= 0 AND latency_ms >= 0)
);

CREATE INDEX llm_call_document_idx ON llm_call (document_id);

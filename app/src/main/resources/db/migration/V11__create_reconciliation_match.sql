-- The latest reconciliation (REC-2..REC-4, architecture.md §6.7, §7, M6.3): one row per order
-- token, plus one row per invoice without an order number (null token). A run replaces every row
-- in one transaction. Order numbers are tokens only (PII-3); `invoice_ids` is a JSON array of
-- document IDs, empty for ORDER_WITHOUT_INVOICE.
CREATE TABLE reconciliation_match (
    id          UUID          PRIMARY KEY,
    order_token VARCHAR(64),
    status      VARCHAR(32)   NOT NULL,
    invoice_ids JSONB         NOT NULL,
    amount_diff NUMERIC(14,2),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT reconciliation_match_status_ck CHECK (status IN ('MATCHED', 'AMOUNT_MISMATCH',
        'DATE_MISMATCH', 'INVOICE_WITHOUT_ORDER', 'ORDER_WITHOUT_INVOICE')),
    CONSTRAINT reconciliation_match_invoice_ids_ck CHECK (jsonb_typeof(invoice_ids) = 'array'),
    CONSTRAINT reconciliation_match_token_ck CHECK (order_token IS NOT NULL
        OR status = 'INVOICE_WITHOUT_ORDER')
);

CREATE INDEX reconciliation_match_order_token_ix ON reconciliation_match (order_token);

-- Imported order-history rows (REC-1, architecture.md §6.7, §7, M6.1). Order numbers are stored
-- as tokens only (PII-3); product names are not PII (spec §6.1). Every row of one import shares
-- its import_id; imports add up.
CREATE TABLE order_history_item (
    id           UUID          PRIMARY KEY,
    import_id    UUID          NOT NULL,
    order_token  VARCHAR(64)   NOT NULL,
    order_date   DATE,
    product_name TEXT,
    asin         VARCHAR(32),
    quantity     INTEGER,
    unit_price   NUMERIC(14,2),
    item_total   NUMERIC(14,2),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX order_history_item_order_token_ix ON order_history_item (order_token);
CREATE INDEX order_history_item_import_ix ON order_history_item (import_id);

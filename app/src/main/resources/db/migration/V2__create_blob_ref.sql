-- Pointers into object storage (architecture.md §7, §9.1). The upload writes the ORIGINAL row;
-- page images and redacted output follow in later stages.
CREATE TABLE blob_ref (
    id          UUID         PRIMARY KEY,
    document_id UUID         NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    kind        VARCHAR(32)  NOT NULL,
    bucket      VARCHAR(63)  NOT NULL,
    key         VARCHAR(1024) NOT NULL,
    page_no     INTEGER,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT blob_ref_kind_ck CHECK (kind IN (
        'ORIGINAL', 'PAGE_IMAGE', 'REDACTED_PAGE', 'REDACTED_PDF')),
    -- Page kinds carry a page number; whole-document kinds don't.
    CONSTRAINT blob_ref_page_no_ck CHECK (
        (kind IN ('PAGE_IMAGE', 'REDACTED_PAGE')) = (page_no IS NOT NULL)),
    CONSTRAINT blob_ref_location_uk UNIQUE (bucket, key)
);

CREATE INDEX blob_ref_document_idx ON blob_ref (document_id);

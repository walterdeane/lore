
CREATE TABLE document (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    domain_id UUID NOT NULL REFERENCES domain(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    author TEXT,
    source_filename TEXT NOT NULL,
    source_path TEXT NOT NULL,
    source_type TEXT NOT NULL CHECK (source_type IN ('PDF','EPUB')),
    tags LTREE[] NOT NULL DEFAULT '{}',
    ingestion_status TEXT NOT NULL CHECK (ingestion_status IN ('PENDING','IN_PROGRESS','COMPLETED','FAILED')) DEFAULT 'PENDING',
    ingestion_error TEXT,
    ingested_at TIMESTAMPTZ,
    UNIQUE (domain_id, source_filename)
);

CREATE INDEX document_domain_id_idx ON document (domain_id);
CREATE INDEX document_tags_idx ON document USING GIST (tags gist__ltree_ops);


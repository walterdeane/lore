CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS ltree;

-- Strip basic markdown syntax before full-text-search indexing.
CREATE OR REPLACE FUNCTION strip_markdown(input text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT AS
$$
SELECT trim(regexp_replace(regexp_replace(input, '[#*`_~\[\]()!>]', ' ', 'g'), '\s+', ' ', 'g'))
$$;

CREATE TABLE domain (
    id               UUID    PRIMARY KEY DEFAULT gen_random_uuid(),
    name             TEXT    NOT NULL,
    description      TEXT    NOT NULL,
    chunk_strategy   TEXT,
    structural_variant TEXT
);

CREATE TABLE tag (
    id          UUID  PRIMARY KEY DEFAULT gen_random_uuid(),
    domain_id   UUID  NOT NULL REFERENCES domain(id) ON DELETE CASCADE,
    name        TEXT  NOT NULL,
    description TEXT  NOT NULL,
    path        LTREE NOT NULL,
    UNIQUE (domain_id, path)
);

CREATE INDEX tag_path_gist_idx ON tag USING GIST (path);

CREATE TABLE document (
    id                 UUID       PRIMARY KEY DEFAULT gen_random_uuid(),
    domain_id          UUID       NOT NULL REFERENCES domain(id) ON DELETE CASCADE,
    title              TEXT       NOT NULL,
    author             TEXT,
    source_filename    TEXT       NOT NULL,
    source_path        TEXT       NOT NULL,
    source_type        TEXT       NOT NULL CHECK (source_type IN ('PDF', 'EPUB')),
    tags               LTREE[]    NOT NULL DEFAULT '{}',
    ingestion_status   TEXT       NOT NULL CHECK (ingestion_status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED')) DEFAULT 'PENDING',
    ingestion_error    TEXT,
    ingested_at        TIMESTAMPTZ,
    chunk_strategy     TEXT,
    structural_variant TEXT,
    UNIQUE (domain_id, source_filename)
);

CREATE INDEX document_domain_id_idx ON document (domain_id);
CREATE INDEX document_tags_idx      ON document USING GIST (tags gist__ltree_ops);

CREATE TABLE chunk (
    id              UUID       PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id     UUID       NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    domain_id       UUID       NOT NULL REFERENCES domain(id)   ON DELETE CASCADE,
    tag_paths       LTREE[]    NOT NULL DEFAULT '{}',
    content         TEXT       NOT NULL,
    embedding       VECTOR(768) NOT NULL,
    chunk_index     INT        NOT NULL,
    chunk_strategy  TEXT,
    page_number     INT,
    token_count     INT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    search_vector   TSVECTOR   GENERATED ALWAYS AS (to_tsvector('english', strip_markdown(content))) STORED,
    UNIQUE (document_id, chunk_index)
);

CREATE INDEX chunk_document_id_idx    ON chunk (document_id);
CREATE INDEX chunk_domain_id_idx      ON chunk (domain_id);
CREATE INDEX chunk_tag_paths_idx      ON chunk USING GIST (tag_paths gist__ltree_ops);
CREATE INDEX chunk_embedding_idx      ON chunk USING hnsw (embedding vector_cosine_ops);
CREATE INDEX chunk_search_vector_idx  ON chunk USING GIN (search_vector);

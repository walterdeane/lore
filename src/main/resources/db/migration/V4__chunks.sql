CREATE TABLE chunk (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    domain_id UUID NOT NULL REFERENCES domain(id) ON DELETE CASCADE,
    tag_paths LTREE[] NOT NULL DEFAULT '{}',
    content TEXT NOT NULL,
    embedding VECTOR(768) NOT NULL,
    chunk_index INT NOT NULL,
    page_number INT,
    token_count INT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (document_id, chunk_index)
);

CREATE INDEX chunk_document_id_idx ON chunk (document_id);
CREATE INDEX chunk_domain_id_idx ON chunk (domain_id);
CREATE INDEX chunk_tag_paths_idx ON chunk USING GIST (tag_paths gist__ltree_ops);
CREATE INDEX chunk_embedding_idx ON chunk USING hnsw (embedding vector_cosine_ops);

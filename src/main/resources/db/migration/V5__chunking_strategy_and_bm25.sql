ALTER TABLE domain ADD COLUMN chunk_strategy TEXT
    CHECK (chunk_strategy IN ('TOKEN','SEMANTIC','STRUCTURAL'));

ALTER TABLE document ADD COLUMN chunk_strategy TEXT
    CHECK (chunk_strategy IN ('TOKEN','SEMANTIC','STRUCTURAL'));

ALTER TABLE chunk ADD COLUMN parent_chunk_id UUID REFERENCES chunk(id);
ALTER TABLE chunk ADD COLUMN chunk_level TEXT
    CHECK (chunk_level IN ('SECTION','PARAGRAPH'));
ALTER TABLE chunk ADD COLUMN chunk_strategy TEXT NOT NULL DEFAULT 'TOKEN';

ALTER TABLE chunk ADD COLUMN search_vector TSVECTOR
    GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;
CREATE INDEX chunk_search_vector_idx ON chunk USING GIN (search_vector);

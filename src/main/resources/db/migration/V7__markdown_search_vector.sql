-- Strip basic markdown syntax before BM25 indexing so # * ` _ etc. don't pollute tsvector.
CREATE OR REPLACE FUNCTION strip_markdown(input text) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT AS
$$
SELECT trim(regexp_replace(regexp_replace(input, '[#*`_~\[\]()!>]', ' ', 'g'), '\s+', ' ', 'g'))
$$;

-- Recreate the generated column using the new function.
-- The old index is dropped automatically when the column is dropped.
ALTER TABLE chunk DROP COLUMN IF EXISTS search_vector;

ALTER TABLE chunk ADD COLUMN search_vector tsvector
    GENERATED ALWAYS AS (to_tsvector('english', strip_markdown(content))) STORED;

CREATE INDEX chunk_search_vector_idx ON chunk USING GIN (search_vector);

-- Repair document.tags and chunk.tag_paths that were stored as tag UUIDs
-- instead of ltree paths. This happened because an earlier version of the
-- upload form submitted tag.id as the value instead of tag.path.
--
-- For each array element that matches a UUID pattern, replace it with the
-- corresponding tag.path. Elements that are already valid ltree paths are
-- left unchanged (the LEFT JOIN finds no match and COALESCE keeps the original).

UPDATE document d
SET tags = ARRAY(
    SELECT COALESCE(t.path, tv::ltree)
    FROM unnest(d.tags) AS tv
    LEFT JOIN tag t
        ON t.id::text = tv::text
       AND tv::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
)
WHERE EXISTS (
    SELECT 1 FROM unnest(d.tags) AS tv
    WHERE tv::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
);

UPDATE chunk c
SET tag_paths = ARRAY(
    SELECT COALESCE(t.path, tv::ltree)
    FROM unnest(c.tag_paths) AS tv
    LEFT JOIN tag t
        ON t.id::text = tv::text
       AND tv::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
)
WHERE EXISTS (
    SELECT 1 FROM unnest(c.tag_paths) AS tv
    WHERE tv::text ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
);

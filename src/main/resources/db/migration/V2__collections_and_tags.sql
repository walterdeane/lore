CREATE TABLE lore_collection (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    description TEXT NOT NULL
);

CREATE TABLE tag (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lore_collection_id UUID NOT NULL REFERENCES lore_collection(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    path TEXT NOT NULL,
    UNIQUE (lore_collection_id, path)
);

CREATE TABLE lore_domain (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name TEXT NOT NULL,
    description TEXT NOT NULL
);

CREATE TABLE tag (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    lore_domain_id UUID NOT NULL REFERENCES lore_domain(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    description TEXT NOT NULL,
    path LTREE NOT NULL,
    UNIQUE (lore_domain_id, path)
);

CREATE INDEX tag_path_gist_idx ON tag USING GIST (path);

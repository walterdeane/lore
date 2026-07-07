package com.walterdeane.lore.search

/**
 * For each selected tag, match chunks whose tag_paths contain that tag or any descendant.
 * tp <@ ANY(...) is true when tp is a descendant of (or equal to) any element in the array,
 * so cookbook.cuisine matches cookbook.cuisine.american but not cookbook or cookbook.american.
 * Multiple selected tags are OR: a chunk matches if any tag_path falls under any selected tag.
 */
internal fun tagFilterClause(tags: List<String>?): String =
    if (!tags.isNullOrEmpty())
        "AND EXISTS (SELECT 1 FROM unnest(c.tag_paths) AS tp WHERE tp <@ ANY(ARRAY[${tags.joinToString(",") { "?" }}]::ltree[]))"
    else ""

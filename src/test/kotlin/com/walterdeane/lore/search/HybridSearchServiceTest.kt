package com.walterdeane.lore.search

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HybridSearchServiceTest {

    @Test
    fun `chunk present in both legs outranks one present in only one`() {
        val inBoth = UUID.randomUUID()
        val bm25Only = UUID.randomUUID()
        val vectorOnly = UUID.randomUUID()

        val bm25 = listOf(inBoth, bm25Only)
        val vector = listOf(inBoth, vectorOnly)

        val fused = fuse(bm25, vector)

        assertEquals(inBoth, fused.first().first)
        assertTrue(fused.map { it.first }.containsAll(listOf(bm25Only, vectorOnly)))
    }

    @Test
    fun `matches manual RRF formula sum of 1 over k plus rank`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val k = 60

        val fused = fuse(bm25 = listOf(a, b), vector = listOf(b), k = k)
        val scores = fused.toMap()

        assertEquals(1.0 / (k + 1), scores[a])
        assertEquals(1.0 / (k + 2) + 1.0 / (k + 1), scores[b])
    }

    @Test
    fun `handles empty legs`() {
        assertEquals(emptyList(), fuse(emptyList(), emptyList()))

        val onlyId = UUID.randomUUID()
        val fused = fuse(listOf(onlyId), emptyList())
        assertEquals(listOf(onlyId), fused.map { it.first })
    }
}

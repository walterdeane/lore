package com.walterdeane.lore.search

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RerankerServiceTest {

    @Test
    fun `parses a clean JSON array in the order given`() {
        assertEquals(listOf(2, 0, 1), parseRerankOrder("[2,0,1]", candidateCount = 3))
    }

    @Test
    fun `tolerates surrounding prose since models don't always follow the format instruction exactly`() {
        val response = "Sure! Here is the ranking: [2, 0, 1]. Let me know if you need anything else."
        assertEquals(listOf(2, 0, 1), parseRerankOrder(response, candidateCount = 3))
    }

    @Test
    fun `de-duplicates repeated indices, keeping the first occurrence`() {
        assertEquals(listOf(1, 0, 2), parseRerankOrder("[1, 0, 1, 2, 0]", candidateCount = 3))
    }

    @Test
    fun `filters out indices outside the valid candidate range`() {
        // candidateCount = 3 means only 0, 1, 2 are valid — -1 isn't parsed as negative (no sign
        // handling), but 3, 4, 99 etc. are out of range and should be dropped.
        assertEquals(listOf(1, 0), parseRerankOrder("[1, 0, 3, 99]", candidateCount = 3))
    }

    @Test
    fun `returns an empty list when the response contains no numbers`() {
        assertEquals(emptyList(), parseRerankOrder("I cannot rank these passages.", candidateCount = 3))
    }

    @Test
    fun `returns an empty list for a blank response`() {
        assertEquals(emptyList(), parseRerankOrder("", candidateCount = 3))
    }
}
package dev.xmbreborn.xmb

import org.junit.Assert.assertEquals
import org.junit.Test

class RecencyTest {
    private fun items(vararg ids: String) = ids.map { XmbItem(it, it) }

    @Test fun mostRecentFirstThenOriginalOrder() {
        val sorted = XmbSources.byRecency(items("a", "b", "c", "d", "e"), mapOf("d" to 300L, "b" to 100L, "e" to 200L))
        assertEquals(listOf("d", "e", "b", "a", "c"), sorted.map { it.id })
    }

    @Test fun nothingOpenedKeepsOrder() {
        assertEquals(listOf("a", "b", "c"), XmbSources.byRecency(items("a", "b", "c"), emptyMap()).map { it.id })
    }
}

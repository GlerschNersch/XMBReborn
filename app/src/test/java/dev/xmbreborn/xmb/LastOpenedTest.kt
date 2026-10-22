package dev.xmbreborn.xmb

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

class LastOpenedTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private fun at(y: Int, m: Int, d: Int, h: Int = 12) = LocalDateTime.of(y, m, d, h, 0).atZone(zone).toInstant().toEpochMilli()
    private fun said(then: Long, now: Long) = XmbSources.lastOpened(then, now, zone, Locale.US)

    @Test fun recentDaysByName() {
        val now = at(2026, 10, 21, 9)
        assertEquals("Opened today", said(at(2026, 10, 21, 0), now))
        assertEquals("Opened yesterday", said(at(2026, 10, 20, 23), now))
        assertEquals("Opened Oct 18", said(at(2026, 10, 18), now))
    }

    @Test fun olderYearsSaySo() {
        assertEquals("Opened yesterday", said(at(2025, 12, 31), at(2026, 1, 1)))
        assertEquals("Opened Dec 30, 2025", said(at(2025, 12, 30), at(2026, 1, 1)))
    }
}

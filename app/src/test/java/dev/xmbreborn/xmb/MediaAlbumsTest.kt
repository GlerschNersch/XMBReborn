package dev.xmbreborn.xmb

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaAlbumsTest {
    @Test fun clipLengths() {
        assertEquals("0:07", MediaAlbums.duration(7_400))
        assertEquals("1:05", MediaAlbums.duration(65_000))
        assertEquals("1:02:05", MediaAlbums.duration(3_725_000))
    }
}

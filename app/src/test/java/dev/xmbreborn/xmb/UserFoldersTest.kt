package dev.xmbreborn.xmb

import org.junit.Assert.assertEquals
import org.junit.Test

class UserFoldersTest {
    private fun items(vararg ids: String) = ids.map { XmbItem(it, it) }
    private fun gather(items: List<XmbItem>, vararg folders: UserFolders.Folder) =
        UserFolders.apply(items, folders.toList()) { f, members -> XmbItem(f.id, f.name, children = members) }

    @Test fun folderTakesItsFirstAppsPlace() {
        val out = gather(items("a", "b", "c", "d"), UserFolders.Folder("f", "Stream", listOf("c", "b")))
        assertEquals(listOf("a", "f", "d"), out.map { it.id })
        // Members keep the column's order (recency or A-Z), not the order they were added.
        assertEquals(listOf("b", "c"), out[1].children!!.map { it.id })
    }

    @Test fun uninstalledAppsAreSkippedAndEmptyFoldersHidden() {
        val out = gather(items("a", "b"), UserFolders.Folder("f", "Gone", listOf("x")), UserFolders.Folder("g", "Half", listOf("b", "y")))
        assertEquals(listOf("a", "g"), out.map { it.id })
        assertEquals(listOf("b"), out[1].children!!.map { it.id })
    }

    @Test fun anAppIsInOneFolderOnly() {
        val out = gather(items("a", "b"), UserFolders.Folder("f", "One", listOf("a")), UserFolders.Folder("g", "Two", listOf("a", "b")))
        assertEquals(listOf("f", "g"), out.map { it.id })
        assertEquals(listOf("a"), out[0].children!!.map { it.id })
        assertEquals(listOf("b"), out[1].children!!.map { it.id })
    }
}

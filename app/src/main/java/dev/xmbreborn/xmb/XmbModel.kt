package dev.xmbreborn.xmb

/**
 * One row in a category's vertical list. [payload] is whatever the source needs to open it.
 * A row with [children] is a folder: opening it shows them as a list of their own, the way the
 * PS3's Settings opens into Theme Settings, Sound Settings and so on.
 */
class XmbItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val payload: Any? = null,
    val children: List<XmbItem>? = null,
)

/**
 * A column on the bar. Each category remembers its own focused row, like the PS3, and the
 * folders open in it: [items] and [focus] are always the list showing now, so code that only
 * cares about what's on screen never needs to know about folders.
 */
class XmbCategory(
    val id: String,
    val title: String,
    val glyph: Glyph,
    items: List<XmbItem> = emptyList(),
) {
    /** One list in the folder path: the column's own rows first, then each open folder's. */
    private class Level(val items: List<XmbItem>, var focus: Int = 0)

    // Changed only on the GL thread, but read from the UI thread too (accessibility), so the
    // path is replaced whole rather than edited in place.
    @Volatile private var levels: List<Level> = listOf(Level(items))

    val items: List<XmbItem> get() = levels.last().items
    val focus: Int get() = levels.last().focus

    /** How many folders are open (0 = the column's own list). */
    val depth: Int get() = levels.size - 1

    /** The open folders, outermost first: the breadcrumb icons left of the list. */
    val crumbs: List<XmbItem> get() = levels.dropLast(1).map { it.items[it.focus] }

    /**
     * Replaces the column's own rows, keeping focus on the same item ids where they still exist
     * and keeping open whichever folders still exist (by id); a folder that's gone closes.
     */
    fun replaceItems(newItems: List<XmbItem>) {
        levels = follow(newItems, path())
    }

    /** Takes on [old]'s focus and open folders, matched by id (a reload built this column anew). */
    fun keepPathOf(old: XmbCategory) {
        levels = follow(levels.first().items, old.path())
    }

    private fun path() = levels.map { it.items.getOrNull(it.focus)?.id to it.focus }

    private fun follow(newItems: List<XmbItem>, path: List<Pair<String?, Int>>): List<Level> {
        val rebuilt = ArrayList<Level>()
        var list = newItems
        for ((i, step) in path.withIndex()) {
            val (id, oldFocus) = step
            val found = list.indexOfFirst { it.id == id }
            rebuilt.add(Level(list, if (found >= 0) found else oldFocus.coerceIn(0, (list.size - 1).coerceAtLeast(0))))
            if (i == path.lastIndex) break
            // Carry on into the next open folder only if it's still here and still a folder.
            list = list.getOrNull(found)?.children?.takeIf { it.isNotEmpty() } ?: break
        }
        return rebuilt
    }

    fun moveFocus(delta: Int): Boolean {
        if (items.isEmpty()) return false
        val next = (focus + delta).coerceIn(0, items.size - 1)
        if (next == focus) return false
        levels.last().focus = next
        return true
    }

    fun setFocus(index: Int): Boolean {
        if (index !in items.indices || index == focus) return false
        levels.last().focus = index
        return true
    }

    val focusedItem: XmbItem? get() = items.getOrNull(focus)

    /** Opens the focused row if it's a folder with something in it. */
    fun open(): Boolean {
        val children = focusedItem?.children?.takeIf { it.isNotEmpty() } ?: return false
        levels = levels + Level(children)
        return true
    }

    /** Back out of the innermost folder; false if none is open. */
    fun close(): Boolean {
        if (depth == 0) return false
        levels = levels.dropLast(1)
        return true
    }

    /** Back out to the column's own list, e.g. on Home, or to [toDepth] folders deep. */
    fun closeTo(toDepth: Int = 0): Boolean {
        if (toDepth !in 0 until depth) return false
        levels = levels.take(toDepth + 1)
        return true
    }

    /** Each starting letter in list order, with the first row under it: "#" for digits and symbols. */
    fun letters(): List<Pair<String, Int>> {
        val seen = LinkedHashMap<String, Int>()
        items.forEachIndexed { i, item -> seen.getOrPut(letterOf(item.title)) { i } }
        return seen.map { (letter, index) -> letter to index }
    }

    companion object {
        fun letterOf(title: String): String {
            val c = title.trimStart().firstOrNull()?.uppercaseChar() ?: return "#"
            return if (c.isLetter()) c.toString() else "#"
        }
    }
}

/** The whole bar: which column is focused. No wrap-around at either end, as on the PS3. */
class XmbModel(val categories: List<XmbCategory>, initial: Int = 0) {
    var focus: Int = initial.coerceIn(0, (categories.size - 1).coerceAtLeast(0))
        private set

    val focused: XmbCategory get() = categories[focus]

    fun moveCategory(delta: Int): Boolean {
        val next = (focus + delta).coerceIn(0, categories.size - 1)
        if (next == focus) return false
        focus = next
        return true
    }

    fun setCategory(index: Int): Boolean {
        if (index !in categories.indices || index == focus) return false
        focus = index
        return true
    }

    fun category(id: String): XmbCategory? = categories.firstOrNull { it.id == id }
}

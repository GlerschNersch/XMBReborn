package dev.xmbreborn.xmb

/** One row in a category's vertical list. [payload] is whatever the source needs to open it. */
class XmbItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val payload: Any? = null,
)

/** A column on the horizontal bar. Each category remembers its own focused row, like the PS3. */
class XmbCategory(
    val id: String,
    val title: String,
    val glyph: Glyph,
    items: List<XmbItem> = emptyList(),
) {
    var items: List<XmbItem> = items
        private set
    var focus: Int = 0
        private set

    /** Replaces the rows, keeping focus on the same item id when it still exists. */
    fun replaceItems(newItems: List<XmbItem>) {
        val keep = items.getOrNull(focus)?.id
        items = newItems
        focus = newItems.indexOfFirst { it.id == keep }.takeIf { it >= 0 }
            ?: focus.coerceIn(0, (newItems.size - 1).coerceAtLeast(0))
    }

    fun moveFocus(delta: Int): Boolean {
        if (items.isEmpty()) return false
        val next = (focus + delta).coerceIn(0, items.size - 1)
        if (next == focus) return false
        focus = next
        return true
    }

    fun setFocus(index: Int): Boolean {
        if (index !in items.indices || index == focus) return false
        focus = index
        return true
    }

    val focusedItem: XmbItem? get() = items.getOrNull(focus)

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

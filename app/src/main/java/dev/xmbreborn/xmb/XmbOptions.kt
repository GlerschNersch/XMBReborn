package dev.xmbreborn.xmb

/** One row of the options panel. [onFocus] runs on the GL thread (for previews), [onSelect] on the UI thread. */
class OptionEntry(
    val label: String,
    val onFocus: (() -> Unit)? = null,
    val onSelect: () -> Unit,
)

/**
 * The Triangle / long-press options panel: a short list with one focused row. Pure state; the
 * renderer animates it and the view routes input to it while it's showing.
 */
class XmbOptions(
    val entries: List<OptionEntry>,
    initial: Int = 0,
    /** Runs on the GL thread when the panel closes without a choice (e.g. to undo a preview). */
    val onCancel: (() -> Unit)? = null,
) {
    var focus = initial.coerceIn(0, (entries.size - 1).coerceAtLeast(0))
        private set

    /** Moves focus without wrapping, like the bar. Returns false at either end. */
    fun move(delta: Int): Boolean = setFocus(focus + delta)

    fun setFocus(index: Int): Boolean {
        if (index !in entries.indices || index == focus) return false
        focus = index
        return true
    }

    val focused: OptionEntry? get() = entries.getOrNull(focus)
}

package dev.xmbreborn.render

/** What's drawn behind the bar (Theme Settings > Background). */
enum class Backdrop(val label: String) {
    /** The PS3's own month gradient, wave and sparkles. */
    WAVE("Original"),
    /** A slow flight down a misty canyon, after the firmware's canyon scene. */
    CANYON("Canyon"),
    /** The Earth from orbit, after the firmware's earth scene. */
    EARTH("Earth");

    companion object {
        fun of(index: Int) = entries.getOrElse(index) { WAVE }
    }
}

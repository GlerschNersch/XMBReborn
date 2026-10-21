package dev.xmbreborn.xmb

/**
 * Background colour per month, measured from the PS3's month gradients: four corners plus the
 * centre (0xRRGGBB), for day and for night. Night versions fade toward black at the bottom.
 */
object MonthPalette {
    /** [month 0..11][tl, tr, bl, br, centre] */
    private val DAY = arrayOf(
        intArrayOf(0xe8e8e8, 0xd1d1d1, 0xdddddd, 0xb5b7b8, 0xbababa),
        intArrayOf(0xd0c123, 0xd5c62d, 0xd5e12f, 0xd6dd35, 0xcda30e),
        intArrayOf(0x81be29, 0xa6b924, 0x80aa1f, 0x599f18, 0x6ca919),
        intArrayOf(0xe07992, 0xdb9eab, 0xe7497a, 0xdc7585, 0xdf7e98),
        intArrayOf(0x136c13, 0x189a18, 0x137213, 0x179617, 0x168516),
        intArrayOf(0x8d539c, 0xc680da, 0x6955a2, 0x845dd9, 0x9b65d2),
        intArrayOf(0x00aa8f, 0x02ddce, 0x00babd, 0x10e7e2, 0x02cdc3),
        intArrayOf(0x00054e, 0x0450af, 0x3d9dd3, 0x06b1de, 0x0a68bb),
        intArrayOf(0xca5cdd, 0x91369c, 0xcf5fe3, 0xae41c4, 0xb242bd),
        intArrayOf(0xe2b10f, 0xe59805, 0xd8c500, 0xe1b311, 0xe1a308),
        intArrayOf(0x774a18, 0x774a18, 0x98742e, 0x98742e, 0x865c20),
        intArrayOf(0xd7412d, 0xe6432d, 0xd13d2a, 0xe5432d, 0xe3412c),
    )
    private val NIGHT = arrayOf(
        intArrayOf(0xb7b7b7, 0xb3b3b3, 0x161616, 0x161616, 0x525252),
        intArrayOf(0xb7b072, 0xcdc899, 0x16160e, 0x16160e, 0x555032),
        intArrayOf(0x92a56b, 0x91a56a, 0x11130d, 0x12130c, 0x464d33),
        intArrayOf(0xcaa5ac, 0xc9a7ae, 0x100f0f, 0x100f0f, 0x6c575b),
        intArrayOf(0x234d23, 0x338633, 0x111c11, 0x122212, 0x1a2f1a),
        intArrayOf(0xca9bd6, 0xd2a3de, 0x09090a, 0x09090a, 0x594966),
        intArrayOf(0x0c4c48, 0x148d8a, 0x111313, 0x101212, 0x123635),
        intArrayOf(0x008b9e, 0x457c95, 0x00255b, 0x000021, 0x003361),
        intArrayOf(0x6b0090, 0x3f005b, 0x35004c, 0x190020, 0x3d004a),
        intArrayOf(0xd89200, 0xd98b00, 0x050000, 0x050000, 0x573500),
        intArrayOf(0x7f5525, 0x7f5525, 0x111110, 0x111110, 0x4b3618),
        intArrayOf(0x40211c, 0xbd3f2c, 0x0e0d0d, 0x121010, 0x2d1815),
    )

    /**
     * 0 = full day, 1 = full night, for an hour of day (0..24, fractional). Day 7:00-17:00,
     * night 20:00-5:00, with a dawn ramp 5-7 and a dusk ramp 17-20.
     */
    fun nightness(hour: Float): Float = when {
        hour < 5f -> 1f
        hour < 7f -> 1f - (hour - 5f) / 2f
        hour < 17f -> 0f
        hour < 20f -> (hour - 17f) / 3f
        else -> 1f
    }

    /** Five colours (tl, tr, bl, br, centre) as linear-ish RGB floats, 15 values. */
    fun colors(month: Int, hour: Float, out: FloatArray = FloatArray(15)): FloatArray {
        val m = month.coerceIn(0, 11)
        val n = nightness(hour)
        for (i in 0 until 5) {
            val d = DAY[m][i]
            val k = NIGHT[m][i]
            for (c in 0 until 3) {
                val shift = 16 - 8 * c
                val dv = (d shr shift and 0xff) / 255f
                val nv = (k shr shift and 0xff) / 255f
                out[i * 3 + c] = dv + (nv - dv) * n
            }
        }
        return out
    }
}

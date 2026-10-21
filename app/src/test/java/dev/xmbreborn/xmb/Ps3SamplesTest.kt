package dev.xmbreborn.xmb

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Reads a real PS3 games folder, if one is named in XMB_PS3_SAMPLES (skipped otherwise), and
 * writes what Ps3Metadata finds to build/ps3-samples.txt. A smoke test over real dumps; nothing
 * of the games is kept.
 */
class Ps3SamplesTest {
    @Test fun readsRealGames() {
        val dir = System.getenv("XMB_PS3_SAMPLES")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val report = StringBuilder()
        for (f in dir!!.listFiles()!!.sortedBy { it.name.lowercase() }) {
            if (f.isDirectory && !f.name.endsWith(".ps3")) {
                val sfo = File(f, "PS3_GAME/PARAM.SFO").takeIf { it.isFile }?.readBytes()
                val p = sfo?.let(Ps3Metadata::parseSfo)
                report.append("DIR  ${f.name} -> ${p?.get("TITLE_ID")} | ${p?.get("TITLE")?.toString()?.replace("\n", " / ")} | " +
                    "icon=${File(f, "PS3_GAME/ICON0.PNG").length()} pic1=${File(f, "PS3_GAME/PIC1.PNG").length()}\n")
            } else if (f.name.endsWith(".iso", ignoreCase = true)) {
                val t0 = System.nanoTime()
                RandomAccessFile(f, "r").use { raf ->
                    val src = Ps3Metadata.Source { off, len ->
                        val n = minOf(len.toLong(), (raf.length() - off).coerceAtLeast(0)).toInt()
                        ByteArray(n).also { raf.seek(off); raf.readFully(it) }
                    }
                    val g = Ps3Metadata.isoGame(src)
                    val p = g?.sfo?.let(Ps3Metadata::parseSfo)
                    val icon = g?.icon
                    val pic = g?.pic1
                    val ms = (System.nanoTime() - t0) / 1_000_000
                    report.append("ISO  ${f.name} -> ${p?.get("TITLE_ID")} | ${p?.get("TITLE")?.toString()?.replace("\n", " / ")} | " +
                        "icon=${icon?.size} pic1=${pic?.size} nameId=${Ps3Metadata.titleIdInName(f.name)} ${ms}ms\n")
                }
            }
        }
        File("build/ps3-samples.txt").writeText(report.toString())
    }
}

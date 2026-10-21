package dev.xmbreborn.xmb

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.DocumentsContract
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * PS3 games, played in ARMSX3 (github.com/ARMSX2/ARMSX3, an Android port of RPCS3). The user
 * picks a folder; we list what's in it:
 *  - game folders (`<Game>/PS3_GAME/PARAM.SFO`, ICON0.PNG, PIC1.PNG) and disc images (`.iso`),
 *    with the game's own title, title id, icon and background, like a real PS3;
 *  - ARMSX3's Frontend Export files (`<Game>.ps3`, holding the title id).
 * A game opens by handing ARMSX3 its title id, the way ES-DE does, or failing that its file.
 * ARMSX3 is a separate app; nothing of it is built in.
 */
object Ps3Library {
    /** Where a game's PIC1.PNG background is, to load when it's focused (they're large). */
    sealed interface Pic1 {
        data class Doc(val uri: Uri) : Pic1
        data class InIso(val iso: Uri) : Pic1
    }

    data class Game(
        val title: String,
        val titleId: String?,
        val icon: ByteArray? = null,
        val pic1: Pic1? = null,
        /** A file ARMSX3 can boot (a disc image), for games without a usable title id. */
        val file: Uri? = null,
    )

    private val titleIdShape = Regex("^[A-Z]{4}[0-9]{5}$")
    private val tagPrefix = Regex("^\\[title_id]", RegexOption.IGNORE_CASE)
    // What ARMSX3 or the folder's provider may add to a name: " [BLUS12345]" when the plain name
    // was taken, " (1)" when a provider de-duplicates.
    private val nameSuffix = Regex("""(\s*\[[A-Za-z]{4}-?[0-9]{5}]|\s*\(\d+\))+$""")

    /** The title id in a shortcut's [content], in either form ARMSX3 writes, or null. */
    fun titleIdIn(content: String): String? {
        val firstLine = content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        val bare = firstLine.replace(tagPrefix, "").trim().replace("-", "").uppercase()
        return bare.takeIf { titleIdShape.matches(it) }
    }

    /** A game from one exported file, or null if it isn't a `.ps3` shortcut holding a title id. */
    fun parse(fileName: String, content: String): Game? {
        if (!fileName.endsWith(EXTENSION, ignoreCase = true)) return null
        val id = titleIdIn(content) ?: return null
        val title = fileName.dropLast(EXTENSION.length).replace(nameSuffix, "").trim()
        return Game(title.ifEmpty { id }, id)
    }

    /** The game's name as PARAM.SFO gives it, on one line ("Uncharted 3 ... Game of the Year"). */
    fun titleOf(sfo: Map<String, Any>): String? =
        (sfo["TITLE"] as? String)?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(" ")?.ifEmpty { null }

    /** A disc image's title and id: from its PARAM.SFO when there is one, else from its file name. */
    fun discIdentity(fileName: String, sfo: Map<String, Any>?): Pair<String, String?> {
        val plain = fileName.substringBeforeLast('.').replace(nameSuffix, "").trim()
        val title = sfo?.let(::titleOf) ?: plain.ifEmpty { fileName }
        val id = (sfo?.get("TITLE_ID") as? String)?.takeIf { titleIdShape.matches(it) } ?: Ps3Metadata.titleIdInName(fileName)
        return title to id
    }

    private class Child(val id: String, val name: String, val isDir: Boolean, val size: Long)

    private fun children(context: Context, tree: Uri, docId: String): List<Child> {
        val out = ArrayList<Child>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        context.contentResolver.query(uri, arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        ), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                out.add(Child(c.getString(0), name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, c.getLong(3)))
            }
        }
        return out
    }

    private fun readAll(context: Context, uri: Uri, max: Long): ByteArray? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }?.takeIf { it.size <= max }
    }.getOrNull()

    /** Every game in the user's folder (a tree URI they granted us), A-Z, one per title id. */
    fun scan(context: Context, tree: Uri): List<Game> {
        val found = ArrayList<Game>()
        for (c in children(context, tree, DocumentsContract.getTreeDocumentId(tree))) {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, c.id)
            val game = runCatching {
                when {
                    c.isDir -> gameFolder(context, tree, c)
                    c.name.endsWith(".iso", ignoreCase = true) -> discImage(context, doc, c.name)
                    c.name.endsWith(EXTENSION, ignoreCase = true) && c.size <= MAX_FILE ->
                        readAll(context, doc, MAX_FILE)?.let { parse(c.name, String(it, Charsets.UTF_8)) }
                    else -> null
                }
            }.getOrNull()
            game?.let(found::add)
        }
        // The same game can be both a folder and an export file: keep the one that knows most.
        val byId = found.filter { it.titleId != null }.groupBy { it.titleId }
            .map { (_, same) -> same.maxBy { (if (it.icon != null) 2 else 0) + (if (it.pic1 != null) 1 else 0) } }
        return (byId + found.filter { it.titleId == null }).sortedBy { it.title.lowercase() }
    }

    /** A game folder holding PS3_GAME/PARAM.SFO; empty or unrelated folders give null. */
    private fun gameFolder(context: Context, tree: Uri, dir: Child): Game? {
        val ps3Game = children(context, tree, dir.id).firstOrNull { it.isDir && it.name.equals("PS3_GAME", true) } ?: return null
        val files = children(context, tree, ps3Game.id).filter { !it.isDir }.associateBy { it.name.uppercase() }
        fun doc(name: String) = files[name]?.let { DocumentsContract.buildDocumentUriUsingTree(tree, it.id) }
        val sfo = doc("PARAM.SFO")?.let { readAll(context, it, MAX_SFO) }?.let(Ps3Metadata::parseSfo) ?: return null
        return Game(
            title = titleOf(sfo) ?: dir.name,
            titleId = sfo["TITLE_ID"] as? String,
            icon = doc("ICON0.PNG")?.let { readAll(context, it, MAX_ICON) },
            pic1 = doc("PIC1.PNG")?.let { Pic1.Doc(it) },
        )
    }

    /** A disc image: its PARAM.SFO and art if it's ISO 9660, else just its file name. */
    private fun discImage(context: Context, uri: Uri, name: String): Game {
        val game = runCatching { isoGame(context, uri) }.getOrNull()
        val (title, id) = discIdentity(name, game?.sfo?.let(Ps3Metadata::parseSfo))
        return Game(title, id, icon = game?.icon, pic1 = game?.pic1?.let { Pic1.InIso(uri) }, file = uri)
    }

    private fun isoGame(context: Context, uri: Uri): Ps3Metadata.IsoGame? =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                Ps3Metadata.isoGame({ offset, length ->
                    val buf = ByteBuffer.allocate(length)
                    var pos = offset
                    while (buf.hasRemaining()) {
                        val n = ch.read(buf, pos)
                        if (n <= 0) break
                        pos += n
                    }
                    buf.array().copyOf(buf.position())
                })
            }
        }

    /** A focused game's PIC1 background, or null. Large (a few MB), so loaded only on demand. */
    fun loadPic1(context: Context, pic: Pic1): ByteArray? = runCatching {
        when (pic) {
            is Pic1.Doc -> readAll(context, pic.uri, MAX_PIC1)
            is Pic1.InIso -> isoGame(context, pic.iso)?.pic1
        }
    }.getOrNull()

    /** The installed ARMSX3 (GitHub or Play build), or null. */
    fun emulator(pm: PackageManager): String? = PACKAGES.firstOrNull { pkg ->
        runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
    }

    /**
     * Starts a game in ARMSX3: by [titleId] when known (ARMSX3 resolves it against its own
     * library, "Launch by title id, for external frontends"), else by handing it the [file].
     */
    fun launch(context: Context, pkg: String, titleId: String?, file: Uri?) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setComponent(ComponentName(pkg, ENTRY_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        when {
            titleId != null -> intent.putExtra("title_id", titleId)
            // E.g. a UDF-only disc image: ARMSX3 boots the file itself, read through our access.
            file != null -> intent.setData(file).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            else -> return
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // A build that moved its entry point: open the emulator at least.
            context.packageManager.getLaunchIntentForPackage(pkg)?.let {
                context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } ?: throw e
        }
    }

    const val EXTENSION = ".ps3"
    val PACKAGES = listOf("com.armsx3", "com.armsx3.play")
    /** ARMSX3's exported main activity (an alias of its Main), which handles external launches. */
    private const val ENTRY_ACTIVITY = "com.armsx2.MainActivity"
    private const val MAX_FILE = 4096L
    private const val MAX_SFO = 64L shl 10
    private const val MAX_ICON = 2L shl 20
    private const val MAX_PIC1 = 16L shl 20
}

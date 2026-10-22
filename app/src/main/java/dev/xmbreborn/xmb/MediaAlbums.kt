package dev.xmbreborn.xmb

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/**
 * The phone's photo and video albums, newest first, for the Photo and Video columns: the PS3
 * lists your pictures and clips there by folder, as well as the apps that show them.
 */
object MediaAlbums {
    class Media(val uri: Uri, val id: Long, val name: String, val modified: Long, val durationMs: Long)
    class Album(val id: String, val name: String, val items: List<Media>, val total: Int)

    /**
     * Up to [maxAlbums] albums by most recent change, each with its newest [perAlbum] items and
     * how many it holds in all. Reads at most [scanLimit] rows, newest first, so a huge library
     * stays quick; albums older than that simply don't show.
     */
    fun load(resolver: ContentResolver, video: Boolean, maxAlbums: Int = 8, perAlbum: Int = 30, scanLimit: Int = 4000): List<Album> {
        val base = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.BUCKET_ID)
            add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DATE_MODIFIED)
            if (video) add(MediaStore.Video.VideoColumns.DURATION)
        }.toTypedArray()
        class Building(val name: String) { val items = ArrayList<Media>(); var total = 0 }
        val albums = LinkedHashMap<String, Building>()
        runCatching {
            resolver.query(base, projection, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
                val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val modCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val durCol = if (video) c.getColumnIndex(MediaStore.Video.VideoColumns.DURATION) else -1
                var rows = 0
                while (c.moveToNext() && rows++ < scanLimit) {
                    val bucket = c.getString(bucketCol) ?: continue
                    val album = albums[bucket] ?: if (albums.size < maxAlbums) {
                        Building(c.getString(bucketNameCol) ?: "Other").also { albums[bucket] = it }
                    } else continue
                    album.total++
                    if (album.items.size < perAlbum) {
                        val id = c.getLong(idCol)
                        album.items += Media(ContentUris.withAppendedId(base, id), id, c.getString(nameCol) ?: "",
                            c.getLong(modCol), if (durCol >= 0) c.getLong(durCol) else 0L)
                    }
                }
            }
        }
        return albums.map { (bucket, b) -> Album("${if (video) "v" else "p"}:$bucket", b.name, b.items, b.total) }
    }

    /** "1:05" or "1:02:05" for a clip's length. */
    fun duration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }
}

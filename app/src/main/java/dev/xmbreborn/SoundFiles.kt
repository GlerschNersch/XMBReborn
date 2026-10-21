package dev.xmbreborn

import android.content.Context
import java.io.File

/**
 * Where the launcher looks for sound files the user supplies (their own PS3 sounds; the app
 * ships none). The external folder, Android/data/dev.xmbreborn/files/sounds, takes a plain
 * `adb push` with any build; the private one needs `run-as`, so only works with debug builds.
 */
object SoundFiles {
    fun dirs(context: Context): List<File> =
        listOfNotNull(context.getExternalFilesDir("sounds"), File(context.filesDir, "sounds"))

    /** The first [name] found, or null. */
    fun find(context: Context, name: String): File? =
        dirs(context).map { File(it, name) }.firstOrNull { it.isFile }
}

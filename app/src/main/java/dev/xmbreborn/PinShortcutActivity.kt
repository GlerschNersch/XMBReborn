package dev.xmbreborn

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.LauncherApps
import android.os.Bundle
import android.widget.Toast

/**
 * Answers "Add to Home screen" from other apps (Chrome, Maps, ...): Android asks the default home
 * app to confirm a pinned shortcut. Accepted ones appear in the Shortcuts column.
 */
class PinShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apps = getSystemService(LauncherApps::class.java)
        val request = runCatching { apps.getPinItemRequest(intent) }.getOrNull()
        val info = request?.shortcutInfo
        if (request == null || request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT || info == null) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Add to Shortcuts?")
            .setMessage(info.shortLabel ?: info.id)
            .setPositiveButton("Add") { _, _ ->
                if (request.isValid && runCatching { request.accept() }.getOrDefault(false)) {
                    Toast.makeText(this, "Added to Shortcuts", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}

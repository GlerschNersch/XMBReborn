package dev.xmbreborn

import android.content.ComponentName
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.UserHandle

/**
 * App shortcuts (an app's own actions, like "New tab") and shortcuts other apps pinned to home
 * ("Add to Home screen"). Android only lets the default home app read them, so everything here
 * comes back empty otherwise.
 */
object Shortcuts {
    /** How many of an app's shortcuts the options panel lists. */
    private const val PER_APP = 4

    /** An app's own shortcuts, most important first. */
    fun forApp(apps: LauncherApps, component: ComponentName, user: UserHandle): List<ShortcutInfo> =
        query(apps, user) {
            setPackage(component.packageName)
            setActivity(component)
            setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
        }.filter { it.isEnabled }.sortedWith(compareBy({ !it.isDeclaredInManifest }, { it.rank })).take(PER_APP)

    /** Everything pinned to this launcher, for every profile, in pinning order. */
    fun pinned(apps: LauncherApps): List<ShortcutInfo> = apps.listedProfiles.flatMap { user ->
        query(apps, user) { setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED) }
    }.filter { it.isPinned }

    /** Unpins one shortcut, keeping the app's others pinned. */
    fun unpin(apps: LauncherApps, info: ShortcutInfo) {
        val keep = query(apps, info.userHandle) {
            setPackage(info.`package`)
            setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
        }.map { it.id }.filter { it != info.id }
        runCatching { apps.pinShortcuts(info.`package`, keep, info.userHandle) }
    }

    /** One pinned shortcut by its package and id, or null if it's gone. */
    fun find(apps: LauncherApps, pkg: String, id: String, user: UserHandle): ShortcutInfo? = query(apps, user) {
        setPackage(pkg)
        setShortcutIds(listOf(id))
        setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
    }.firstOrNull()

    private inline fun query(apps: LauncherApps, user: UserHandle, build: LauncherApps.ShortcutQuery.() -> Unit): List<ShortcutInfo> {
        if (!runCatching { apps.hasShortcutHostPermission() }.getOrDefault(false)) return emptyList()
        return runCatching { apps.getShortcuts(LauncherApps.ShortcutQuery().apply(build), user) }.getOrNull().orEmpty()
    }
}

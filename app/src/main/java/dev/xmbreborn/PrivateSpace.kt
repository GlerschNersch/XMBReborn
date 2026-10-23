package dev.xmbreborn

import android.content.pm.LauncherApps
import android.os.Build
import android.os.UserHandle
import android.os.UserManager

/**
 * True for Android 15's Private Space. Its apps stay out of every list: the space has its own
 * lock, and the system launcher only shows them inside a separate container once unlocked.
 */
fun LauncherApps.isPrivateSpace(user: UserHandle): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
        runCatching { getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE }
            .getOrDefault(false)

/** The profiles whose apps the launcher lists: the user's own and work profiles, never Private Space. */
val LauncherApps.listedProfiles: List<UserHandle> get() = profiles.filterNot(::isPrivateSpace)

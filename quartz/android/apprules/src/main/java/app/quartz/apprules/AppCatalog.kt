package app.quartz.apprules

import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.os.UserHandle

/** An app that can be picked for a rule, with its icon (badged for other profiles). */
data class CatalogApp(val ref: AppRef, val icon: Drawable?)

/**
 * The apps a rule can pick (FORK.md §14), grouped by profile: the main profile's apps, and those
 * of every other profile Android lets Quartz see through LauncherApps (work profile, Secure
 * Folder, dual apps; the private space only while it's unlocked and visible to apps). Apps app
 * detection saw in a profile that isn't listed are added as [AppProfile.OTHER].
 */
object AppCatalog {

    fun load(context: Context, seen: List<AppRef>): Map<AppProfile, List<CatalogApp>> {
        val launcher = context.getSystemService(LauncherApps::class.java)
        val me = Process.myUserHandle()
        val profiles = runCatching { launcher?.profiles }.getOrNull().orEmpty().ifEmpty { listOf(me) }
        val result = linkedMapOf<AppProfile, MutableList<CatalogApp>>()
        for (user in profiles) {
            val profile = if (user == me) AppProfile.MAIN else profileOf(launcher, user)
            val apps =
                runCatching { launcher?.getActivityList(null, user) }.getOrNull().orEmpty()
                    .filter { it.applicationInfo.packageName != context.packageName || user != me }
                    .distinctBy { it.applicationInfo.packageName }
                    .map { activity ->
                        CatalogApp(
                            AppRef(
                                packageName = activity.applicationInfo.packageName,
                                label = activity.label?.toString() ?: activity.applicationInfo.packageName,
                                profile = profile,
                                user = user.hashCode(), // UserHandle's hash is its user ID
                            ),
                            runCatching { activity.getBadgedIcon(0) }.getOrNull(),
                        )
                    }
            result.getOrPut(profile) { mutableListOf() } += apps
        }
        val listed = result.values.flatten().map { it.ref.packageName }.toSet()
        val others = seen.filter { it.packageName !in listed }.map { CatalogApp(it, null) }
        if (others.isNotEmpty()) result.getOrPut(AppProfile.OTHER) { mutableListOf() } += others
        return result.mapValues { (_, apps) -> apps.sortedBy { it.ref.label.lowercase() } }
    }

    private const val USER_TYPE_PRIVATE = "android.os.usertype.profile.PRIVATE"

    /**
     * Which kind of profile [user] is: Android 15 says so for the private space and work profile;
     * Samsung's Secure Folder (user 150+) and Dual Messenger (user 95) are known by their IDs;
     * anything else is taken as a work profile.
     */
    private fun profileOf(launcher: LauncherApps?, user: UserHandle): AppProfile {
        if (Build.VERSION.SDK_INT >= 35) {
            val type = runCatching { launcher?.getLauncherUserInfo(user)?.userType }.getOrNull()
            if (type == USER_TYPE_PRIVATE) return AppProfile.PRIVATE
        }
        return when (user.hashCode()) {
            in 150..160 -> AppProfile.SECURE_FOLDER
            95 -> AppProfile.DUAL
            else -> AppProfile.WORK
        }
    }
}

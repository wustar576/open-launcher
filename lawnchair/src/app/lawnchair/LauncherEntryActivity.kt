/*
 *     This file is part of Open Launcher, a fork of Lawnchair Launcher.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package app.lawnchair

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import app.lawnchair.ui.preferences.PreferenceActivity
import app.lawnchair.util.isDefaultLauncher
import com.android.launcher3.Launcher
import com.android.launcher3.Utilities

/**
 * No-UI trampoline that owns the app's `MAIN`/`LAUNCHER` intent-filter - i.e. this is what the
 * system resolves when the user taps the "Open Launcher" app icon in a drawer/home screen
 * (including its own, see [LawnchairLauncher]). See `quickstep/AndroidManifest-launcher.xml`
 * where the `LAUNCHER` category was removed from the home activity, and `lawnchair/AndroidManifest.xml`
 * where this activity is declared instead.
 *
 * Re-opening the home screen from the app icon is only useful before Open Launcher has been set
 * as the default home app; once it is in use (default, or simply the one currently on screen),
 * tapping the icon again should behave like opening "the app" and land on Settings instead. The
 * actual decision is a pure function, [decideLauncherEntryTarget], so it can be unit tested
 * without an Android runtime.
 */
class LauncherEntryActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val target = decideLauncherEntryTarget(
            isDefaultHome = isDefaultHome(this),
            launchedFromSelf = isLaunchedFromSelf(this),
            homeIsResumed = isHomeResumed(),
        )
        when (target) {
            LauncherEntryTarget.SETTINGS -> startSettings()
            LauncherEntryTarget.HOME -> startHome()
        }

        suppressTransitionAnimation()
        finish()
    }

    private fun startSettings() {
        val intent = Intent(this, PreferenceActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun startHome() {
        // Same intent shape a tap on a normal launcher icon would resolve to (ACTION_MAIN +
        // CATEGORY_HOME), restricted to our own package so it always resolves to LawnchairLauncher
        // (the only CATEGORY_HOME activity we ship) without a disambiguation dialog, and with the
        // NEW_TASK flag so it reattaches to any already-running home task instead of duplicating it.
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_HOME)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /** Disables the enter/exit animation for the activity this trampoline started, and for its own dismissal. */
    private fun suppressTransitionAnimation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    companion object {

        /**
         * Whether Open Launcher currently holds the HOME role (is the default launcher).
         *
         * Uses `RoleManager.ROLE_HOME` on API 29+ (the platform's own source of truth for the
         * default home app since Android Q), falling back to resolving the `CATEGORY_HOME`
         * intent on older versions.
         */
        private fun isDefaultHome(context: Context): Boolean {
            if (Utilities.ATLEAST_Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                    return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
                }
            }
            return context.isDefaultLauncher()
        }

        /**
         * Whether [activity] was started from within Open Launcher's own process (e.g. a
         * shortcut on its own workspace/drawer), as opposed to another launcher or the system.
         *
         * Tries, in order: [Activity.getLaunchedFromPackage] (API 34+, the most direct signal),
         * then [Activity.getReferrer] (API 22+, works for plain `startActivity` launches), then
         * [Activity.getCallingPackage] (only ever non-null for `startActivityForResult` launches,
         * kept as a last-resort fallback).
         */
        private fun isLaunchedFromSelf(activity: Activity): Boolean {
            val ownPackage = activity.packageName
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val launchedFrom = runCatching { activity.launchedFromPackage }.getOrNull()
                if (launchedFrom != null) {
                    return launchedFrom == ownPackage
                }
            }
            val referrerPackage = runCatching { activity.referrer?.host }.getOrNull()
            if (referrerPackage != null) {
                return referrerPackage == ownPackage
            }
            return activity.callingPackage == ownPackage
        }

        /**
         * Whether Open Launcher's home activity is currently started (visible), regardless of
         * who launched this trampoline. Covers "not default, but already the one on screen",
         * e.g. the user is trying Open Launcher out before switching their default launcher, and
         * taps a workspace/drawer shortcut to it rather than pressing the home button.
         *
         * [Launcher.ACTIVITY_TRACKER] is the same in-process weak reference Launcher3 itself uses
         * to track its single live home instance (see [com.android.launcher3.util.ContextTracker]).
         */
        private fun isHomeResumed(): Boolean = Launcher.ACTIVITY_TRACKER.getCreatedContext<Launcher>()?.isStarted() == true
    }
}

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

/**
 * Where tapping the "Open Launcher" app icon should take the user.
 *
 * The app icon is handled by [LauncherEntryActivity], a no-UI trampoline that owns the
 * `MAIN`/`LAUNCHER` intent-filter (the home activity, [LawnchairLauncher], no longer does -
 * see `quickstep/AndroidManifest-launcher.xml`). Re-opening the home screen from the icon is
 * only useful the first time, before Open Launcher is set as the default home app; once it is
 * in use, tapping the icon again should feel like opening "the app", i.e. its Settings.
 */
enum class LauncherEntryTarget {
    SETTINGS,
    HOME,
}

/**
 * Pure decision function behind [LauncherEntryActivity]. Free of any Android dependency so it
 * can be unit tested on a plain JVM (see `lawnchair/tests/unit`).
 *
 * @param isDefaultHome Whether Open Launcher currently holds the HOME role (is the default
 *   launcher). See `RoleManager.ROLE_HOME` on API 29+, or the resolved `CATEGORY_HOME` activity
 *   on older versions.
 * @param launchedFromSelf Whether the tap that started [LauncherEntryActivity] was itself
 *   launched from within Open Launcher's own process (e.g. a shortcut tapped from its own
 *   drawer/workspace), as opposed to from another launcher or the system.
 * @param homeIsResumed Whether Open Launcher's home activity is currently started (visible),
 *   regardless of who launched the entry activity. Covers the "not default, but already the one
 *   in use" case, e.g. the user is trying it out before switching their default launcher.
 * @return [LauncherEntryTarget.SETTINGS] whenever Open Launcher is already in use (default home,
 *   or the tap originated from inside it), [LauncherEntryTarget.HOME] otherwise - i.e. only when
 *   tapped from a different launcher while Open Launcher is not the default.
 */
fun decideLauncherEntryTarget(
    isDefaultHome: Boolean,
    launchedFromSelf: Boolean,
    homeIsResumed: Boolean,
): LauncherEntryTarget = if (isDefaultHome || launchedFromSelf || homeIsResumed) {
    LauncherEntryTarget.SETTINGS
} else {
    LauncherEntryTarget.HOME
}

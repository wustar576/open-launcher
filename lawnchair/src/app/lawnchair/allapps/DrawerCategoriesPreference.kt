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

package app.lawnchair.allapps

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.android.launcher3.LauncherPrefs

/**
 * "Show category folders" preference.
 *
 * NOTE: this reads and writes the same `SharedPreferences` file as
 * `app.lawnchair.preferences.PreferenceManager` (`LauncherPrefs.getPrefs`), but keeps its own
 * accessor because `PreferenceManager` is owned by another work stream. Once that file gains
 * `val drawerCategories = BoolPref("pref_drawerCategories", true, recreate)` this object can be
 * deleted and the call sites switched over without any data migration.
 */
object DrawerCategoriesPreference {

    const val KEY = "pref_drawerCategories"
    const val DEFAULT = true

    private fun prefs(context: Context): SharedPreferences = LauncherPrefs.getPrefs(context.applicationContext)

    fun get(context: Context): Boolean = prefs(context).getBoolean(KEY, DEFAULT)

    fun set(context: Context, value: Boolean) {
        prefs(context).edit { putBoolean(KEY, value) }
    }

    fun addListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeListener(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }
}

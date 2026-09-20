package app.lawnchair.search.algorithms.engine

import android.content.pm.ShortcutInfo
import com.android.launcher3.model.data.AppInfo

/**
 * Internal representation of a search result.
 *
 * Open Launcher searches installed apps and their shortcuts only — there are no web, contact,
 * file, settings, calculator or search-history results, and no network access at all.
 */
sealed interface SearchResult {
    data class App(val data: AppInfo) : SearchResult
    data class Shortcut(val data: ShortcutInfo) : SearchResult
}

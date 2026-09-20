package app.lawnchair.search.algorithms.engine.provider.apps

import android.content.Context
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import app.lawnchair.search.algorithms.engine.SearchResult
import app.lawnchair.search.algorithms.filterHiddenApps
import com.android.launcher3.model.data.AppInfo
import java.text.Normalizer
import java.util.Locale

/**
 * Ranks installed apps against a query.
 *
 * Ranking always goes through [AppMatcher], which is tolerant of typos and of partial or
 * out-of-order words; the old exact `StringMatcherUtility` path has been removed so behaviour no
 * longer depends on a "fuzzy search" preference.
 */
object AppSearchProvider {

    private val DIACRITICS_REMOVE_PATTERN = "\\p{M}+".toRegex()

    fun search(context: Context, query: String, apps: List<AppInfo>): List<SearchResult.App> {
        if (query.isBlank()) return emptyList()

        val prefs = PreferenceManager2.getInstance(context)
        val hiddenApps = prefs.hiddenApps.firstCached()
        val hiddenAppsInSearch = prefs.hiddenAppsInSearch.firstCached()
        val maxAppResults = prefs.maxAppSearchResultCount.firstCached()

        val queryNormalized = stripDiacritics(query).lowercase(Locale.getDefault())

        return rank(apps, queryNormalized, maxAppResults, hiddenApps, hiddenAppsInSearch)
            .map { SearchResult.App(data = it) }
    }

    internal fun rank(
        apps: List<AppInfo>,
        query: String,
        maxResultsCount: Int,
        hiddenApps: Set<String>,
        hiddenAppsInSearch: String,
    ): List<AppInfo> = apps.asSequence()
        .filterHiddenApps(query, hiddenApps, hiddenAppsInSearch)
        .mapNotNull { app ->
            val matchResult = AppMatcher.match(stripDiacritics(app.title.toString()), query)
            if (matchResult.type == MatchType.NO_MATCH) null else app to matchResult
        }
        .sortedWith(
            compareBy(
                { it.second.type.priority },
                { -it.second.score },
                { it.first.title?.toString()?.lowercase(Locale.getDefault()).orEmpty() },
            ),
        )
        .map { it.first }
        .take(maxResultsCount)
        .toList()

    private fun stripDiacritics(input: String): String {
        return Normalizer.normalize(input, Normalizer.Form.NFKD)
            .replace(DIACRITICS_REMOVE_PATTERN, "")
    }
}

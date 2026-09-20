package app.lawnchair.search.algorithms.engine.provider.web

import android.content.Context
import android.net.Uri
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.R

/**
 * A [WebSearchProvider] built from a user supplied URL template.
 * The suggestion endpoint (and its OkHttp client) has been removed; see [WebSearchProvider].
 */
object CustomWebSearchProvider : WebSearchProvider {

    override val id: String = "custom"

    override val label: Int = R.string.search_provider_custom

    override val iconRes: Int = R.drawable.ic_search

    private var searchUrlTemplate: String = ""
    private var displayName: String = ""

    fun getDisplayName(): String = displayName

    override fun configure(context: Context): WebSearchProvider {
        val prefs = PreferenceManager2.getInstance(context)
        searchUrlTemplate = prefs.webSuggestionProviderUrl.firstCached()
        displayName = prefs.webSuggestionProviderName.firstCached()
        return this
    }

    override fun getSearchUrl(query: String): String {
        if (searchUrlTemplate.isBlank()) {
            return GoogleWebSearchProvider.getSearchUrl(query)
        }
        return searchUrlTemplate.replace("%s", Uri.encode(query))
    }

    override fun toString(): String = id
}

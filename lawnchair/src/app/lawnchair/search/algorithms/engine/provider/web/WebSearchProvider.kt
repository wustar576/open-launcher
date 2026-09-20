package app.lawnchair.search.algorithms.engine.provider.web

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * Describes a web search engine.
 *
 * NOTE: Open Launcher does not search the web. Nothing in the launcher calls [getSearchUrl] any
 * more — this type only survives because the preference store
 * (`PreferenceManager2.webSuggestionProvider`) and the search settings screens, both owned by
 * another work stream, still reference it. Once those are removed this whole package can go.
 * The suggestion-fetching part of the interface (and all of its Retrofit/OkHttp machinery) has
 * already been deleted, so no implementation can make a network request.
 */
interface WebSearchProvider {

    /**
     * Human-readable label used by the preference UI
     */
    @get:StringRes
    val label: Int

    /**
     * Icon resource used by the drawer search bar
     */
    @get:DrawableRes
    val iconRes: Int

    /**
     * A unique, stable ID for this provider (e.g., "google", "duckduckgo", "custom").
     */
    val id: String

    fun configure(context: Context): WebSearchProvider = this

    /**
     * Constructs the final search URL for a given query.
     */
    fun getSearchUrl(query: String): String

    override fun toString(): String

    companion object WebSearchProviderCompanion {
        fun values(): List<WebSearchProvider> = listOf(
            GoogleWebSearchProvider,
            DuckDuckGoWebSearchProvider,
            CustomWebSearchProvider,
        )

        fun fromString(value: String): WebSearchProvider = when (value) {
            "duckduckgo" -> DuckDuckGoWebSearchProvider
            "custom" -> CustomWebSearchProvider
            else -> GoogleWebSearchProvider
        }
    }
}

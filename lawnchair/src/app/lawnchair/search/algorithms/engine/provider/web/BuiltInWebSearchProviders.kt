package app.lawnchair.search.algorithms.engine.provider.web

import android.net.Uri
import com.android.launcher3.R

/**
 * Inert descriptions of web search engines. See [WebSearchProvider] for why these still exist.
 * The Retrofit/OkHttp suggestion clients (Google, DuckDuckGo, StartPage, StartPage EU, Kagi)
 * were removed along with the web suggestion provider; the Startpage affiliate parameters went
 * with them.
 */
object GoogleWebSearchProvider : WebSearchProvider {

    override val label = R.string.search_provider_google

    override val iconRes = R.drawable.ic_super_g_color

    override val id: String = "google"

    override fun getSearchUrl(query: String): String = "https://google.com/search?q=${Uri.encode(query)}"

    override fun toString(): String = id
}

object DuckDuckGoWebSearchProvider : WebSearchProvider {

    override val label = R.string.search_provider_duckduckgo

    override val iconRes = R.drawable.ic_duckduckgo

    override val id: String = "duckduckgo"

    override fun getSearchUrl(query: String): String = "https://duckduckgo.com/?q=${Uri.encode(query)}"

    override fun toString(): String = id
}

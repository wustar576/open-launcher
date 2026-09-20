package app.lawnchair.search.algorithms.engine

import android.content.Context
import app.lawnchair.search.adapter.SPACE
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.search.adapter.SearchTargetFactory

sealed interface SectionBuilder {
    /**
     * Takes the full list of results and returns a list of SearchTargetCompat
     * objects for its specific section, or an empty list if its section
     * should not be displayed.
     */
    fun build(
        context: Context,
        factory: SearchTargetFactory,
        results: List<SearchResult>,
    ): List<SearchTargetCompat>
}

data object AppsAndShortcutsSectionBuilder : SectionBuilder {
    override fun build(
        context: Context,
        factory: SearchTargetFactory,
        results: List<SearchResult>,
    ): List<SearchTargetCompat> {
        val apps = results.filterIsInstance<SearchResult.App>()
        val shortcuts = results.filterIsInstance<SearchResult.Shortcut>()

        if (apps.isEmpty() && shortcuts.isEmpty()) return emptyList()

        val targets = mutableListOf<SearchTargetCompat>()

        if (apps.size == 1 && shortcuts.isNotEmpty()) {
            // A single hit with shortcuts: show the app as a row, followed by its shortcuts.
            val singleApp = apps.first()
            targets.add(factory.createAppSearchTarget(singleApp.data, asRow = true))
            targets.addAll(shortcuts.map { factory.createShortcutTarget(it.data) })
        } else {
            targets.addAll(apps.map { factory.createAppSearchTarget(it.data, asRow = false) })
        }
        targets.add(factory.createHeaderTarget(SPACE))

        return targets
    }
}

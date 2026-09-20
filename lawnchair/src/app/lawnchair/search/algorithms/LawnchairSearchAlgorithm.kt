package app.lawnchair.search.algorithms

import android.content.Context
import app.lawnchair.allapps.views.SearchItemBackground
import app.lawnchair.allapps.views.SearchResultView.Companion.EXTRA_QUICK_LAUNCH
import app.lawnchair.search.LawnchairSearchAdapterProvider
import app.lawnchair.search.adapter.SearchAdapterItem
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.search.adapter.SearchTargetCompat.Companion.RESULT_TYPE_APPLICATION
import app.lawnchair.search.adapter.SearchTargetCompat.Companion.RESULT_TYPE_SHORTCUT
import com.android.app.search.LayoutType.EMPTY_DIVIDER
import com.android.app.search.LayoutType.ICON_HORIZONTAL_TEXT
import com.android.app.search.LayoutType.ICON_SINGLE_VERTICAL_TEXT
import com.android.app.search.LayoutType.SMALL_ICON_HORIZONTAL_TEXT
import com.android.app.search.LayoutType.TEXT_HEADER
import com.android.launcher3.BuildConfig
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.search.SearchAlgorithm
import com.android.launcher3.search.SearchCallback

sealed class LawnchairSearchAlgorithm(
    protected val context: Context,
) : SearchAlgorithm<BaseAllAppsAdapter.AdapterItem> {

    private val iconBackground = SearchItemBackground(
        context,
        showBackground = false,
        roundTop = true,
        roundBottom = true,
    )
    private val normalBackground = SearchItemBackground(
        context,
        showBackground = true,
        roundTop = true,
        roundBottom = true,
    )
    private val topBackground = SearchItemBackground(
        context,
        showBackground = true,
        roundTop = true,
        roundBottom = false,
    )
    private val centerBackground = SearchItemBackground(
        context,
        showBackground = true,
        roundTop = false,
        roundBottom = false,
    )
    private val bottomBackground = SearchItemBackground(
        context,
        showBackground = true,
        roundTop = false,
        roundBottom = true,
    )

    protected fun transformSearchResults(results: List<SearchTargetCompat>): List<SearchAdapterItem> {
        val filtered = results
            .asSequence()
            .filter { it.packageName != BuildConfig.APPLICATION_ID }
            .filter { LawnchairSearchAdapterProvider.viewTypeMap[it.layoutType] != null }
            .removeDuplicateDividers()
            .toList()

        val appAndShortcutIndices = findAppAndShorcutIndices(filtered)

        return filtered.mapIndexedNotNull { index, target ->
            val isFirst = index == 0 || filtered[index - 1].isDivider
            val isLast = index == filtered.lastIndex || filtered[index + 1].isDivider

            // todo make quick launch work on non-app results
            if (
                (target.isApp && target.layoutType == SMALL_ICON_HORIZONTAL_TEXT) ||
                target.isShortcut
            ) {
                SearchAdapterItem.createAdapterItem(target, getGroupedBackground(index, appAndShortcutIndices))
            } else if (target.layoutType == ICON_SINGLE_VERTICAL_TEXT && target.extras.getBoolean(EXTRA_QUICK_LAUNCH, false)) {
                SearchAdapterItem.createAdapterItem(target, normalBackground)
            } else {
                SearchAdapterItem.createAdapterItem(target, getBackground(target.layoutType, isFirst, isLast))
            }
        }
    }

    protected fun setFirstItemQuickLaunch(searchTargets: List<SearchTargetCompat>) {
        val hasQuickLaunch = searchTargets.any { it.extras.getBoolean(EXTRA_QUICK_LAUNCH, false) }
        if (!hasQuickLaunch) {
            // check if we have a header or spacer item. if so, we skip as there isn't any relevant
            // action to be applied
            val target = searchTargets.getOrNull(
                searchTargets.indexOfFirst { it.layoutType != TEXT_HEADER },
            )
            target?.extras?.apply {
                putBoolean(EXTRA_QUICK_LAUNCH, true)
            }
        }
    }

    private fun findAppAndShorcutIndices(filtered: List<SearchTargetCompat>): List<Int> = filtered.indices.filter {
        (filtered[it].isApp || filtered[it].isShortcut) &&
            (
                filtered[it].layoutType == ICON_HORIZONTAL_TEXT ||
                    filtered[it].layoutType == SMALL_ICON_HORIZONTAL_TEXT
                )
    }

    private fun getBackground(
        layoutType: String,
        isFirst: Boolean,
        isLast: Boolean,
    ): SearchItemBackground = when {
        layoutType == TEXT_HEADER || layoutType == ICON_SINGLE_VERTICAL_TEXT || layoutType == EMPTY_DIVIDER -> iconBackground
        isFirst && isLast -> normalBackground
        isFirst -> topBackground
        isLast -> bottomBackground
        else -> centerBackground
    }

    private fun getGroupedBackground(index: Int, indices: List<Int>): SearchItemBackground = when {
        indices.size == 1 -> normalBackground
        index == indices.first() -> topBackground
        index == indices.last() -> bottomBackground
        else -> centerBackground
    }

    open fun doZeroStateSearch(callback: SearchCallback<BaseAllAppsAdapter.AdapterItem>) {
        // Default implementation is to clear results.
        callback.clearSearchResult()
    }

    companion object {

        /**
         * Preference values kept only so that stored preferences and the (to be removed) search
         * settings screens keep resolving. Open Launcher always uses [LawnchairAppSearchAlgorithm].
         */
        const val APP_SEARCH = "appSearch"
        const val LOCAL_SEARCH = "localSearch"
        const val ASI_SEARCH = "globalSearch"

        /** The ASI / global search integration has been removed. */
        @Suppress("UNUSED_PARAMETER")
        fun isASISearchEnabled(context: Context): Boolean = false

        fun create(context: Context): LawnchairSearchAlgorithm = LawnchairAppSearchAlgorithm(context)
    }
}

private fun Sequence<SearchTargetCompat>.removeDuplicateDividers(): Sequence<SearchTargetCompat> {
    var previousWasDivider = true
    return filter { item ->
        val isDivider = item.layoutType == EMPTY_DIVIDER
        val remove = isDivider && previousWasDivider
        previousWasDivider = isDivider
        !remove
    }
}

private val SearchTargetCompat.isApp get() = resultType == RESULT_TYPE_APPLICATION
private val SearchTargetCompat.isShortcut get() = resultType == RESULT_TYPE_SHORTCUT
private val SearchTargetCompat.isDivider get() = layoutType == EMPTY_DIVIDER

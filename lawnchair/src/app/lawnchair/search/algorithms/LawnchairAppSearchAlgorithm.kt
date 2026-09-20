package app.lawnchair.search.algorithms

import android.content.Context
import android.os.Handler
import app.lawnchair.search.adapter.SearchTargetCompat
import app.lawnchair.search.adapter.SearchTargetFactory
import app.lawnchair.search.algorithms.engine.AppsAndShortcutsSectionBuilder
import app.lawnchair.search.algorithms.engine.SearchResult
import app.lawnchair.search.algorithms.engine.provider.ShortcutSearchProvider
import app.lawnchair.search.algorithms.engine.provider.apps.AppSearchProvider
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherModel
import com.android.launcher3.allapps.BaseAllAppsAdapter
import com.android.launcher3.model.AllAppsList
import com.android.launcher3.model.BgDataModel
import com.android.launcher3.model.ModelTaskController
import com.android.launcher3.search.SearchCallback
import com.android.launcher3.util.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The one and only search algorithm in Open Launcher: installed apps plus, when a single app
 * matches, that app's shortcuts. Everything runs locally; no network request is ever issued.
 */
class LawnchairAppSearchAlgorithm(context: Context) : LawnchairSearchAlgorithm(context) {

    private val appState = LauncherAppState.getInstance(context)
    private val resultHandler = Handler(Executors.MAIN_EXECUTOR.looper)

    private val searchTargetFactory = SearchTargetFactory(context)

    private val coroutineScope = CoroutineScope(context = Dispatchers.IO)

    override fun doSearch(query: String, callback: SearchCallback<BaseAllAppsAdapter.AdapterItem>) {
        appState.model.enqueueModelUpdateTask(object : LauncherModel.ModelUpdateTask {
            override fun execute(app: ModelTaskController, dataModel: BgDataModel, apps: AllAppsList) {
                val appResults = AppSearchProvider.search(this@LawnchairAppSearchAlgorithm.context, query, apps.data)
                coroutineScope.launch(Dispatchers.Main) {
                    val results = getResult(appResults)
                    callback.onSearchResult(query, results)
                }
            }
        })
    }

    override fun cancel(interruptActiveRequests: Boolean) {
        if (interruptActiveRequests) {
            resultHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun getResult(
        appResults: List<SearchResult.App>,
    ): ArrayList<BaseAllAppsAdapter.AdapterItem> {
        val shortcutResults = ShortcutSearchProvider.search(context, appResults)
        val searchTargets: List<SearchTargetCompat> = AppsAndShortcutsSectionBuilder.build(
            context,
            searchTargetFactory,
            appResults + shortcutResults,
        )

        setFirstItemQuickLaunch(searchTargets)
        return ArrayList(transformSearchResults(searchTargets))
    }
}

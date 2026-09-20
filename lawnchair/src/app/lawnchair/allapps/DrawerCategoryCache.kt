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

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import androidx.annotation.StringRes
import androidx.annotation.WorkerThread
import app.lawnchair.flowerpot.Flowerpot
import app.lawnchair.util.SingletonHolder
import app.lawnchair.util.categorizeAppsIntoBuckets
import app.lawnchair.util.ensureOnMainThread
import app.lawnchair.util.useApplicationContext
import com.android.launcher3.R
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.util.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the (expensive) flowerpot categorisation of the app drawer off the main thread and
 * off the hot path of "user opened the drawer".
 *
 * ### Invalidation
 *
 * A cached result is only reused when the *cache key* still matches. The key is made of
 *
 *  * a generation counter that a [BroadcastReceiver] bumps on `PACKAGE_ADDED`, `PACKAGE_REMOVED`,
 *    `PACKAGE_CHANGED` and `PACKAGE_REPLACED` — this covers app *updates*, which can change the
 *    intent filters the flowerpot rules match on, without ever touching the PackageManager here;
 *  * the number of apps and an order-insensitive hash of their component keys — this covers
 *    profile changes and anything the broadcast misses.
 *
 * ### Threading
 *
 * [peek] is a cheap main-thread lookup. When it misses, the caller renders the plain A-Z list and
 * calls [requestCompute], which does the PackageManager work on [Executors.MODEL_EXECUTOR] and
 * then invokes the callback on the main thread so the drawer can re-run its adapter build.
 */
class DrawerCategoryCache private constructor(context: Context) {

    private val appContext: Context = context.applicationContext

    /**
     * Created eagerly here because [Flowerpot.Manager] insists on being constructed on the main
     * thread; afterwards the instance is safe to use from the worker.
     */
    private val potsManager: Flowerpot.Manager = Flowerpot.Manager.getInstance(appContext)

    private val generation = AtomicInteger(0)

    @Volatile
    private var snapshot: Snapshot? = null

    @Volatile
    private var inFlightKey: CacheKey? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            generation.incrementAndGet()
            snapshot = null
            inFlightKey = null
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        runCatching {
            appContext.registerReceiver(packageReceiver, filter)
        }.onFailure { Log.w(TAG, "Could not register package receiver", it) }
    }

    /** Identifies the app set a cached categorisation was computed from. */
    data class CacheKey(val generation: Int, val appCount: Int, val componentHash: Int)

    /** One rendered category: a bucket key, its localised title and its members. */
    data class Category(
        val bucketKey: String,
        @param:StringRes val titleRes: Int,
        val componentKeys: List<String>,
    )

    private data class Snapshot(val key: CacheKey, val categories: List<Category>)

    @AnyThread
    fun keyFor(apps: List<AppInfo>): CacheKey {
        var hash = 0
        apps.forEach { hash += it.toComponentKey().toString().hashCode() }
        return CacheKey(generation.get(), apps.size, hash)
    }

    /** Returns the cached categories when they are still valid for [apps], otherwise `null`. */
    @AnyThread
    fun peek(apps: List<AppInfo>): List<Category>? {
        val current = snapshot ?: return null
        return if (current.key == keyFor(apps)) current.categories else null
    }

    /**
     * Recomputes the categorisation on a worker thread unless an identical computation is already
     * running, then calls [onReady] on the main thread.
     */
    @MainThread
    fun requestCompute(apps: List<AppInfo>, onReady: () -> Unit) {
        val key = keyFor(apps)
        if (inFlightKey == key) return
        inFlightKey = key
        val snapshotOfApps = apps.toList()
        Executors.MODEL_EXECUTOR.execute {
            val categories = runCatching { compute(snapshotOfApps) }
                .onFailure { Log.w(TAG, "Categorisation failed", it) }
                .getOrDefault(emptyList())
            // The generation may have moved on while we were working; only publish if it did not.
            if (generation.get() == key.generation) {
                snapshot = Snapshot(key, categories)
            }
            inFlightKey = null
            Executors.MAIN_EXECUTOR.execute(onReady)
        }
    }

    @WorkerThread
    private fun compute(apps: List<AppInfo>): List<Category> {
        val members = categorizeAppsIntoBuckets(apps, appContext, potsManager)
        return DrawerCategoryBuckets.foldForDisplay(members).map { (bucketKey, bucketApps) ->
            Category(
                bucketKey = bucketKey,
                titleRes = titleResFor(bucketKey),
                componentKeys = bucketApps
                    .sortedBy { it.title?.toString()?.lowercase().orEmpty() }
                    .map { it.toComponentKey().toString() },
            )
        }
    }

    companion object : SingletonHolder<DrawerCategoryCache, Context>(
        ensureOnMainThread(useApplicationContext(::DrawerCategoryCache)),
    ) {
        private const val TAG = "DrawerCategoryCache"

        @JvmStatic
        override fun getInstance(arg: Context): DrawerCategoryCache = super.getInstance(arg)

        @StringRes
        fun titleResFor(bucketKey: String): Int = when (bucketKey) {
            DrawerCategoryBuckets.COMMUNICATION -> R.string.drawer_category_communication
            DrawerCategoryBuckets.SOCIAL -> R.string.drawer_category_social
            DrawerCategoryBuckets.MEDIA -> R.string.drawer_category_media
            DrawerCategoryBuckets.PHOTOGRAPHY -> R.string.drawer_category_photography
            DrawerCategoryBuckets.GAMES -> R.string.drawer_category_games
            DrawerCategoryBuckets.PRODUCTIVITY -> R.string.drawer_category_productivity
            DrawerCategoryBuckets.TOOLS -> R.string.drawer_category_tools
            DrawerCategoryBuckets.SHOPPING -> R.string.drawer_category_shopping
            DrawerCategoryBuckets.FINANCE -> R.string.drawer_category_finance
            DrawerCategoryBuckets.TRAVEL -> R.string.drawer_category_travel
            DrawerCategoryBuckets.LIFESTYLE -> R.string.drawer_category_lifestyle
            DrawerCategoryBuckets.GOOGLE -> R.string.drawer_category_google
            DrawerCategoryBuckets.SYSTEM -> R.string.drawer_category_system
            else -> R.string.drawer_category_other
        }
    }
}

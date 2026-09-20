package app.lawnchair.allapps

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import app.lawnchair.data.folder.FolderEntry
import app.lawnchair.data.folder.model.FolderViewModel
import app.lawnchair.launcher
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.util.observeOnce
import com.android.launcher3.InvariantDeviceProfile.OnIDPChangeListener
import com.android.launcher3.allapps.AllAppsStore
import com.android.launcher3.allapps.AlphabeticalAppsList
import com.android.launcher3.allapps.BaseAllAppsAdapter.AdapterItem
import com.android.launcher3.allapps.PrivateProfileManager
import com.android.launcher3.allapps.WorkProfileManager
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.model.data.FolderInfo
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.views.ActivityContext
import com.patrykmichalik.opto.core.onEach
import java.util.function.Predicate

@Suppress("SYNTHETIC_PROPERTY_WITHOUT_JAVA_ORIGIN")
class LawnchairAlphabeticalAppsList<T>(
    private val context: T,
    private val appsStore: AllAppsStore<T>,
    workProfileManager: WorkProfileManager?,
    privateProfileManager: PrivateProfileManager?,
) : AlphabeticalAppsList<T>(context, appsStore, workProfileManager, privateProfileManager),
    OnIDPChangeListener,
    DefaultLifecycleObserver
    where T : Context, T : ActivityContext {

    private var hiddenApps: Set<String> = setOf()
    private val prefs2 = PreferenceManager2.getInstance(context)
    private val prefs = PreferenceManager.getInstance(context)

    private val viewModel = FolderViewModel(
        (context as? ComponentActivity)?.application ?: context.launcher.application,
    )
    private val folderList = mutableListOf<FolderEntry>()
    private val filteredList = mutableListOf<AppInfo>()

    private val categoryCache = DrawerCategoryCache.getInstance(context)
    private var showCategories = DrawerCategoriesPreference.get(context)

    private val categoriesPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DrawerCategoriesPreference.KEY) {
            showCategories = DrawerCategoriesPreference.get(context)
            onAppsUpdated()
        }
    }

    init {
        context.launcher.deviceProfile.inv.addOnChangeListener(this)
        (context as? LifecycleOwner)?.lifecycle?.addObserver(this)
        try {
            prefs2.hiddenApps.onEach(launchIn = context.launcher.lifecycleScope) {
                hiddenApps = it
                onAppsUpdated()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to initialize hidden apps", t)
        }
        DrawerCategoriesPreference.addListener(context, categoriesPrefListener)
        observeFolders()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        context.launcher.deviceProfile.inv.removeOnChangeListener(this)
        DrawerCategoriesPreference.removeListener(context, categoriesPrefListener)
    }

    private fun observeFolders() {
        viewModel.folders.observeOnce(context as LifecycleOwner) { folders ->
            if (folders != null) {
                folderList.clear()
                folderList.addAll(folders)
                updateAdapterItems()
            }
        }
    }

    override fun updateItemFilter(itemFilter: Predicate<ItemInfo>?) {
        mItemFilter = Predicate { info ->
            require(info is AppInfo) { "`info` must be an instance of `AppInfo`." }
            val componentKey = info.toComponentKey().toString()
            (itemFilter?.test(info) != false) && !hiddenApps.contains(componentKey)
        }
        onAppsUpdated()
    }

    /**
     * Builds the drawer as "folders on top, full A-Z list below".
     *
     * * When the user made their own drawer folders those are shown and, if
     *   `pref_hideFolderApps` is on, their members are taken out of the A-Z list — unchanged
     *   behaviour.
     * * Otherwise, and when "show category folders" is on, automatically derived category
     *   folders are shown instead. These never remove anything from the A-Z list, so every app
     *   stays reachable alphabetically.
     */
    override fun addAppsWithSections(appList: List<AppInfo?>?, startPosition: Int): Int {
        if (appList.isNullOrEmpty()) return startPosition
        filteredList.clear()
        var position = startPosition

        // Show app drawer folders only on main profile, to prevent state complexity
        if (isWorkOrPrivateSpace(appList)) return super.addAppsWithSections(appList, position)

        val hideFolderApps = prefs.folderApps.get()
        var hasManualFolders = false

        folderList.forEach { folderEntry ->
            val resolvedApps = folderEntry.itemComponentKeys.mapNotNull { keyString ->
                val componentKey = ComponentKey.fromString(keyString) ?: return@mapNotNull null
                appsStore.getApp(componentKey) as? AppInfo
            }

            if (resolvedApps.size > 1) {
                val folderInfo = FolderInfo().apply {
                    id = folderEntry.id
                    title = folderEntry.title
                    resolvedApps.forEach { add(it) }
                }
                mAdapterItems.add(AdapterItem.asFolder(folderInfo))
                position++
                hasManualFolders = true

                if (hideFolderApps) {
                    filteredList.addAll(resolvedApps)
                }
            }
        }

        if (!hasManualFolders && showCategories) {
            position = addCategoryFolders(appList.filterNotNull(), position)
        }

        val remainingApps = appList.filterNot { app -> filteredList.contains(app) && hideFolderApps }
        return super.addAppsWithSections(remainingApps, position)
    }

    /**
     * Adds the automatically derived category folders. Never blocks: on a cache miss nothing is
     * added and a background recomputation is kicked off that rebuilds the adapter once done.
     */
    private fun addCategoryFolders(apps: List<AppInfo>, startPosition: Int): Int {
        var position = startPosition
        val categories = categoryCache.peek(apps)
        if (categories == null) {
            categoryCache.requestCompute(apps) { updateAdapterItems() }
            return position
        }

        categories.forEach { category ->
            val resolvedApps = category.componentKeys.mapNotNull { keyString ->
                val componentKey = ComponentKey.fromString(keyString) ?: return@mapNotNull null
                appsStore.getApp(componentKey) as? AppInfo
            }
            if (resolvedApps.size < DrawerCategoryBuckets.MIN_BUCKET_SIZE) return@forEach

            val folderInfo = FolderInfo().apply {
                title = context.getString(category.titleRes)
                resolvedApps.forEach { add(it) }
            }
            mAdapterItems.add(AdapterItem.asFolder(folderInfo))
            position++
        }
        return position
    }

    override fun onIdpChanged(modelPropertiesChanged: Boolean) {
        onAppsUpdated()
    }
}

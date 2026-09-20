package app.lawnchair.util

import android.content.Context
import app.lawnchair.allapps.DrawerCategoryBuckets
import app.lawnchair.allapps.DrawerCategoryCache
import app.lawnchair.flowerpot.Flowerpot
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.util.ApplicationInfoWrapper

/**
 * Assigns every app to exactly one drawer category bucket.
 *
 * The 31 shipped flowerpot rule sets plus the two synthetic pots
 * ([DrawerCategoryBuckets.POT_GOOGLE] / [DrawerCategoryBuckets.POT_SYSTEM]) are collapsed into
 * the handful of buckets described by [DrawerCategoryBuckets]. An app that matches several pots
 * is claimed by the first bucket in [DrawerCategoryBuckets.claimOrder] that wants it, which makes
 * the result deterministic.
 *
 * This is an expensive call (it queries the PackageManager through the flowerpot rules) and must
 * therefore never run on the main thread — see `DrawerCategoryCache`.
 *
 * @param apps apps to categorise; already filtered (hidden apps removed) by the caller.
 * @param context used for the system-app check.
 * @param potsManager flowerpot manager; must have been obtained on the main thread.
 * @return bucket key to apps, including [DrawerCategoryBuckets.OTHER] for everything unmatched.
 */
fun categorizeAppsIntoBuckets(
    apps: List<AppInfo>,
    context: Context,
    potsManager: Flowerpot.Manager,
): Map<String, List<AppInfo>> {
    val unclaimed = LinkedHashMap<String, AppInfo>(apps.size)
    apps.forEach { app ->
        val key = app.toComponentKey().toString()
        if (key !in unclaimed) unclaimed[key] = app
    }

    val result = LinkedHashMap<String, MutableList<AppInfo>>()

    fun claim(bucket: String, matched: Collection<AppInfo>) {
        if (matched.isEmpty()) return
        val target = result.getOrPut(bucket) { mutableListOf() }
        matched.forEach { app ->
            val key = app.toComponentKey().toString()
            if (unclaimed.remove(key) != null) target.add(app)
        }
    }

    // Google and system apps are claimed first, mirroring the previous drawer behaviour.
    claim(
        DrawerCategoryBuckets.GOOGLE,
        unclaimed.values.filter { it.targetPackage?.startsWith("com.google.") == true },
    )
    claim(
        DrawerCategoryBuckets.SYSTEM,
        unclaimed.values.filter { app ->
            val intent = app.intent ?: return@filter false
            runCatching { ApplicationInfoWrapper(context, intent).isSystem() }.getOrDefault(false)
        },
    )

    // Then the flowerpot rule sets, bucket by bucket, in a fixed order.
    DrawerCategoryBuckets.claimOrder.forEach { bucket ->
        if (bucket == DrawerCategoryBuckets.GOOGLE || bucket == DrawerCategoryBuckets.SYSTEM) return@forEach
        DrawerCategoryBuckets.potsFor(bucket).forEach { potName ->
            if (unclaimed.isEmpty()) return@forEach
            val pot = potsManager.getPot(potName) ?: return@forEach
            val matched = runCatching {
                pot.categorizeApps(unclaimed.values.toList()).values.flatten()
            }.getOrDefault(emptyList())
            claim(bucket, matched)
        }
    }

    if (unclaimed.isNotEmpty()) {
        result[DrawerCategoryBuckets.OTHER] = unclaimed.values.toMutableList()
    }

    return result
}

/**
 * Same categorisation as [categorizeAppsIntoBuckets], but keyed by the localised bucket title.
 *
 * Kept for the "Lawndeck" home-screen layout, which needs human readable folder names rather
 * than bucket keys.
 */
fun categorizeAppsWithSystemAndGoogle(
    apps: List<AppInfo>,
    context: Context,
): Map<String, List<AppInfo>> {
    val potsManager = Flowerpot.Manager.getInstance(context)
    return categorizeAppsIntoBuckets(apps, context, potsManager)
        .mapKeys { (bucketKey, _) -> context.getString(DrawerCategoryCache.titleResFor(bucketKey)) }
}

/*
 * Copyright 2021, Lawnchair
 * Copyright 2026, Open Launcher contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair.ui.preferences.components

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.lawnchair.FeedBridge
import app.lawnchair.icons.CustomAdaptiveIconDrawable
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences.preferenceManager
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.ListPreference
import app.lawnchair.ui.preferences.components.controls.ListPreferenceEntry
import com.android.launcher3.R
import com.google.accompanist.drawablepainter.rememberDrawablePainter

private const val TAG = "FeedPreference"

data class ProviderInfo(
    val name: String,
    val packageName: String,
    val icon: Drawable?,
)

fun getProviders(context: Context) = FeedBridge.getAvailableProviders(context).map {
    ProviderInfo(
        name = it.loadLabel(context.packageManager).toString(),
        packageName = it.packageName,
        icon = CustomAdaptiveIconDrawable.wrapNonNull(it.loadIcon(context.packageManager)),
    )
}

fun getEntries(context: Context) = getProviders(context).map {
    ListPreferenceEntry(
        value = it.packageName,
        endWidget = {
            if (it.icon != null) {
                Image(
                    painter = rememberDrawablePainter(drawable = it.icon),
                    contentDescription = null,
                    modifier = Modifier.requiredSize(48.dp),
                )
            }
        },
        label = { it.name },
    )
}.toList()

@Composable
fun FeedPreference(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val adapter = preferenceManager().feedProvider.getAdapter()
    val preferredPackage = adapter.state.value
    val entries = remember { getEntries(context) }
    val resolvedPackage = remember(preferredPackage) {
        FeedBridge.getInstance(context).resolveBridge(preferredPackage)?.packageName
            ?: FeedBridge.GOOGLE_APP_PACKAGE
    }
    val resolvedEntry = entries.firstOrNull { it.value == resolvedPackage }

    // 只有一個提供者時沒有什麼好選的，直接不顯示這一列。
    if (entries.size < 2) return

    ListPreference(
        value = resolvedEntry?.value ?: "",
        onValueChange = adapter::onChange,
        entries = entries,
        label = stringResource(R.string.feed_provider),
        modifier = modifier,
        endWidget = resolvedEntry?.endWidget,
    )
}

/**
 * 未安裝 Open Launcher Feed 時顯示的項目：點下去開啟本專案的 GitHub Releases。
 */
@Composable
fun FeedCompanionMissingPreference(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    ClickablePreference(
        label = stringResource(R.string.feed_get_companion),
        modifier = modifier,
        subtitle = stringResource(R.string.feed_get_companion_description),
        onClick = {
            val url = context.getString(R.string.feed_releases_url)
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, url.toUri())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                Log.w(TAG, "No app available to open $url", e)
            }
        },
    )
}

package app.lawnchair.search.adapter

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Process
import androidx.core.os.bundleOf
import app.lawnchair.theme.color.tokens.ColorTokens
import com.android.app.search.LayoutType
import com.android.launcher3.R
import com.android.launcher3.model.data.AppInfo
import com.android.launcher3.util.ComponentKey
import java.security.MessageDigest

/**
 * Builds the search targets the all-apps search adapter renders.
 *
 * Open Launcher only produces app, app-shortcut and section-header targets. The contact, file,
 * settings, calculator, search-history, web-suggestion, web-search and Play-Store targets were
 * removed together with their providers.
 */
class SearchTargetFactory(
    private val context: Context,
) {
    fun createAppSearchTarget(appInfo: AppInfo, asRow: Boolean = false): SearchTargetCompat {
        val componentName = appInfo.componentName
        val user = appInfo.user
        return SearchTargetCompat.Builder(
            SearchTargetCompat.RESULT_TYPE_APPLICATION,
            if (asRow) LayoutType.SMALL_ICON_HORIZONTAL_TEXT else LayoutType.ICON_SINGLE_VERTICAL_TEXT,
            generateHashKey(ComponentKey(componentName, user).toString()),
        ).apply {
            setPackageName(componentName?.packageName ?: "")
            setUserHandle(user)
            setExtras(bundleOf("class" to (componentName?.className ?: "")))
        }.build()
    }

    fun createShortcutTarget(shortcutInfo: ShortcutInfo): SearchTargetCompat {
        return SearchTargetCompat.Builder(
            SearchTargetCompat.RESULT_TYPE_SHORTCUT,
            LayoutType.SMALL_ICON_HORIZONTAL_TEXT,
            "shortcut_" + generateHashKey("${shortcutInfo.`package`}|${shortcutInfo.userHandle}|${shortcutInfo.id}"),
        ).apply {
            setShortcutInfo(shortcutInfo)
            setUserHandle(shortcutInfo.userHandle)
            setExtras(Bundle())
        }.build()
    }

    fun createHeaderTarget(header: String, pkg: String = HEADER): SearchTargetCompat {
        val id = "header_$header"
        val action = SearchActionCompat.Builder(id, header)
            .setIcon(
                Icon.createWithResource(context, R.drawable.ic_allapps_search)
                    .setTint(ColorTokens.TextColorPrimary.resolveColor(context)),
            )
            .setIntent(Intent())
            .build()
        return createSearchTarget(
            id,
            action,
            LayoutType.TEXT_HEADER,
            SearchTargetCompat.RESULT_TYPE_SECTION_HEADER,
            pkg,
        )
    }

    companion object {
        private const val HASH_ALGORITHM = "SHA-256"

        // TODO find a way to properly provide tag/provide ids to search target
        private val messageDigest by lazy { MessageDigest.getInstance(HASH_ALGORITHM) }

        private fun generateHashKey(input: String): String = messageDigest.digest(input.toByteArray())
            .joinToString(separator = "") { "%02x".format(it) }

        fun createSearchTarget(
            id: String,
            action: SearchActionCompat,
            layoutType: String,
            targetCompat: Int,
            pkg: String,
            extras: Bundle = Bundle(),
        ): SearchTargetCompat {
            return SearchTargetCompat.Builder(
                targetCompat,
                layoutType,
                generateHashKey(id),
            ).apply {
                setPackageName(pkg)
                setUserHandle(Process.myUserHandle())
                setSearchAction(action)
                setExtras(extras)
            }.build()
        }
    }
}

// keys used in `pkg` param
const val HEADER = "header"
const val SPACE = "space"
const val SPACE_MINI = "space_mini"
const val LOADING = "loading"
const val ERROR = "error"
const val SHORTCUT = "shortcut"

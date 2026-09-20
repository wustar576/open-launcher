package app.lawnchair.allapps.views

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import app.lawnchair.allapps.views.SearchResultView.Companion.FLAG_HIDE_SUBTITLE
import app.lawnchair.font.FontManager
import app.lawnchair.search.adapter.SearchTargetCompat
import com.android.app.search.LayoutType
import com.android.launcher3.R
import com.android.launcher3.views.BubbleTextHolder

class SearchResultIconRow(context: Context, attrs: AttributeSet?) :
    LinearLayout(context, attrs),
    SearchResultView,
    BubbleTextHolder {

    private var isSmall = false
    private lateinit var icon: SearchResultIcon
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private var delimiter: View? = null
    private lateinit var shortcutIcons: Array<SearchResultIcon>

    private var flags = 0

    override fun onFinishInflate() {
        super.onFinishInflate()
        isSmall = id == R.id.search_result_small_icon_row
        icon = ViewCompat.requireViewById(this, R.id.icon)
        icon.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        val iconSize = icon.iconSize
        icon.layoutParams.apply {
            width = iconSize
            height = iconSize
        }
        icon.setTextVisibility(false)
        title = ViewCompat.requireViewById(this, R.id.title)
        subtitle = ViewCompat.requireViewById(this, R.id.subtitle)
        subtitle.isVisible = false
        FontManager.INSTANCE.get(context).setCustomFont(title, R.id.font_heading)
        FontManager.INSTANCE.get(context).setCustomFont(subtitle, R.id.font_body)
        delimiter = findViewById(R.id.delimiter)
        setOnClickListener(icon)

        shortcutIcons = listOf(
            R.id.shortcut_0,
            R.id.shortcut_1,
            R.id.shortcut_2,
        )
            .mapNotNull { findViewById<SearchResultIcon>(it) }
            .toTypedArray()
        shortcutIcons.forEach {
            it.setTextVisibility(false)
            it.layoutParams.apply {
                width = icon.iconSize
                height = icon.iconSize
            }
        }
    }

    override val isQuickLaunch get() = icon.isQuickLaunch || hasFlag(flags, SearchResultView.FLAG_QUICK_LAUNCH)
    override val titleText get() = if (icon.titleText != "") icon.titleText else title.text

    override fun launch(): Boolean {
        performClick()
        return true
    }

    override fun bind(target: SearchTargetCompat, shortcuts: List<SearchTargetCompat>) {
        flags = getFlags(target.extras)

        icon.bind(target) {
            title.text = it.title
            tag = it
        }
        bindShortcuts(shortcuts)
        var showDelimiter = true
        if (isSmall) {
            val textRows = ViewCompat.requireViewById<LinearLayout>(this, R.id.text_rows)
            if (target.layoutType == LayoutType.HORIZONTAL_MEDIUM_TEXT) {
                showDelimiter = false
                layoutParams.height = resources.getDimensionPixelSize(R.dimen.search_result_row_medium_height)
                textRows.orientation = VERTICAL
                subtitle.setPadding(0, 0, 0, 0)
            } else {
                layoutParams.height = resources.getDimensionPixelSize(R.dimen.search_result_small_row_height)
                textRows.orientation = HORIZONTAL
                val subtitleStartPadding = resources.getDimensionPixelSize(R.dimen.search_result_subtitle_padding_start)
                subtitle.setPaddingRelative(subtitleStartPadding, 0, 0, 0)
            }
        }
        setSubtitleText(target.searchAction?.subtitle, showDelimiter)
        if (shouldHandleClick(target) && !isSmall) {
            setOnClickListener {
                target.searchAction?.intent?.let { intent -> handleSearchTargetClick(context, intent) }
            }
        }
    }

    private fun setSubtitleText(subtitleText: CharSequence?, showDelimiter: Boolean) {
        if (subtitleText.isNullOrEmpty() || icon.hasFlag(FLAG_HIDE_SUBTITLE)) {
            subtitle.isVisible = false
            delimiter?.isVisible = false
        } else {
            subtitle.text = subtitleText
            subtitle.isVisible = true
            delimiter?.isVisible = showDelimiter
        }
    }

    private fun bindShortcuts(shortcuts: List<SearchTargetCompat>) {
        shortcutIcons.forEachIndexed { index, icon ->
            if (index < shortcuts.size) {
                icon.isVisible = true
                icon.bind(shortcuts[index], emptyList())
            } else {
                icon.isVisible = false
            }
        }
    }

    override fun getBubbleText() = icon
}

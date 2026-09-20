/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

import android.app.Activity
import android.os.Bundle
import android.os.Process
import android.util.TypedValue
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView

/**
 * 極簡的說明畫面，沒有 launcher icon（intent-filter 只有 MAIN + INFO），
 * 只能從「應用程式資訊」或 adb 開啟：
 *
 *   adb shell am start -n app.openlauncher.feed/.FeedInfoActivity
 *
 * 目的是在實機上快速確認外掛的套件名、UID、bridge 模式，以及 Google app 在不在。
 */
class FeedInfoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val connector = GoogleOverlayConnector(applicationContext)
        val googleAvailable = connector.isGoogleAppAvailable()
        val uri = OverlayProtocol.buildUri(packageName, Process.myUid())
        val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

        val text = buildString {
            appendLine(getString(R.string.info_title))
            appendLine()
            appendLine(getString(R.string.info_package, packageName))
            appendLine(getString(R.string.info_uid, Process.myUid().toString()))
            appendLine(getString(R.string.info_debuggable, debuggable.toString()))
            appendLine(getString(R.string.info_uri, uri))
            appendLine(
                getString(
                    R.string.info_google_app,
                    if (googleAvailable) {
                        getString(R.string.info_available)
                    } else {
                        getString(R.string.info_missing)
                    },
                ),
            )
            appendLine()
            appendLine(getString(R.string.info_body))
        }

        FeedLog.i(FeedLog.INFO, "uri=$uri debuggable=$debuggable googleApp=$googleAvailable")

        val view = TextView(this).apply {
            setText(text)
            setTextIsSelectable(true)
            gravity = Gravity.START
            val padding = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                24f,
                resources.displayMetrics,
            ).toInt()
            setPadding(padding, padding, padding, padding)
        }
        setContentView(ScrollView(this).apply { addView(view) })
    }
}

/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

import android.util.Log

/**
 * 統一的 log tag。實機除錯時用：
 *
 *   adb logcat -s OLFeed.Service OLFeed.Upstream OLFeed.Bridge OLFeed.Proxy OLFeed.Info
 *
 * （Android 的 tag 上限是 23 個字元，以下都在範圍內。）
 */
object FeedLog {
    const val SERVICE = "OLFeed.Service"
    const val UPSTREAM = "OLFeed.Upstream"
    const val BRIDGE = "OLFeed.Bridge"
    const val PROXY = "OLFeed.Proxy"
    const val INFO = "OLFeed.Info"

    fun d(tag: String, message: String) = Log.d(tag, message)
    fun i(tag: String, message: String) = Log.i(tag, message)
    fun w(tag: String, message: String, t: Throwable? = null) = Log.w(tag, message, t)
    fun e(tag: String, message: String, t: Throwable? = null) = Log.e(tag, message, t)
}

/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

/**
 * 與 overlay service 溝通時用到的純字串／常數邏輯。
 *
 * 這裡刻意不碰 [android.net.Uri]，全部用字串處理，才能在 JVM 單元測試中直接驗證
 * （`android.net.Uri` 在本機單元測試裡是空殼，會回傳 null）。
 */
object OverlayProtocol {

    /** 啟動器與外掛、外掛與 Google app 之間共用的 intent action。 */
    const val ACTION_WINDOW_OVERLAY = "com.android.launcher3.WINDOW_OVERLAY"

    /** data URI 的 scheme；intent-filter 只能宣告這一項，不能限制 host。 */
    const val SCHEME = "app"

    /** 提供 Discover 畫面的 Google app 套件名。 */
    const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"

    /** service.api.version：外掛對啟動器宣告 7，也用 7 去綁 Google app。 */
    const val API_VERSION = 7

    /** client version，對應 URI 查詢參數 `cv`。 */
    const val CLIENT_VERSION = 9

    const val QUERY_API_VERSION = "v"
    const val QUERY_CLIENT_VERSION = "cv"

    /**
     * 組出 `app://<套件名>:<uid>?v=<api>&cv=<client>`。
     *
     * overlay service 會從這個 URI 認出「誰要綁我」，所以外掛去綁 Google app 時
     * 必須填自己的套件名與自己的 UID，不能沿用啟動器的。
     */
    fun buildUri(
        packageName: String,
        uid: Int,
        apiVersion: Int = API_VERSION,
        clientVersion: Int = CLIENT_VERSION,
    ): String {
        require(packageName.isNotBlank()) { "packageName must not be blank" }
        require(uid >= 0) { "uid must not be negative" }
        return "$SCHEME://$packageName:$uid" +
            "?$QUERY_API_VERSION=$apiVersion" +
            "&$QUERY_CLIENT_VERSION=$clientVersion"
    }

    /**
     * 從 data URI 取出套件名。探測階段的 URI 可能只有 `app://<套件名>`（沒有 UID、
     * 沒有查詢字串），所以兩種格式都要能解。無法解析時回傳 null。
     */
    fun packageNameOf(uri: String?): String? {
        val authority = authorityOf(uri) ?: return null
        val colon = authority.lastIndexOf(':')
        val name = if (colon >= 0) authority.substring(0, colon) else authority
        return name.ifEmpty { null }
    }

    /** 從 data URI 取出 UID；URI 沒帶 UID 或格式不對時回傳 null。 */
    fun uidOf(uri: String?): Int? {
        val authority = authorityOf(uri) ?: return null
        val colon = authority.lastIndexOf(':')
        if (colon < 0) return null
        return authority.substring(colon + 1).toIntOrNull()?.takeIf { it >= 0 }
    }

    private fun authorityOf(uri: String?): String? {
        val prefix = "$SCHEME://"
        if (uri == null || !uri.startsWith(prefix)) return null
        val rest = uri.substring(prefix.length)
        val end = rest.indexOfFirst { it == '?' || it == '#' || it == '/' }
        val authority = if (end >= 0) rest.substring(0, end) else rest
        return authority.ifEmpty { null }
    }
}

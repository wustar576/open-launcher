/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

/**
 * `windowAttached` / `windowAttached2` 夾帶內容的**純邏輯**：怎麼描述它、哪些欄位會洩漏
 * 「真正的客戶端是誰」、以及該改寫成什麼。完全不碰 Android API，所以可以單元測試。
 *
 * ## 為什麼需要改寫
 *
 * Google app 的 overlay service 只接受系統 app 或 debuggable app 當客戶端。外掛本身是
 * debuggable，綁定用的 data URI 也是外掛自己的套件名與 UID，`Binder.getCallingUid()`
 * 更是外掛的 UID——身分這三關都過了。
 *
 * 但 `windowAttached2` 的 `layout_params` 是**啟動器視窗的** [android.view.WindowManager.LayoutParams]，
 * 其中 `packageName` 欄位寫著啟動器的套件名。實機觀察（2026-09-20，Pixel 10 / Android 16）：
 *
 * - debug 版啟動器（本身 debuggable）＋外掛 → Google app 回報 `overlayStatusChanged(0x19)`，Discover 正常；
 * - release 版啟動器（不是 debuggable）＋**同一個外掛** → Google app 完全不回話。
 *
 * 兩者唯一的差別就是那個套件名指向的 app 是否 debuggable，因此外掛在轉送之前把
 * `packageName` 改成自己的。**window token 不能動**：overlay 視窗必須掛在啟動器的視窗上，
 * 那個 token 就是它的錨點。
 */
object AttachPayload {

    const val KEY_LAYOUT_PARAMS = "layout_params"
    const val KEY_CONFIGURATION = "configuration"
    const val KEY_CLIENT_OPTIONS = "client_options"

    /** [android.view.WindowManager.LayoutParams] 裡我們會看、會改的欄位。 */
    data class LayoutParamsFacts(
        val packageName: String?,
        val type: Int,
        val flags: Int,
        val hasToken: Boolean,
        val title: String?,
    ) {
        override fun toString(): String =
            "packageName=$packageName type=$type flags=${hex(flags)} " +
                "token=${if (hasToken) "present" else "MISSING"} title=$title"
    }

    /**
     * 一行講完整包 attach 的內容，給實機 logcat 用。
     *
     * @param keys bundle 的全部 key（包含我們不認得的）。
     * @param clientOptions `client_options`，沒有則 null。
     * @param configuration `configuration` 的簡述，沒有則 null。
     */
    fun describe(
        keys: Collection<String>?,
        layoutParams: LayoutParamsFacts?,
        clientOptions: Int?,
        configuration: String?,
    ): String = buildString {
        append("keys=").append(keys?.sorted() ?: "<none>")
        append(" | layout_params: ").append(layoutParams ?: "<absent>")
        append(" | client_options=").append(clientOptions?.let { hex(it) } ?: "<absent>")
        append(" | configuration: ").append(configuration ?: "<absent>")
    }

    /**
     * 這包 attach 的 `packageName` 需要改寫嗎？
     *
     * @return 應該寫進去的套件名；已經是外掛自己（或外掛套件名不明）時回傳 null，代表不必動。
     */
    fun packageNameRewrite(current: String?, ourPackageName: String): String? = when {
        ourPackageName.isEmpty() -> null
        current == ourPackageName -> null
        else -> ourPackageName
    }

    /**
     * 視窗標題也會寫出啟動器是誰（實機看到的是
     * `app.openlauncher/app.lawnchair.LawnchairLauncher`），所以同樣要換掉。
     *
     * @return 應該寫進去的標題；不需要改時回傳 null。
     */
    fun titleRewrite(current: String?, ourPackageName: String): String? = when {
        ourPackageName.isEmpty() -> null
        current == null -> null
        current.startsWith("$ourPackageName/") -> null
        else -> "$ourPackageName/$OVERLAY_SERVICE_CLASS"
    }

    /** 改寫結果的一行說明。 */
    fun describeRewrite(field: String, from: String?, to: String?): String =
        if (to == null) {
            "layout_params.$field kept as $from (already ours or unknown)"
        } else {
            "layout_params.$field rewritten $from -> $to (window token untouched)"
        }

    private const val OVERLAY_SERVICE_CLASS = "app.openlauncher.feed.OverlayBridgeService"

    fun hex(value: Int): String = "0x" + Integer.toHexString(value)
}

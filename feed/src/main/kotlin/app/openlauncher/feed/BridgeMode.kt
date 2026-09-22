/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

/**
 * `onBind()` 要回傳哪一種介面。
 *
 * 實機階段可以在不改程式碼的情況下切換：修改 AndroidManifest.xml 中
 * service 的 `<meta-data android:name="app.openlauncher.feed.bridge_mode" .../>`，
 * 然後重新安裝。
 */
enum class BridgeMode(val manifestValue: String) {

    /**
     * (B) 代理：回傳 `amirz.aidlbridge.IBridge`。外掛以自己的身分綁 Google app，
     * 再把拿到的 binder 經由 `IBridgeCallback.onServiceConnected` 交還啟動器。
     * 之後啟動器直接對 Google app 下 overlay 指令，外掛不在資料路徑上。
     */
    BRIDGE("bridge"),

    /**
     * (A) 轉送：回傳 `com.google.android.libraries.launcherclient.ILauncherOverlay`
     * 的 Stub，17 個交易逐一轉送給 Google app。所有呼叫都由外掛的程序發出，
     * 因此 `Binder.getCallingUid()` 永遠是外掛的 UID。
     */
    OVERLAY_PROXY("proxy"),
    ;

    companion object {
        /**
         * meta-data 缺漏或無法辨識時採用的預設值。
         *
         * 2026-09-20 於 Pixel 10（Android 16）實測：`BRIDGE` 幾乎必定失敗——Google app
         * 交回來的 binder 在啟動器這一端連 `getInterfaceDescriptor()` 都是空字串，
         * `windowAttached2` 不會拋例外但永遠等不到 `overlayStatusChanged`，於是捲動事件
         * 全被丟掉。也就是 README 第 5 節風險 1 的情況：Google app 每一筆交易都重新檢查
         * `Binder.getCallingUid()`，binder 交還啟動器之後呼叫者變成啟動器的 UID。
         * 2026-09-22 在外掛這一端補到了直接證據：同一個 binder 在外掛程序內查得到
         * `descriptor=…ILauncherOverlay`、`hasOverlayContent()` 也回 true，交還啟動器
         * 之後連 `INTERFACE_TRANSACTION` 都不回（README §4.13 §3）。
         *
         * **2026-09-22 更正**：原本這裡寫「`OVERLAY_PROXY` 實測可正常顯示 Discover」，
         * 那句話已被同日的 2×2 實機測試推翻——**proxy 在乾淨的冷啟動下從來沒有獨立
         * 成功過**。09-20 那次「成功」是借用了 Google 先前為直連的 debug 啟動器建好、
         * 又因舊版啟動器切換提供者時不送 `windowDetached` 而沒被拆掉的視窗。
         * proxy 的失敗點與 bridge 不同：交易的 uid 是外掛的（正確），但
         * `layout_params` 裡的 window token 屬於啟動器（另一個 uid），而 token
         * 外掛改不了。詳見 README §4.13。
         *
         * 預設值維持 `OVERLAY_PROXY`——不是因為它可用，而是因為兩者之中只有它的
         * 交易 uid 是對的，而且診斷 log 最完整。
         */
        val DEFAULT = OVERLAY_PROXY

        const val META_DATA_NAME = "app.openlauncher.feed.bridge_mode"

        fun fromManifestValue(value: String?): BridgeMode {
            if (value == null) return DEFAULT
            val normalized = value.trim().lowercase()
            return entries.firstOrNull { it.manifestValue == normalized } ?: DEFAULT
        }
    }
}

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
        /** meta-data 缺漏或無法辨識時採用的預設值。 */
        val DEFAULT = BRIDGE

        const val META_DATA_NAME = "app.openlauncher.feed.bridge_mode"

        fun fromManifestValue(value: String?): BridgeMode {
            if (value == null) return DEFAULT
            val normalized = value.trim().lowercase()
            return entries.firstOrNull { it.manifestValue == normalized } ?: DEFAULT
        }
    }
}

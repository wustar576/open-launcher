/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

import android.util.Log

/**
 * 實機實驗用的開關，**不必重新建置、不必重新安裝**就能切換。
 *
 * ## 為什麼是 `log.tag.*` 系統屬性
 *
 * 外掛刻意不要任何權限（見 AndroidManifest），所以可用的「外部輸入」少得可憐：
 *
 * - `SharedPreferences`／檔案：adb 寫不進別的 app 的私有目錄（除非 root 或 run-as，
 *   而 run-as 對 release 版簽章的 APK 不一定能用）。
 * - `settings put`：屬於「改系統設定」，實機除錯時不該動。
 * - 自訂 broadcast：要 `am broadcast`，而且多一個 exported 的元件。
 * - **`Log.isLoggable(tag, DEBUG)`**：公開 API，底下讀的是 `log.tag.<tag>` 系統屬性，
 *   `adb shell setprop log.tag.<tag> DEBUG` 就能寫，不需要任何權限、不改任何系統設定，
 *   重開機就自動消失。← 採用這個。
 *
 * 屬性預設值是 INFO，所以 `isLoggable(tag, DEBUG)` 預設回傳 false＝開關預設全關，
 * 也就是「與 2026-09-20 唯一成功過的那版完全相同的 wire 行為」。
 *
 * ## 用法
 *
 * ```bash
 * adb shell setprop log.tag.OLFeedRewriteId DEBUG   # 打開
 * adb shell setprop log.tag.OLFeedRewriteId INFO    # 關掉（或 "" 清掉）
 * adb shell am force-stop app.openlauncher.feed     # 讓外掛重讀（見下）
 * ```
 *
 * 旗標是**每次用到時才讀**，所以大部分開關下一次 attach 就生效；但為了讓每次實驗的起點
 * 乾淨（上游連線、window session 都重來一次），實驗流程仍然建議 force-stop 外掛。
 *
 * @param isEnabled 「這個 tag 打開了嗎」。正式使用的是 [fromSystemProperties]；
 *   單元測試直接塞一個假的進來，因此這個類別完全可測。
 */
class FeedFlags(private val isEnabled: (String) -> Boolean) {

    /**
     * 轉送 `windowAttached*` 之前，把 `layout_params.packageName` 與視窗標題改寫成外掛自己的。
     *
     * 2026-09-20 實測沒有用，但那次是在「一個掛在死掉 debug 啟動器 token 上的陳舊
     * `GoogleDiscoverWindow` 還卡著」的狀態下測的，等於沒測過。留著重測。
     */
    val rewriteClientIdentity: Boolean get() = isEnabled(TAG_REWRITE_IDENTITY)

    /**
     * 把啟動器的 `ILauncherOverlayCallback` 包一層 [FeedLog.CALLBACK] 轉送器。
     *
     * 打開：看得見 Google app 到底有沒有回話，但交給 Google app 的 binder 變成外掛的
     * （外掛程序死掉時 Google app 會收到 binder death，也許正是它清掉舊 session 的時機）。
     * 關掉：原樣轉交啟動器的 binder，wire 行為與唯一成功過的那版相同。
     */
    val wrapCallback: Boolean get() = isEnabled(TAG_WRAP_CALLBACK)

    /** 每一次 attach 之前都無條件先補一發 `windowDetached(false)`。 */
    val detachBeforeAttach: Boolean get() = isEnabled(TAG_DETACH_BEFORE)

    /**
     * 關掉「最後一個客戶端離開後延遲 [LINGER_MILLIS] 毫秒才真的 unbind」的緩衝，
     * 回到舊行為（立刻 unbind）。用來重現 2026-09-20 22:48 那次的上游抖動。
     */
    val noLinger: Boolean get() = isEnabled(TAG_NO_LINGER)

    /** 綁 Google app 時加上 `BIND_IMPORTANT`（實機觀察：另一家外掛是用兩條連線綁的）。 */
    val bindImportant: Boolean get() = isEnabled(TAG_BIND_IMPORTANT)

    /** 上游 URI 的 `v=`：預設 [OverlayProtocol.API_VERSION]，可改成 9 或 11。 */
    val upstreamApiVersion: Int
        get() = resolveApiVersion(v9 = isEnabled(TAG_UPSTREAM_V9), v11 = isEnabled(TAG_UPSTREAM_V11))

    /** 上游 URI 的 `cv=`：預設 [OverlayProtocol.CLIENT_VERSION]，null 代表整個參數不要。 */
    val upstreamClientVersion: Int?
        get() = if (isEnabled(TAG_UPSTREAM_NO_CV)) null else OverlayProtocol.CLIENT_VERSION

    /** 真正 unbind 上游之前要等多久（毫秒）。 */
    val lingerMillis: Long get() = if (noLinger) 0L else LINGER_MILLIS

    /** 綁上游時要用的 `bindService` flags。 */
    val upstreamBindFlags: Int get() = resolveBindFlags(bindImportant)

    /** 一行寫出目前所有開關，每次 attach 都記一次，事後看 log 就知道那筆是哪個變體送的。 */
    fun describe(): String =
        "rewriteId=$rewriteClientIdentity wrapCb=$wrapCallback detachPre=$detachBeforeAttach " +
            "linger=${lingerMillis}ms bindFlags=${AttachPayload.hex(upstreamBindFlags)} " +
            "upstream=v$upstreamApiVersion" +
            (upstreamClientVersion?.let { ",cv$it" } ?: ",no-cv")

    companion object {

        const val TAG_REWRITE_IDENTITY = "OLFeedRewriteId"
        const val TAG_WRAP_CALLBACK = "OLFeedWrapCb"
        const val TAG_DETACH_BEFORE = "OLFeedDetachPre"
        const val TAG_NO_LINGER = "OLFeedNoLinger"
        const val TAG_BIND_IMPORTANT = "OLFeedBindImp"
        const val TAG_UPSTREAM_V9 = "OLFeedV9"
        const val TAG_UPSTREAM_V11 = "OLFeedV11"
        const val TAG_UPSTREAM_NO_CV = "OLFeedNoCv"

        /** 全部開關，給 README 與 log 用（順序＝實驗順序）。 */
        val ALL_TAGS = listOf(
            TAG_REWRITE_IDENTITY,
            TAG_WRAP_CALLBACK,
            TAG_DETACH_BEFORE,
            TAG_NO_LINGER,
            TAG_BIND_IMPORTANT,
            TAG_UPSTREAM_V9,
            TAG_UPSTREAM_V11,
            TAG_UPSTREAM_NO_CV,
        )

        /**
         * 啟動器解除綁定後，先不要馬上放掉 Google app 的連線。
         *
         * 實機觀察（2026-09-20 22:48，Pixel 10）：啟動器換提供者／收到套件更新廣播時會
         * 「全部 unbind → 立刻重綁」，兩次之間只差 40 毫秒；舊行為會在這 40 毫秒內把上游
         * 連線整個拆掉再建起來，於是 `windowAttached2` 有可能被送到一個**馬上就要被 unbind
         * 的 binder** 上。多等 1 秒，這種抖動就完全看不到，真正的提供者切換頂多晚 1 秒放手。
         */
        const val LINGER_MILLIS = 1_000L

        /** `Context.BIND_AUTO_CREATE`。常數直接寫死，讓這個檔案能純 JVM 單元測試。 */
        const val BIND_AUTO_CREATE = 0x0001

        /** `Context.BIND_IMPORTANT`。 */
        const val BIND_IMPORTANT = 0x0040

        /** `v=` 的取值規則：兩個都開時以較新的 11 為準。 */
        fun resolveApiVersion(v9: Boolean, v11: Boolean): Int = when {
            v11 -> 11
            v9 -> 9
            else -> OverlayProtocol.API_VERSION
        }

        fun resolveBindFlags(important: Boolean): Int =
            if (important) BIND_AUTO_CREATE or BIND_IMPORTANT else BIND_AUTO_CREATE

        /**
         * 實機用的旗標來源：`adb shell setprop log.tag.<tag> DEBUG`。
         *
         * `Log.isLoggable` 在 tag 太長時會丟例外（舊版 Android），所以包起來，
         * 任何失敗都當作「沒開」——實驗開關絕不可以讓外掛掛掉。
         */
        val fromSystemProperties = FeedFlags { tag ->
            try {
                Log.isLoggable(tag, Log.DEBUG)
            } catch (t: Throwable) {
                false
            }
        }
    }
}

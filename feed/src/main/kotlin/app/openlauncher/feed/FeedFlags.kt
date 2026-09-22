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
 * adb shell setprop log.tag.OLFeedRewriteId INFO    # 關掉（或清成空字串）
 * adb shell am force-stop app.openlauncher.feed     # 讓外掛重讀（見下）
 * ```
 *
 * 旗標是**每次用到時才讀**，所以大部分開關下一次 attach 就生效；但為了讓每次實驗的起點
 * 乾淨（上游連線、window session 都重來一次），實驗流程仍然建議 force-stop 外掛。
 *
 * [TAG_BRIDGE_MODE] 是唯一的例外：模式只在 `Service.onCreate()` 決定一次，
 * 所以切換它**一定**要 force-stop 外掛才會生效。
 *
 * @param isEnabled 「這個 tag 打開了嗎」。正式使用的是 [fromSystemProperties]；
 *   單元測試直接塞一個假的進來，因此這個類別完全可測。
 * @param manifestMode AndroidManifest meta-data 宣告的模式。null 代表「還沒讀到」
 *   （例如純旗標的單元測試），此時 [describeMode] 只印得出被覆寫的那一種。
 *   服務端在 `onCreate()` 讀到 meta-data 之後用 [withManifestMode] 補上。
 */
class FeedFlags(
    private val isEnabled: (String) -> Boolean,
    private val manifestMode: BridgeMode? = null,
) {

    /** 補上 manifest 宣告的模式，讓 [describe] 印得出「現在到底跑哪一種模式」。 */
    fun withManifestMode(mode: BridgeMode): FeedFlags = FeedFlags(isEnabled, mode)

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

    /**
     * 綁 Google app 時改用**兩條** `ServiceConnection`，flags 分別是
     * [BIND_FLAGS_IMPORTANT]（0x41）與 [BIND_FLAGS_WAIVE_PRIORITY]（0x21）。
     *
     * 為什麼：真正的啟動器對 overlay service 是綁兩條（一條「重要」、一條「不搶優先權」），
     * 外掛目前只綁一條。若 Google app 會看「客戶端綁定的形狀」來判斷對方是不是啟動器，
     * 那一條與兩條就是有差別的。兩條會拿到**同一個** binder（Android 對同一個 intent
     * 只呼叫一次 `onBind`），因此只以第一條收到的為準。
     *
     * 與 [bindImportant] 互斥：兩個都開時以這個為準（見 [bindImportantIgnored]）。
     */
    val dualBind: Boolean get() = isEnabled(TAG_DUAL_BIND)

    /** [bindImportant] 被 [dualBind] 蓋掉了（兩個都開）。純粹為了在 log 裡講清楚。 */
    val bindImportantIgnored: Boolean get() = dualBind && bindImportant

    /**
     * 強制使用 [BridgeMode.BRIDGE]，不管 manifest meta-data 寫什麼。
     *
     * 為什麼要有：模式本來只能由 manifest meta-data 決定，換一種就得重新建置、重新安裝。
     * 2026-09-21 實機證實 proxy 模式在冷啟動下從來沒有成功過，bridge 模式因此必須重測，
     * 有這個開關就不必為了換模式重建 APK。
     *
     * 關掉時 manifest 的值照常生效（也就是預設仍然是 proxy）。
     */
    val forceBridgeMode: Boolean get() = isEnabled(TAG_BRIDGE_MODE)

    /**
     * 這一次實際生效的模式。
     *
     * [forceBridgeMode] 打開 → 一定是 [BridgeMode.BRIDGE]；否則就是 manifest 的值
     * （還沒讀到 manifest 時為 null）。
     */
    val effectiveMode: BridgeMode?
        get() = if (forceBridgeMode) BridgeMode.BRIDGE else manifestMode

    /** 用 manifest 的值算出實際模式，給還沒 [withManifestMode] 的呼叫端用。 */
    fun effectiveMode(manifestMode: BridgeMode): BridgeMode =
        if (forceBridgeMode) BridgeMode.BRIDGE else manifestMode

    /**
     * 目前模式與它的來源，例如 `mode=bridge(override)`、`mode=proxy(manifest)`。
     *
     * manifest 還沒讀到而且沒有覆寫時是 `mode=?(manifest)`——看到這個就代表這行 log
     * 出現在 `onCreate()` 讀 meta-data 之前。
     */
    fun describeMode(): String {
        val source = if (forceBridgeMode) "override" else "manifest"
        return "mode=" + (effectiveMode?.manifestValue ?: "?") + "($source)"
    }

    /** 上游 URI 的 `v=`：預設 [OverlayProtocol.API_VERSION]，可改成 9 或 11。 */
    val upstreamApiVersion: Int
        get() = resolveApiVersion(v9 = isEnabled(TAG_UPSTREAM_V9), v11 = isEnabled(TAG_UPSTREAM_V11))

    /** 上游 URI 的 `cv=`：預設 [OverlayProtocol.CLIENT_VERSION]，null 代表整個參數不要。 */
    val upstreamClientVersion: Int?
        get() = if (isEnabled(TAG_UPSTREAM_NO_CV)) null else OverlayProtocol.CLIENT_VERSION

    /** 真正 unbind 上游之前要等多久（毫秒）。 */
    val lingerMillis: Long get() = if (noLinger) 0L else LINGER_MILLIS

    /**
     * 綁上游要建立幾條連線、各自用什麼 `bindService` flags。
     *
     * 一般情況只有一條；[dualBind] 打開時是兩條（順序就是送出去的順序）。
     */
    val upstreamBindFlagsList: List<Int> get() = resolveBindFlagsList(dualBind, bindImportant)

    /** 第一條（也是拿 binder 的那條）連線的 flags。 */
    val upstreamBindFlags: Int get() = upstreamBindFlagsList.first()

    /** 例如 `0x1`、`0x41`、`0x41+0x21`。 */
    fun describeBindFlags(): String =
        upstreamBindFlagsList.joinToString("+") { AttachPayload.hex(it) }

    /** 一行寫出目前所有開關，每次 attach 都記一次，事後看 log 就知道那筆是哪個變體送的。 */
    fun describe(): String =
        describeMode() + " rewriteId=$rewriteClientIdentity wrapCb=$wrapCallback " +
            "detachPre=$detachBeforeAttach linger=${lingerMillis}ms " +
            "bindFlags=${describeBindFlags()}" +
            (if (dualBind) "(dual)" else "") +
            (if (bindImportantIgnored) "(bindImp ignored)" else "") +
            " upstream=v$upstreamApiVersion" +
            (upstreamClientVersion?.let { ",cv$it" } ?: ",no-cv")

    companion object {

        const val TAG_REWRITE_IDENTITY = "OLFeedRewriteId"
        const val TAG_WRAP_CALLBACK = "OLFeedWrapCb"
        const val TAG_DETACH_BEFORE = "OLFeedDetachPre"
        const val TAG_NO_LINGER = "OLFeedNoLinger"
        const val TAG_BIND_IMPORTANT = "OLFeedBindImp"
        const val TAG_DUAL_BIND = "OLFeedDualBind"
        const val TAG_BRIDGE_MODE = "OLFeedBridge"
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
            TAG_DUAL_BIND,
            TAG_BRIDGE_MODE,
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

        /** `Context.BIND_WAIVE_PRIORITY`。 */
        const val BIND_WAIVE_PRIORITY = 0x0020

        /** `Context.BIND_IMPORTANT`。 */
        const val BIND_IMPORTANT = 0x0040

        /** 兩條連線中「重要」的那條：0x41。 */
        const val BIND_FLAGS_IMPORTANT = BIND_AUTO_CREATE or BIND_IMPORTANT

        /** 兩條連線中「不搶優先權」的那條：0x21。 */
        const val BIND_FLAGS_WAIVE_PRIORITY = BIND_AUTO_CREATE or BIND_WAIVE_PRIORITY

        /** `v=` 的取值規則：兩個都開時以較新的 11 為準。 */
        fun resolveApiVersion(v9: Boolean, v11: Boolean): Int = when {
            v11 -> 11
            v9 -> 9
            else -> OverlayProtocol.API_VERSION
        }

        fun resolveBindFlags(important: Boolean): Int =
            if (important) BIND_FLAGS_IMPORTANT else BIND_AUTO_CREATE

        /**
         * 要綁幾條、各自的 flags。
         *
         * dual 打開時一律是 0x41 + 0x21 兩條，important 被忽略（在 log 裡會講）。
         */
        fun resolveBindFlagsList(dual: Boolean, important: Boolean): List<Int> =
            if (dual) {
                listOf(BIND_FLAGS_IMPORTANT, BIND_FLAGS_WAIVE_PRIORITY)
            } else {
                listOf(resolveBindFlags(important))
            }

        /**
         * 實機用的旗標來源：`adb shell setprop log.tag.<tag> DEBUG`。
         *
         * `Log.isLoggable` 在 tag 太長時會丟例外（舊版 Android），所以包起來，
         * 任何失敗都當作「沒開」——實驗開關絕不可以讓外掛掛掉。
         */
        val fromSystemProperties = FeedFlags(
            isEnabled = { tag ->
                try {
                    Log.isLoggable(tag, Log.DEBUG)
                } catch (t: Throwable) {
                    false
                }
            },
        )
    }
}

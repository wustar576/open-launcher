/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

import android.content.ComponentName
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.RemoteException
import android.view.WindowManager
import com.google.android.libraries.launcherclient.ILauncherOverlay
import com.google.android.libraries.launcherclient.ILauncherOverlayCallback

/**
 * 設計 (A)：回傳 `ILauncherOverlay` 的 Stub，17 個交易逐一轉送給 Google app。
 *
 * 與 (B) 的差別只有一件事，但可能是關鍵的一件事：所有 overlay 呼叫都是**從外掛的
 * 程序**發出的，因此 Google app 端看到的 `Binder.getCallingUid()` 永遠是外掛的
 * UID（debuggable，可被接受）。(B) 把 binder 交還啟動器之後，後續呼叫的
 * calling UID 是啟動器的；如果 Google app 每筆交易都重新驗證身分，(B) 就會失敗。
 *
 * 代價：每個呼叫多一次 IPC（捲動事件是 oneway，但頻率高），以及必須自己把
 * `windowAttached*` 的內容暫存起來，等上游連上後補送。
 *
 * ## window token 的歸屬（2026-09-20 的 bug）
 *
 * `windowAttached2` 帶的是**啟動器的 window token**。Google app 會把 Discover 視窗掛在
 * 那個 token 底下，而且顯然不接受同一個 token 同時被兩個 session 認領：只要前一個
 * session 沒有收到 `windowDetached`，下一個 session 的 attach 就會被無視——永遠等不到
 * `overlayStatusChanged`，新聞頁一片空白。因此：
 *
 * - 啟動器 unbind（切換提供者、外掛被移除）時，[releaseWindow] 會在斷線**之前**補送
 *   `windowDetached`，把 token 還回去；
 * - [WindowAttachState] 保證同一個 attach 不會重複送給同一個上游 binder。
 *
 * ## callback 為什麼要包一層
 *
 * `ILauncherOverlayCallback` 原本是直接把啟動器的 binder 原封不動轉交給 Google app，
 * 少一跳 IPC。但這樣一來，「Google app 到底有沒有回報 `overlayStatusChanged`」在外掛這端
 * 完全看不見，實機除錯時只能用猜的。現在改成包一層 [CallbackRelay]：多一次 oneway IPC
 * （捲動期間每幀一次，Pixel 10 實測感覺不出來），換到的是一行決定性的 log，而且上游掛掉時
 * 外掛可以主動送 `overlayStatusChanged(0)` 給啟動器，讓它停止把捲動事件丟進黑洞。
 *
 * ## attach 內容裡的身分改寫（2026-09-20，release 版啟動器）
 *
 * `windowAttached*` 帶的 `LayoutParams.packageName` 是**啟動器的**套件名。Google app 似乎
 * 拿它去做「系統 app 或 debuggable app」的檢查，於是 release 版啟動器（不是 debuggable）
 * 配同一個外掛時完全等不到 `overlayStatusChanged`。轉送前會把它改成外掛自己的套件名，
 * 細節與理由見 [AttachPayload]。window token 保持不動。
 *
 * @param ourPackageName 外掛自己的套件名，寫進轉送出去的 `LayoutParams.packageName`。
 */
/**
 * 要不要把 `windowAttached*` 裡的客戶端身分欄位改寫成外掛自己的？
 *
 * **實機結論（2026-09-20，Pixel 10）：沒有用。** 把 `layout_params.packageName` 與視窗標題
 * 都換成外掛自己之後，Google app 依然不回報 `overlayStatusChanged`；而同一時間
 * `hasOverlayContent()` 這種阻塞式交易**答得出來**（true），代表它其實願意跟外掛講話，
 * 問題不在「客戶端是誰」。程式碼與這段紀錄留著，是為了不要有人再走一次同一條路。
 */
private const val REWRITE_CLIENT_IDENTITY = false

/**
 * 要不要把 `ILauncherOverlayCallback` 包一層 `CallbackRelay`？
 *
 * 包起來可以看見「Google app 到底有沒有回話」，但**改變了交給 Google app 的 binder 身分**：
 * 原本是啟動器的（也就是 window token 的擁有者），包起來之後變成外掛的。2026-09-20 實測
 * 唯一能滑出 Discover 的版本（commit 5659722）就是直接原樣轉交的那一版，因此先關掉，
 * 換取與那一版完全相同的 wire 行為。
 */
private const val WRAP_CALLBACK = false

/**
 * attach 之前要不要先補一發 `windowDetached(false)`？
 *
 * 2026-09-20 實測：沒有用（Google app 留在 `dumpsys window` 裡的那個陳舊
 * `GoogleDiscoverWindow` 不會因此消失）。同樣先關掉，讓 wire 行為與唯一成功過的版本一致。
 */
private const val DETACH_BEFORE_ATTACH = false

class LauncherOverlayProxy(
    private val ourPackageName: String,
) : ILauncherOverlay.Stub(), GoogleOverlayConnector.Listener {

    /** 保護 [remote] / [attachState] / [pendingActivityState]：binder 執行緒與主執行緒都會碰。 */
    private val lock = Any()

    private var remote: ILauncherOverlay? = null

    private val attachState = WindowAttachState<PendingAttach, ILauncherOverlay>()

    private var pendingActivityState: Int? = null

    @Volatile
    private var loggedScroll = false

    /**
     * 一次 `windowAttached*` 的全部內容。
     *
     * @param launcherCallback 啟動器給的 callback binder，外掛要直接通知它時用。
     * @param deliver 把這次 attach 送給某個上游 binder。
     */
    private class PendingAttach(
        val name: String,
        val launcherCallback: ILauncherOverlayCallback?,
        val deliver: (ILauncherOverlay) -> Unit,
    )

    /**
     * 夾在 Google app 與啟動器之間的 callback。只做兩件事：記一行 log、原樣轉送。
     */
    private class CallbackRelay(
        private val target: ILauncherOverlayCallback,
    ) : ILauncherOverlayCallback.Stub() {

        @Volatile
        private var loggedScroll = false

        override fun overlayScrollChanged(progress: Float) {
            if (!loggedScroll) {
                loggedScroll = true
                FeedLog.i(FeedLog.CALLBACK, "first overlayScrollChanged($progress) from the Google app")
            }
            try {
                target.overlayScrollChanged(progress)
            } catch (e: RemoteException) {
                FeedLog.w(FeedLog.CALLBACK, "overlayScrollChanged could not reach the launcher", e)
            }
        }

        override fun overlayStatusChanged(status: Int) {
            FeedLog.i(
                FeedLog.CALLBACK,
                "overlayStatusChanged(0x${Integer.toHexString(status)}) from the Google app" +
                    " -> launcher (scroll events ${if (status and 1 != 0) "accepted" else "dropped"})",
            )
            try {
                target.overlayStatusChanged(status)
            } catch (e: RemoteException) {
                FeedLog.w(FeedLog.CALLBACK, "overlayStatusChanged could not reach the launcher", e)
            }
        }
    }

    // region GoogleOverlayConnector.Listener

    override fun onUpstreamConnected(component: ComponentName, binder: IBinder) {
        val overlay = ILauncherOverlay.Stub.asInterface(binder)
        val replay: PendingAttach?
        val activityState: Int?
        synchronized(lock) {
            remote = overlay
            replay = attachState.onUpstreamConnected(overlay)
            activityState = pendingActivityState
        }
        FeedLog.i(
            FeedLog.PROXY,
            "upstream ready ($component), replaying ${replay?.name ?: "nothing"}",
        )
        replay?.let { attach -> deliverAttach(overlay, "replay ${attach.name}", attach) }
        activityState?.let { state ->
            forwardTo(overlay, "replay setActivityState") { it.setActivityState(state) }
        }
        probeUpstream(overlay)
    }

    /**
     * 診斷用：`windowAttached2` 是 oneway，Google app 不理我們時完全沒有回音，分不出
     * 「它收到了但拒絕」和「它根本不跟我們講話」。這裡打一個**阻塞式**交易
     * （`hasOverlayContent`，唯讀）當作 ping：拿得到答案就代表 binder 是活的、
     * 對方願意處理我們的交易，那麼不回報 `overlayStatusChanged` 就是它的決定。
     *
     * 必須在背景執行緒做——阻塞式 binder 呼叫不可以擋住主執行緒。
     */
    private fun probeUpstream(overlay: ILauncherOverlay) {
        Thread({
            try {
                val hasContent = overlay.hasOverlayContent()
                FeedLog.i(FeedLog.PROXY, "upstream ping: hasOverlayContent() = $hasContent")
            } catch (t: Throwable) {
                FeedLog.w(FeedLog.PROXY, "upstream ping: hasOverlayContent() failed", t)
            }
        }, "OLFeed-probe").start()
    }

    override fun onUpstreamDisconnected(component: ComponentName?) {
        FeedLog.w(FeedLog.PROXY, "upstream gone ($component); calls will be dropped until it returns")
        onUpstreamLost()
    }

    override fun onUpstreamUnavailable(reason: String) {
        FeedLog.e(FeedLog.PROXY, "upstream unavailable: $reason")
        onUpstreamLost()
    }

    private fun onUpstreamLost() {
        val callback = synchronized(lock) {
            remote = null
            attachState.onUpstreamLost()
            attachState.pending?.launcherCallback
        }
        // 主動告訴啟動器「捲動事件現在沒人收」，它才不會把事件丟進黑洞；也讓上游回來後
        // 那個 0x19 對啟動器而言是真正的狀態變化（否則會被 setServiceState 的去重吃掉）。
        if (callback != null) {
            FeedLog.i(FeedLog.PROXY, "telling the launcher the overlay is detached (status 0)")
            try {
                callback.overlayStatusChanged(0)
            } catch (e: RemoteException) {
                FeedLog.w(FeedLog.PROXY, "could not tell the launcher about the lost upstream", e)
            }
        }
    }

    override fun toString(): String = "LauncherOverlayProxy"

    // endregion

    /**
     * 啟動器的綁定結束了（切換提供者、外掛被停用、service 被銷毀）。
     *
     * **必須在 unbind 上游之前呼叫。** 把啟動器的 window token 還給 Google app，否則下一個
     * 提供者（可能是啟動器自己直連 Google app）再拿同一個 token 來 attach 時會被無視。
     */
    fun releaseWindow() {
        val released = synchronized(lock) { attachState.onDetach() }
        if (released == null) {
            FeedLog.i(FeedLog.PROXY, "releaseWindow: nothing was attached")
            return
        }
        FeedLog.i(FeedLog.PROXY, "releaseWindow: handing the launcher window back to the Google app")
        forward("windowDetached(release)") { it.windowDetached(false) }
    }

    // region ILauncherOverlay - 順序與 AIDL 宣告一致（交易碼 1..17）

    // 1
    override fun startScroll() {
        // 實機除錯用：啟動器只在 overlay 回報過狀態之後才會把手勢往下送，所以這一行
        // 同時證明「使用者真的滑了」與「啟動器認為 overlay 是活的」。
        if (!loggedScroll) {
            loggedScroll = true
            FeedLog.i(FeedLog.PROXY, "first startScroll() from the launcher")
        }
        forward("startScroll") { it.startScroll() }
    }

    // 2
    override fun onScroll(progress: Float) = forward("onScroll") { it.onScroll(progress) }

    // 3
    override fun endScroll() = forward("endScroll") { it.endScroll() }

    // 4
    override fun windowAttached(
        lp: WindowManager.LayoutParams?,
        cb: ILauncherOverlayCallback?,
        flags: Int,
    ) {
        val relay = callbackFor(cb)
        FeedLog.i(
            FeedLog.PROXY,
            "windowAttached payload: " + AttachPayload.describe(
                keys = null,
                layoutParams = lp?.let { factsOf(it) },
                clientOptions = flags,
                configuration = null,
            ),
        )
        // 送出去的是改寫過的複本，暫存起來的也是同一份，replay 時內容必定一致。
        val rewritten = if (REWRITE_CLIENT_IDENTITY) lp?.let { rewriteIdentity(it) } else lp
        attach(
            PendingAttach("windowAttached", cb) { it.windowAttached(rewritten, relay, flags) },
            "windowAttached(flags=$flags)",
        )
    }

    // 5
    override fun windowDetached(isChangingConfigurations: Boolean) {
        synchronized(lock) { attachState.onDetach() }
        FeedLog.i(FeedLog.PROXY, "windowDetached(changingConfigurations=$isChangingConfigurations)")
        forward("windowDetached") { it.windowDetached(isChangingConfigurations) }
    }

    // 6
    override fun closeOverlay(flags: Int) = forward("closeOverlay") { it.closeOverlay(flags) }

    // 7
    override fun onPause() = forward("onPause") { it.onPause() }

    // 8
    override fun onResume() = forward("onResume") { it.onResume() }

    // 9
    override fun openOverlay(flags: Int) = forward("openOverlay") { it.openOverlay(flags) }

    // 10
    override fun requestVoiceDetection(start: Boolean) =
        forward("requestVoiceDetection") { it.requestVoiceDetection(start) }

    // 11
    override fun getVoiceSearchLanguage(): String? =
        forwardBlocking("getVoiceSearchLanguage", null) { it.getVoiceSearchLanguage() }

    // 12
    override fun isVoiceDetectionRunning(): Boolean =
        forwardBlocking("isVoiceDetectionRunning", false) { it.isVoiceDetectionRunning() }

    // 13
    override fun hasOverlayContent(): Boolean =
        forwardBlocking("hasOverlayContent", false) { it.hasOverlayContent() }

    // 14
    override fun windowAttached2(bundle: Bundle?, cb: ILauncherOverlayCallback?) {
        val relay = callbackFor(cb)
        val keys = runCatching { bundle?.keySet() }.getOrNull()
        // 送出去的是改寫過的複本，暫存起來的也是同一份，replay 時內容必定一致。
        val rewritten = if (REWRITE_CLIENT_IDENTITY) rewriteAttachBundle(bundle) else describeOnly(bundle)
        attach(
            PendingAttach("windowAttached2", cb) { it.windowAttached2(rewritten, relay) },
            "windowAttached2(keys=$keys)",
        )
    }

    // 15 - 協定中的佔位方法，必須存在才能讓後面的交易碼對齊。
    override fun unusedMethod() = forward("unusedMethod") { it.unusedMethod() }

    // 16
    override fun setActivityState(flags: Int) {
        synchronized(lock) { pendingActivityState = flags }
        // 實機除錯用：Google app 有可能是「沒收到 activity state 就不回話」，
        // 這一行讓「啟動器到底有沒有送、送了什麼」不必用猜的。
        FeedLog.i(FeedLog.PROXY, "setActivityState(${AttachPayload.hex(flags)})")
        forward("setActivityState") { it.setActivityState(flags) }
    }

    // 17
    override fun startSearch(data: ByteArray?, bundle: Bundle?): Boolean =
        forwardBlocking("startSearch", false) { it.startSearch(data, bundle) }

    // endregion

    // region attach 內容的診斷與改寫

    /** [WRAP_CALLBACK] 為 false 時，原樣把啟動器的 callback binder 交給 Google app。 */
    private fun callbackFor(cb: ILauncherOverlayCallback?): ILauncherOverlayCallback? =
        if (WRAP_CALLBACK) cb?.let { CallbackRelay(it) } else cb

    /** 只記錄、不改寫：診斷的那一行還是要有，送出去的仍是原封不動的 bundle。 */
    private fun describeOnly(bundle: Bundle?): Bundle? {
        runCatching {
            bundle?.classLoader = WindowManager.LayoutParams::class.java.classLoader
            val lp = bundle?.parcelable(AttachPayload.KEY_LAYOUT_PARAMS, WindowManager.LayoutParams::class.java)
            FeedLog.i(
                FeedLog.PROXY,
                "windowAttached2 payload (forwarded untouched): " + AttachPayload.describe(
                    keys = bundle?.keySet(),
                    layoutParams = lp?.let { factsOf(it) },
                    clientOptions = bundle?.getInt(AttachPayload.KEY_CLIENT_OPTIONS),
                    configuration = null,
                ),
            )
        }.onFailure { FeedLog.w(FeedLog.PROXY, "could not describe the attach bundle", it) }
        return bundle
    }

    /**
     * 把整包 bundle 印出來（實機診斷），並回傳一個 `layout_params.packageName` 已改成外掛
     * 自己的複本。任何一步失敗都退回原封不動的 bundle——寧可維持舊行為，也不要讓 attach 消失。
     */
    private fun rewriteAttachBundle(bundle: Bundle?): Bundle? {
        if (bundle == null) {
            FeedLog.i(FeedLog.PROXY, "windowAttached2 payload: <null bundle>")
            return null
        }
        return try {
            // 從 binder 收到的 bundle 預設沒有 class loader，取 Parcelable 前要先給一個。
            bundle.classLoader = WindowManager.LayoutParams::class.java.classLoader
            val keys = bundle.keySet().toList()
            val lp = bundle.parcelable(AttachPayload.KEY_LAYOUT_PARAMS, WindowManager.LayoutParams::class.java)
            val configuration =
                bundle.parcelable(AttachPayload.KEY_CONFIGURATION, Configuration::class.java)
            val clientOptions =
                if (bundle.containsKey(AttachPayload.KEY_CLIENT_OPTIONS)) {
                    bundle.getInt(AttachPayload.KEY_CLIENT_OPTIONS)
                } else {
                    null
                }
            FeedLog.i(
                FeedLog.PROXY,
                "windowAttached2 payload: " + AttachPayload.describe(
                    keys = keys,
                    layoutParams = lp?.let { factsOf(it) },
                    clientOptions = clientOptions,
                    configuration = configuration?.let { describeConfiguration(it) },
                ),
            )
            if (lp == null) {
                FeedLog.w(FeedLog.PROXY, "no ${AttachPayload.KEY_LAYOUT_PARAMS} to rewrite; forwarding as-is")
                return bundle
            }
            val copy = rewriteIdentity(lp)
            Bundle(bundle).apply {
                classLoader = WindowManager.LayoutParams::class.java.classLoader
                putParcelable(AttachPayload.KEY_LAYOUT_PARAMS, copy)
            }
        } catch (t: Throwable) {
            FeedLog.w(FeedLog.PROXY, "could not rewrite the attach bundle; forwarding it untouched", t)
            bundle
        }
    }

    /**
     * 回傳一份「客戶端身分」欄位都換成外掛自己的 [WindowManager.LayoutParams] 複本：
     * `packageName`，以及同樣寫著啟動器元件名的視窗標題。
     *
     * **不會**改動傳進來的物件（那是 binder 交易解出來的，但 replay 時還會再用一次），
     * 也**不會**動 `token`——overlay 視窗就是掛在那個 token 上的。
     */
    private fun rewriteIdentity(source: WindowManager.LayoutParams): WindowManager.LayoutParams {
        val currentTitle = source.title?.toString()
        val packageName = AttachPayload.packageNameRewrite(source.packageName, ourPackageName)
        val title = AttachPayload.titleRewrite(currentTitle, ourPackageName)
        FeedLog.i(FeedLog.PROXY, AttachPayload.describeRewrite("packageName", source.packageName, packageName))
        FeedLog.i(FeedLog.PROXY, AttachPayload.describeRewrite("title", currentTitle, title))
        if (packageName == null && title == null) return source
        return try {
            copyOf(source).also { copy ->
                packageName?.let { copy.packageName = it }
                title?.let { copy.setTitle(it) }
            }
        } catch (t: Throwable) {
            FeedLog.w(FeedLog.PROXY, "could not copy LayoutParams; forwarding the original", t)
            source
        }
    }

    /**
     * 深複製 [WindowManager.LayoutParams]。走 [Parcel] 是因為那正是這個物件之後會被
     * binder 序列化的方式：token 與 packageName 都在 `writeToParcel` 的欄位清單裡，
     * 複本因此與原件逐欄位相同。
     */
    private fun copyOf(source: WindowManager.LayoutParams): WindowManager.LayoutParams {
        val parcel = Parcel.obtain()
        return try {
            source.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            WindowManager.LayoutParams.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun factsOf(lp: WindowManager.LayoutParams) = AttachPayload.LayoutParamsFacts(
        packageName = lp.packageName,
        type = lp.type,
        flags = lp.flags,
        hasToken = lp.token != null,
        title = lp.title?.toString(),
    )

    private fun describeConfiguration(configuration: Configuration): String =
        "orientation=${configuration.orientation} density=${configuration.densityDpi} " +
            "smallestWidth=${configuration.smallestScreenWidthDp}dp locale=${configuration.locales[0]}"

    private fun <T> Bundle.parcelable(key: String, type: Class<T>): T? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelable(key, type)
        } else {
            @Suppress("DEPRECATION")
            type.cast(getParcelable<android.os.Parcelable>(key))
        }
    } catch (t: Throwable) {
        FeedLog.w(FeedLog.PROXY, "cannot read $key from the attach bundle", t)
        null
    }

    // endregion

    /** `windowAttached` / `windowAttached2` 共用：記住這次 attach，能送就馬上送。 */
    private fun attach(pending: PendingAttach, description: String) {
        val target = synchronized(lock) {
            val current = remote
            attachState.onAttach(pending, current)
            current
        }
        FeedLog.i(
            FeedLog.PROXY,
            "$description -> ${if (target != null) "forwarding now" else "deferred, upstream not ready"}",
        )
        if (target != null) {
            deliverAttach(target, pending.name, pending)
        }
    }

    /**
     * 送出一次 attach，**前面先補一發 `windowDetached(false)`**。
     *
     * 原因是實機 `dumpsys window` 看到的東西（2026-09-20，Pixel 10）：Google app 在更早的
     * 一次 session 之後，留下一個 `GoogleDiscoverWindow`，掛在**早就死掉的**另一個啟動器
     * 活動 token 上（`mHasSurface=false`、`mWindowRemovalAllowed=false`）。那次 session 的
     * 外掛程序是被重新安裝殺掉的，所以 `windowDetached` 從來沒送出去。
     *
     * 我們自己在 §4.9 已經吃過同樣的虧：overlay 還記著上一個 window token 時，新的 attach
     * 會被無視，`overlayStatusChanged` 永遠不來。既然 `windowDetached` 是 oneway、而且在
     * 「本來就沒有附著」的情況下對我們自己的實作是 no-op，attach 前先送一發是很便宜的保險。
     */
    private fun deliverAttach(overlay: ILauncherOverlay, name: String, pending: PendingAttach) {
        if (DETACH_BEFORE_ATTACH) {
            FeedLog.i(FeedLog.PROXY, "releasing any window the overlay still holds before $name")
            forwardTo(overlay, "windowDetached(before $name)") { it.windowDetached(false) }
        }
        forwardTo(overlay, name) { pending.deliver(it) }
    }

    private inline fun forward(name: String, block: (ILauncherOverlay) -> Unit) {
        val overlay = synchronized(lock) { remote }
        if (overlay == null) {
            FeedLog.d(FeedLog.PROXY, "dropping $name: upstream not connected")
            return
        }
        try {
            block(overlay)
        } catch (e: RemoteException) {
            onForwardFailed(overlay, name, e)
        }
    }

    /** attach／replay 專用（頻率低，不必 inline）。 */
    private fun forwardTo(
        overlay: ILauncherOverlay,
        name: String,
        block: (ILauncherOverlay) -> Unit,
    ) {
        try {
            block(overlay)
        } catch (e: RemoteException) {
            onForwardFailed(overlay, name, e)
        }
    }

    private fun onForwardFailed(overlay: ILauncherOverlay, name: String, e: RemoteException) {
        FeedLog.w(FeedLog.PROXY, "$name failed", e)
        synchronized(lock) {
            if (remote === overlay) {
                remote = null
                attachState.onUpstreamLost()
            }
        }
    }

    private inline fun <T> forwardBlocking(
        name: String,
        fallback: T,
        block: (ILauncherOverlay) -> T,
    ): T {
        val overlay = synchronized(lock) { remote }
        if (overlay == null) {
            FeedLog.d(FeedLog.PROXY, "answering $name with $fallback: upstream not connected")
            return fallback
        }
        return try {
            block(overlay)
        } catch (e: RemoteException) {
            onForwardFailed(overlay, name, e)
            fallback
        }
    }
}

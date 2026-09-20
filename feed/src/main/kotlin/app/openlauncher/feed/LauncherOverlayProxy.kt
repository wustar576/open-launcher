/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

import android.content.ComponentName
import android.os.Bundle
import android.os.IBinder
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
 */
class LauncherOverlayProxy : ILauncherOverlay.Stub(), GoogleOverlayConnector.Listener {

    /** 保護 [remote] / [attachState] / [pendingActivityState]：binder 執行緒與主執行緒都會碰。 */
    private val lock = Any()

    private var remote: ILauncherOverlay? = null

    private val attachState = WindowAttachState<PendingAttach, ILauncherOverlay>()

    private var pendingActivityState: Int? = null

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
        replay?.let { attach -> forwardTo(overlay, "replay ${attach.name}") { attach.deliver(it) } }
        activityState?.let { state ->
            forwardTo(overlay, "replay setActivityState") { it.setActivityState(state) }
        }
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
    override fun startScroll() = forward("startScroll") { it.startScroll() }

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
        val relay = cb?.let { CallbackRelay(it) }
        attach(
            PendingAttach("windowAttached", cb) { it.windowAttached(lp, relay, flags) },
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
        val relay = cb?.let { CallbackRelay(it) }
        val keys = runCatching { bundle?.keySet() }.getOrNull()
        attach(
            PendingAttach("windowAttached2", cb) { it.windowAttached2(bundle, relay) },
            "windowAttached2(keys=$keys)",
        )
    }

    // 15 - 協定中的佔位方法，必須存在才能讓後面的交易碼對齊。
    override fun unusedMethod() = forward("unusedMethod") { it.unusedMethod() }

    // 16
    override fun setActivityState(flags: Int) {
        synchronized(lock) { pendingActivityState = flags }
        forward("setActivityState") { it.setActivityState(flags) }
    }

    // 17
    override fun startSearch(data: ByteArray?, bundle: Bundle?): Boolean =
        forwardBlocking("startSearch", false) { it.startSearch(data, bundle) }

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
            forwardTo(target, pending.name) { pending.deliver(it) }
        }
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

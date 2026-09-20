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
 * `ILauncherOverlayCallback` 直接原封不動轉交給 Google app（binder 可以跨程序傳遞），
 * 所以 Google 的 `overlayScrollChanged` / `overlayStatusChanged` 是直接打回啟動器，
 * 不經過外掛，少一跳。
 */
class LauncherOverlayProxy : ILauncherOverlay.Stub(), GoogleOverlayConnector.Listener {

    @Volatile
    private var remote: ILauncherOverlay? = null

    /** 上游還沒連上時收到的 windowAttached/windowAttached2，連上後補送。 */
    @Volatile
    private var pendingAttach: PendingAttach? = null

    @Volatile
    private var pendingActivityState: Int? = null

    private sealed interface PendingAttach {
        data class Legacy(
            val lp: WindowManager.LayoutParams?,
            val cb: ILauncherOverlayCallback?,
            val flags: Int,
        ) : PendingAttach

        data class Modern(
            val bundle: Bundle?,
            val cb: ILauncherOverlayCallback?,
        ) : PendingAttach
    }

    // region GoogleOverlayConnector.Listener

    override fun onUpstreamConnected(component: ComponentName, binder: IBinder) {
        val overlay = ILauncherOverlay.Stub.asInterface(binder)
        remote = overlay
        FeedLog.i(FeedLog.PROXY, "upstream ready ($component), replaying pending state")
        val attach = pendingAttach
        if (attach != null) {
            forward("replay windowAttached") {
                when (attach) {
                    is PendingAttach.Legacy -> it.windowAttached(attach.lp, attach.cb, attach.flags)
                    is PendingAttach.Modern -> it.windowAttached2(attach.bundle, attach.cb)
                }
            }
        }
        pendingActivityState?.let { state ->
            forward("replay setActivityState") { it.setActivityState(state) }
        }
    }

    override fun onUpstreamDisconnected(component: ComponentName?) {
        FeedLog.w(FeedLog.PROXY, "upstream gone ($component); calls will be dropped until it returns")
        remote = null
    }

    override fun onUpstreamUnavailable(reason: String) {
        FeedLog.e(FeedLog.PROXY, "upstream unavailable: $reason")
        remote = null
    }

    override fun toString(): String = "LauncherOverlayProxy"

    // endregion

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
        pendingAttach = PendingAttach.Legacy(lp, cb, flags)
        FeedLog.i(FeedLog.PROXY, "windowAttached(flags=$flags)")
        forward("windowAttached") { it.windowAttached(lp, cb, flags) }
    }

    // 5
    override fun windowDetached(isChangingConfigurations: Boolean) {
        pendingAttach = null
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
        pendingAttach = PendingAttach.Modern(bundle, cb)
        FeedLog.i(FeedLog.PROXY, "windowAttached2(keys=${runCatching { bundle?.keySet() }.getOrNull()})")
        forward("windowAttached2") { it.windowAttached2(bundle, cb) }
    }

    // 15 - 協定中的佔位方法，必須存在才能讓後面的交易碼對齊。
    override fun unusedMethod() = forward("unusedMethod") { it.unusedMethod() }

    // 16
    override fun setActivityState(flags: Int) {
        pendingActivityState = flags
        forward("setActivityState") { it.setActivityState(flags) }
    }

    // 17
    override fun startSearch(data: ByteArray?, bundle: Bundle?): Boolean =
        forwardBlocking("startSearch", false) { it.startSearch(data, bundle) }

    // endregion

    private inline fun forward(name: String, block: (ILauncherOverlay) -> Unit) {
        val overlay = remote
        if (overlay == null) {
            FeedLog.d(FeedLog.PROXY, "dropping $name: upstream not connected")
            return
        }
        try {
            block(overlay)
        } catch (e: RemoteException) {
            FeedLog.w(FeedLog.PROXY, "$name failed", e)
            remote = null
        }
    }

    private inline fun <T> forwardBlocking(
        name: String,
        fallback: T,
        block: (ILauncherOverlay) -> T,
    ): T {
        val overlay = remote
        if (overlay == null) {
            FeedLog.d(FeedLog.PROXY, "answering $name with $fallback: upstream not connected")
            return fallback
        }
        return try {
            block(overlay)
        } catch (e: RemoteException) {
            FeedLog.w(FeedLog.PROXY, "$name failed", e)
            remote = null
            fallback
        }
    }
}

/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

import amirz.aidlbridge.IBridge
import amirz.aidlbridge.IBridgeCallback
import android.content.ComponentName
import android.os.Handler
import android.os.IBinder
import android.os.RemoteException
import com.google.android.libraries.launcherclient.ILauncherOverlay

/**
 * 設計 (B)：回傳 `amirz.aidlbridge.IBridge` 給啟動器。
 *
 * 流程：
 * 1. 啟動器綁到本外掛的 service，拿到這個 binder（介面描述字串為 `amirz.aidlbridge.IBridge`）。
 * 2. 啟動器呼叫 [bindService]，附上自己的 [IBridgeCallback]。
 * 3. 外掛以**自己的**身分去綁 Google app，取得 `ILauncherOverlay` 的 binder。
 * 4. 外掛把那個 binder 經 `cb.onServiceConnected` 交還啟動器；之後啟動器直接對
 *    Google app 下指令，外掛不在資料路徑上。
 *
 * 啟動器會對同一個 service 綁兩次（`LauncherClientService` 與 `LauncherClient.mBaseService`
 * 各一次，flags 不同），所以這裡會收到兩個不同的 callback，必須兩個都通知。
 */
class BridgeBinder(
    private val connector: GoogleOverlayConnector,
    private val handler: Handler,
) : IBridge.Stub() {

    private val clients = LinkedHashMap<IBinder, Client>()

    /** 已經 ping 過的上游 binder：同一個 binder 只 ping 一次（兩個客戶端不必各打一發）。 */
    private var probedBinder: IBinder? = null

    override fun bindService(cb: IBridgeCallback?, flags: Int) {
        // oneway：這裡在 binder 執行緒上，所有狀態都丟回主執行緒處理。
        FeedLog.i(
            FeedLog.BRIDGE,
            "bindService(flags=${AttachPayload.hex(flags)}) from the launcher, callback=$cb",
        )
        if (cb == null) {
            FeedLog.w(FeedLog.BRIDGE, "bindService() with a null callback, ignoring")
            return
        }
        val token = cb.asBinder()
        handler.post { addClient(token, cb, flags) }
    }

    /** service.onUnbind：所有啟動器都放手了，釋放上游連線。 */
    fun releaseAll() {
        handler.post {
            FeedLog.i(FeedLog.BRIDGE, "releaseAll(), ${clients.size} client(s)")
            clients.values.forEach { it.unlink() }
            clients.clear()
            connector.detachAll()
        }
    }

    private fun addClient(token: IBinder, cb: IBridgeCallback, flags: Int) {
        val existing = clients[token]
        if (existing != null) {
            // 同一個 callback 再綁一次：容忍，直接把目前狀態再回報一次。
            FeedLog.i(
                FeedLog.BRIDGE,
                "duplicate bindService() for $token (flags=${AttachPayload.hex(flags)})",
            )
            connector.attach(existing)
            return
        }
        val client = Client(token, cb)
        clients[token] = client
        FeedLog.i(
            FeedLog.BRIDGE,
            "bindService(flags=${AttachPayload.hex(flags)}) from $token, " +
                "now ${clients.size} client(s)",
        )
        if (!client.link()) {
            clients.remove(token)
            return
        }
        connector.attach(client)
    }

    private fun removeClient(token: IBinder) {
        val client = clients.remove(token) ?: return
        FeedLog.i(FeedLog.BRIDGE, "client $token went away, ${clients.size} left")
        client.unlink()
        connector.detach(client)
    }

    /**
     * 啟動器是用 `IBinder.getInterfaceDescriptor()` 認這個 binder 是不是 overlay 的，
     * 所以交還之前先自己讀一次：2026-09-20 實機在啟動器那一端讀到的是**空字串**，
     * 對照這一行就知道是「Google app 本來就沒給描述字串」還是「跨程序之後才掉的」。
     */
    private fun describeDescriptor(binder: IBinder): String {
        val descriptor = runCatching { binder.interfaceDescriptor }
            .getOrElse { return "<failed: ${it.javaClass.simpleName}>" }
        return when {
            descriptor == null -> "<null>"
            descriptor.isEmpty() -> "<empty>"
            else -> descriptor
        }
    }

    /**
     * 診斷用：打一發**阻塞式**唯讀交易（`hasOverlayContent`）當 ping。
     *
     * 答得出來＝binder 是活的、Google app 願意處理**外掛**送去的交易；接下來啟動器拿著
     * 同一個 binder 送 `windowAttached2` 還是沒反應的話，被擋掉的就是啟動器那個呼叫者身分
     * （README §5 風險 1），而不是外掛。proxy 模式本來就會 ping（見 [LauncherOverlayProxy]），
     * bridge 模式少了這一行就等於少了這個對照組。
     *
     * 必須在背景執行緒做——阻塞式 binder 呼叫不可以擋住主執行緒。
     */
    private fun probeUpstream(binder: IBinder) {
        if (probedBinder === binder) return
        probedBinder = binder
        Thread({
            try {
                val overlay = ILauncherOverlay.Stub.asInterface(binder)
                val hasContent = overlay.hasOverlayContent()
                FeedLog.i(FeedLog.BRIDGE, "upstream ping: hasOverlayContent() = $hasContent")
            } catch (t: Throwable) {
                FeedLog.w(FeedLog.BRIDGE, "upstream ping: hasOverlayContent() failed", t)
            }
        }, "OLFeed-bridge-probe").start()
    }

    private inner class Client(
        private val token: IBinder,
        private val callback: IBridgeCallback,
    ) : GoogleOverlayConnector.Listener, IBinder.DeathRecipient {

        fun link(): Boolean = try {
            token.linkToDeath(this, 0)
            true
        } catch (e: RemoteException) {
            FeedLog.w(FeedLog.BRIDGE, "client $token was already dead", e)
            false
        }

        fun unlink() {
            runCatching { token.unlinkToDeath(this, 0) }
        }

        override fun binderDied() {
            handler.post { removeClient(token) }
        }

        override fun onUpstreamConnected(component: ComponentName, binder: IBinder) {
            FeedLog.i(
                FeedLog.BRIDGE,
                "handing Google overlay binder to $token: binder=$binder " +
                    "descriptor=${describeDescriptor(binder)} alive=${binder.isBinderAlive}",
            )
            try {
                callback.onServiceConnected(component, binder)
            } catch (e: RemoteException) {
                FeedLog.w(FeedLog.BRIDGE, "onServiceConnected() failed for $token", e)
            }
            probeUpstream(binder)
        }

        override fun onUpstreamDisconnected(component: ComponentName?) {
            // 下次連上（可能是新的 binder）要再 ping 一次。
            probedBinder = null
            FeedLog.i(FeedLog.BRIDGE, "telling $token the overlay went away")
            try {
                callback.onServiceDisconnected(component)
            } catch (e: RemoteException) {
                FeedLog.w(FeedLog.BRIDGE, "onServiceDisconnected() failed for $token", e)
            }
        }

        override fun onUpstreamUnavailable(reason: String) {
            // 啟動器沒有「連不上」這個回呼，只能什麼都不做：桌面向右滑會是無反應，
            // 不會當掉。留 log 讓實機階段看得出原因。
            FeedLog.e(FeedLog.BRIDGE, "cannot serve $token: $reason")
        }

        override fun toString(): String = "BridgeClient($token)"
    }
}

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

    override fun bindService(cb: IBridgeCallback?, flags: Int) {
        // oneway：這裡在 binder 執行緒上，所有狀態都丟回主執行緒處理。
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
            FeedLog.i(FeedLog.BRIDGE, "duplicate bindService() for $token (flags=$flags)")
            connector.attach(existing)
            return
        }
        val client = Client(token, cb)
        clients[token] = client
        FeedLog.i(FeedLog.BRIDGE, "bindService(flags=$flags) from $token, now ${clients.size} client(s)")
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
            FeedLog.i(FeedLog.BRIDGE, "handing Google overlay binder to $token")
            try {
                callback.onServiceConnected(component, binder)
            } catch (e: RemoteException) {
                FeedLog.w(FeedLog.BRIDGE, "onServiceConnected() failed for $token", e)
            }
        }

        override fun onUpstreamDisconnected(component: ComponentName?) {
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

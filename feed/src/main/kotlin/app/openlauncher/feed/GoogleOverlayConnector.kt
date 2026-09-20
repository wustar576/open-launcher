/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process

/**
 * 負責「外掛 → Google app」這一段：以**外掛自己的套件名與 UID** 綁 Google app 的
 * overlay service，並把結果轉告所有客戶端。
 *
 * 所有狀態都在主執行緒上操作（binder 執行緒進來的呼叫請先 post 過來），
 * 連線狀態本身交給純邏輯的 [ConnectionStateMachine]。
 */
class GoogleOverlayConnector(
    private val context: Context,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) {

    interface Listener {
        fun onUpstreamConnected(component: ComponentName, binder: IBinder)
        fun onUpstreamDisconnected(component: ComponentName?)
        fun onUpstreamUnavailable(reason: String)
    }

    private val machine = ConnectionStateMachine<Listener>()

    private var boundComponent: ComponentName? = null
    private var upstreamBinder: IBinder? = null
    private var bound = false

    val state: UpstreamState get() = machine.state

    /** 目前拿到的 Google app overlay binder，尚未連上時為 null。 */
    val binder: IBinder? get() = upstreamBinder

    val component: ComponentName? get() = boundComponent

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            handler.post {
                FeedLog.i(FeedLog.UPSTREAM, "connected to $name (binder=$service)")
                boundComponent = name
                upstreamBinder = service
                apply(machine.onUpstreamConnected())
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            handler.post {
                FeedLog.w(FeedLog.UPSTREAM, "disconnected from $name")
                upstreamBinder = null
                apply(machine.onUpstreamDisconnected())
            }
        }

        override fun onBindingDied(name: ComponentName) {
            handler.post {
                FeedLog.w(FeedLog.UPSTREAM, "binding died for $name, rebinding")
                upstreamBinder = null
                apply(machine.onBindingDied())
            }
        }

        override fun onNullBinding(name: ComponentName) {
            handler.post {
                FeedLog.e(FeedLog.UPSTREAM, "$name returned a null binder; is this build debuggable?")
                upstreamBinder = null
                apply(machine.onBindFailed())
            }
        }
    }

    /** 客戶端要用 overlay。重複 attach 同一個 listener 是安全的。 */
    fun attach(listener: Listener) {
        FeedLog.d(FeedLog.UPSTREAM, "attach($listener), state=${machine.state}")
        apply(machine.attach(listener))
    }

    fun detach(listener: Listener) {
        FeedLog.d(FeedLog.UPSTREAM, "detach($listener), state=${machine.state}")
        apply(machine.detach(listener))
    }

    fun detachAll() {
        FeedLog.d(FeedLog.UPSTREAM, "detachAll(), state=${machine.state}")
        apply(machine.detachAll())
    }

    /** 建立要送給 Google app 的 intent；URI 帶的是**外掛自己的**套件名與 UID。 */
    fun buildUpstreamIntent(): Intent {
        val uri = OverlayProtocol.buildUri(context.packageName, Process.myUid())
        return Intent(OverlayProtocol.ACTION_WINDOW_OVERLAY)
            .setPackage(OverlayProtocol.GOOGLE_APP_PACKAGE)
            .setData(Uri.parse(uri))
    }

    /** Google app 是否存在、啟用、並且真的提供 overlay service。 */
    fun isGoogleAppAvailable(): Boolean = try {
        context.packageManager.resolveService(buildUpstreamIntent(), 0) != null
    } catch (t: Throwable) {
        FeedLog.e(FeedLog.UPSTREAM, "resolveService failed", t)
        false
    }

    private fun apply(effect: ConnectionEffect<Listener>) {
        when (effect.command) {
            UpstreamCommand.BIND -> doBind()
            UpstreamCommand.UNBIND -> doUnbind()
            UpstreamCommand.REBIND -> {
                doUnbind()
                doBind()
            }
            null -> Unit
        }

        val component = boundComponent
        val binder = upstreamBinder
        if (component != null && binder != null) {
            effect.notifyConnected.forEach { safely { it.onUpstreamConnected(component, binder) } }
        }
        effect.notifyDisconnected.forEach { safely { it.onUpstreamDisconnected(component) } }
        effect.notifyUnavailable.forEach {
            safely { it.onUpstreamUnavailable("Google app overlay service unavailable") }
        }
    }

    private fun doBind() {
        if (bound) return
        val intent = buildUpstreamIntent()
        FeedLog.i(FeedLog.UPSTREAM, "binding: ${intent.action} data=${intent.data} pkg=${intent.`package`}")
        if (!isGoogleAppAvailable()) {
            FeedLog.e(
                FeedLog.UPSTREAM,
                "${OverlayProtocol.GOOGLE_APP_PACKAGE} is missing or disabled; feed will stay empty",
            )
            apply(machine.onBindFailed())
            return
        }
        val ok = try {
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            FeedLog.e(FeedLog.UPSTREAM, "bindService threw", t)
            false
        }
        if (ok) {
            bound = true
            FeedLog.i(FeedLog.UPSTREAM, "bindService returned true, waiting for onServiceConnected")
        } else {
            FeedLog.e(FeedLog.UPSTREAM, "bindService returned false")
            // 依 Android 文件，回傳 false 仍必須 unbind 才不會洩漏。
            runCatching { context.unbindService(connection) }
            apply(machine.onBindFailed())
        }
    }

    private fun doUnbind() {
        if (!bound) return
        FeedLog.i(FeedLog.UPSTREAM, "unbinding from ${OverlayProtocol.GOOGLE_APP_PACKAGE}")
        runCatching { context.unbindService(connection) }
            .onFailure { FeedLog.w(FeedLog.UPSTREAM, "unbindService failed", it) }
        bound = false
        upstreamBinder = null
        boundComponent = null
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            FeedLog.w(FeedLog.UPSTREAM, "listener callback failed", t)
        }
    }
}

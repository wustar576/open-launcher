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
    private val flags: FeedFlags = FeedFlags.fromSystemProperties,
) {

    interface Listener {
        fun onUpstreamConnected(component: ComponentName, binder: IBinder)
        fun onUpstreamDisconnected(component: ComponentName?)
        fun onUpstreamUnavailable(reason: String)

        /**
         * 上游馬上就要被 unbind 了，但 binder 現在**還活著**。
         *
         * 要把啟動器的 window token 還給 Google app（`windowDetached`）就只剩這個時機，
         * unbind 之後那個 binder 就再也送不出東西。預設什麼都不做。
         */
        fun onUpstreamReleasing(component: ComponentName?, binder: IBinder) = Unit
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

    /**
     * 建立要送給 Google app 的 intent；URI 帶的是**外掛自己的**套件名與 UID。
     *
     * `v=` / `cv=` 可以用系統屬性即時改（見 [FeedFlags]），不必重新建置。
     */
    fun buildUpstreamIntent(): Intent {
        val uri = OverlayProtocol.buildUri(
            packageName = context.packageName,
            uid = Process.myUid(),
            apiVersion = flags.upstreamApiVersion,
            clientVersion = flags.upstreamClientVersion,
        )
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
        // 順序是契約的一部分：unbind 之前先讓客戶端用還活著的 binder 把 window token 還回去。
        val liveBinder = upstreamBinder
        if (liveBinder != null) {
            effect.notifyReleasing.forEach {
                safely { it.onUpstreamReleasing(boundComponent, liveBinder) }
            }
        }

        when (effect.command) {
            UpstreamCommand.BIND -> {
                cancelLingerTimer()
                doBind()
            }
            UpstreamCommand.UNBIND -> {
                cancelLingerTimer()
                doUnbind()
            }
            UpstreamCommand.REBIND -> {
                cancelLingerTimer()
                doUnbind()
                doBind()
            }
            UpstreamCommand.SCHEDULE_UNBIND -> scheduleLingerTimer()
            UpstreamCommand.CANCEL_UNBIND -> cancelLingerTimer()
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

    /**
     * 最後一個客戶端走了，先等一下再拆線。
     *
     * 為什麼（2026-09-20 22:48 實機 log）：啟動器收到外掛的 `PACKAGE_REPLACED` 廣播會
     * `reconnect()`，也就是「兩條 binding 全部 unbind → 立刻重綁」。舊行為在這 40 毫秒
     * 之間把 Google app 的連線整個拆掉重建，於是那一次的 `windowAttached2` 可能剛好被送到
     * 一個馬上要作廢的 binder 上。等 [FeedFlags.LINGER_MILLIS] 之後再拆，啟動器回來時
     * 連線根本沒斷過（[UpstreamCommand.CANCEL_UNBIND]）。
     */
    private fun scheduleLingerTimer() {
        cancelLingerTimer()
        val delay = flags.lingerMillis
        FeedLog.i(FeedLog.UPSTREAM, "last client left; keeping the upstream for ${delay}ms")
        handler.postDelayed(lingerTimeout, delay)
    }

    private fun cancelLingerTimer() = handler.removeCallbacks(lingerTimeout)

    private val lingerTimeout = Runnable {
        FeedLog.i(FeedLog.UPSTREAM, "linger expired; releasing the upstream for real")
        apply(machine.onLingerExpired())
    }

    private fun doBind() {
        if (bound) return
        val intent = buildUpstreamIntent()
        FeedLog.i(
            FeedLog.UPSTREAM,
            "binding: ${intent.action} data=${intent.data} pkg=${intent.`package`} " +
                "flags=${AttachPayload.hex(flags.upstreamBindFlags)} | switches: ${flags.describe()}",
        )
        if (!isGoogleAppAvailable()) {
            FeedLog.e(
                FeedLog.UPSTREAM,
                "${OverlayProtocol.GOOGLE_APP_PACKAGE} is missing or disabled; feed will stay empty",
            )
            apply(machine.onBindFailed())
            return
        }
        val ok = try {
            context.bindService(intent, connection, flags.upstreamBindFlags)
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

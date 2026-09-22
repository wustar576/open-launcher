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
 *
 * 綁幾條線由 [FeedFlags.upstreamBindFlagsList] 決定：平常一條，
 * `OLFeedDualBind` 打開時兩條（0x41 + 0x21，模仿真正啟動器的形狀）。
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

    /** 這一輪綁定用的連線（一條或兩條）。 */
    private var connections: List<UpstreamConnection> = emptyList()

    /**
     * 綁定的「第幾輪」。每次 [doBind] 加一，連線把建立時的世代記在身上，
     * 回呼進來時世代對不上就直接忽略——拆線之後才姍姍來遲的回呼不該動到新的連線狀態，
     * 兩條連線同時回報 binding died 時也只會有一條真的觸發重綁。
     */
    private var bindGeneration = 0

    val state: UpstreamState get() = machine.state

    /** 目前拿到的 Google app overlay binder，尚未連上時為 null。 */
    val binder: IBinder? get() = upstreamBinder

    val component: ComponentName? get() = boundComponent

    /**
     * 一條對 Google app 的連線。
     *
     * 兩條連線會拿到**同一個** binder（Android 對同一個 intent 只呼叫一次 `onBind`），
     * 所以 [onServiceConnected] 只認第一條送到的那個；萬一真的不一樣，那是個值得記一筆
     * warning 的大事（代表 Google app 依 flags 給了不同的實例）。
     */
    private inner class UpstreamConnection(
        private val index: Int,
        val bindFlags: Int,
        private val generation: Int,
    ) : ServiceConnection {

        /** 這條線現在是不是連著的。只有最後一條斷掉才算「上游沒了」。 */
        var isConnected = false
            private set

        val label: String get() = "conn#$index(${AttachPayload.hex(bindFlags)})"

        private fun onMainThread(what: String, block: () -> Unit) {
            handler.post {
                if (generation != bindGeneration) {
                    FeedLog.d(FeedLog.UPSTREAM, "$label $what from an old binding (gen $generation), ignoring")
                    return@post
                }
                block()
            }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            onMainThread("onServiceConnected") {
                isConnected = true
                val existing = upstreamBinder
                if (existing != null) {
                    if (existing === service) {
                        FeedLog.i(
                            FeedLog.UPSTREAM,
                            "$label connected to $name with the same binder; the first one stands",
                        )
                    } else {
                        FeedLog.w(
                            FeedLog.UPSTREAM,
                            "$label connected to $name with a DIFFERENT binder " +
                                "($service, keeping $existing); only the first one is used",
                        )
                    }
                    return@onMainThread
                }
                FeedLog.i(FeedLog.UPSTREAM, "connected to $name (binder=$service) via $label")
                boundComponent = name
                upstreamBinder = service
                apply(machine.onUpstreamConnected())
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            onMainThread("onServiceDisconnected") {
                isConnected = false
                FeedLog.w(FeedLog.UPSTREAM, "$label disconnected from $name")
                if (connections.any { it.isConnected }) {
                    // 還有另一條連著：對客戶端而言上游根本沒斷。
                    FeedLog.i(FeedLog.UPSTREAM, "another connection is still up; keeping the binder")
                    return@onMainThread
                }
                upstreamBinder = null
                apply(machine.onUpstreamDisconnected())
            }
        }

        override fun onBindingDied(name: ComponentName) {
            onMainThread("onBindingDied") {
                isConnected = false
                FeedLog.w(FeedLog.UPSTREAM, "$label binding died for $name, rebinding")
                upstreamBinder = null
                // REBIND 會把世代加一，另一條線的 onBindingDied 因此會被當成過期回呼忽略。
                apply(machine.onBindingDied())
            }
        }

        override fun onNullBinding(name: ComponentName) {
            onMainThread("onNullBinding") {
                isConnected = false
                FeedLog.e(
                    FeedLog.UPSTREAM,
                    "$label: $name returned a null binder; is this build debuggable?",
                )
                upstreamBinder = null
                apply(machine.onBindFailed())
            }
        }

        override fun toString(): String = label
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
        val bindFlagsList = flags.upstreamBindFlagsList
        FeedLog.i(
            FeedLog.UPSTREAM,
            "binding: ${intent.action} data=${intent.data} pkg=${intent.`package`} " +
                "bindFlags=${flags.describeBindFlags()} " +
                "connections=${bindFlagsList.size} | switches: ${flags.describe()}",
        )
        if (flags.bindImportantIgnored) {
            FeedLog.i(
                FeedLog.UPSTREAM,
                "${FeedFlags.TAG_DUAL_BIND} wins over ${FeedFlags.TAG_BIND_IMPORTANT}: " +
                    "the two connections already carry BIND_IMPORTANT on the first one",
            )
        }
        if (!isGoogleAppAvailable()) {
            FeedLog.e(
                FeedLog.UPSTREAM,
                "${OverlayProtocol.GOOGLE_APP_PACKAGE} is missing or disabled; feed will stay empty",
            )
            apply(machine.onBindFailed())
            return
        }
        bindGeneration++
        val established = mutableListOf<UpstreamConnection>()
        bindFlagsList.forEachIndexed { index, bindFlags ->
            val connection = UpstreamConnection(index + 1, bindFlags, bindGeneration)
            val ok = try {
                context.bindService(intent, connection, bindFlags)
            } catch (t: Throwable) {
                FeedLog.e(FeedLog.UPSTREAM, "bindService threw for ${connection.label}", t)
                false
            }
            if (ok) {
                established += connection
                FeedLog.i(
                    FeedLog.UPSTREAM,
                    "${connection.label}: bindService returned true, waiting for onServiceConnected",
                )
            } else {
                FeedLog.e(FeedLog.UPSTREAM, "${connection.label}: bindService returned false")
                // 依 Android 文件，回傳 false 仍必須 unbind 才不會洩漏。
                runCatching { context.unbindService(connection) }
            }
        }
        connections = established
        if (established.isEmpty()) {
            apply(machine.onBindFailed())
            return
        }
        bound = true
        if (established.size < bindFlagsList.size) {
            // 第一條成功就還有 binder 可用，降級成單線繼續跑，但要在 log 裡講清楚。
            FeedLog.w(
                FeedLog.UPSTREAM,
                "only ${established.size}/${bindFlagsList.size} connection(s) were established",
            )
        }
    }

    private fun doUnbind() {
        if (!bound) return
        FeedLog.i(
            FeedLog.UPSTREAM,
            "unbinding ${connections.size} connection(s) from ${OverlayProtocol.GOOGLE_APP_PACKAGE}",
        )
        connections.forEach { connection ->
            runCatching { context.unbindService(connection) }
                .onFailure {
                    FeedLog.w(FeedLog.UPSTREAM, "unbindService failed for ${connection.label}", it)
                }
        }
        connections = emptyList()
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

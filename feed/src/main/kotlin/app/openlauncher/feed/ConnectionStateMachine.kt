/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

/** 外掛對 Google app 那一段連線的狀態。 */
enum class UpstreamState {
    /** 沒有客戶端，也沒有綁定。 */
    IDLE,

    /** 已呼叫 bindService，但還沒拿到 binder（或剛斷線、等待系統重連）。 */
    BINDING,

    /** 已取得 Google app 的 overlay binder。 */
    CONNECTED,

    /**
     * 最後一個客戶端走了，但**還沒真的 unbind**：等一小段時間，看它會不會馬上回來。
     *
     * 見 [FeedFlags.LINGER_MILLIS]：啟動器換提供者或收到套件更新廣播時會「全部 unbind →
     * 立刻重綁」，兩次之間只差幾十毫秒。
     */
    LINGERING,

    /** Google app 不存在／被停用／bindService 回傳 false。 */
    UNAVAILABLE,
}

/** 狀態機要求呼叫端執行的動作。 */
enum class UpstreamCommand {
    BIND,
    UNBIND,

    /** 先 unbind 再 bind（binding died 時使用）。 */
    REBIND,

    /** 過 [FeedFlags.LINGER_MILLIS] 之後呼叫 [ConnectionStateMachine.onLingerExpired]。 */
    SCHEDULE_UNBIND,

    /** 取消上面那個預約（客戶端在緩衝期內回來了）。 */
    CANCEL_UNBIND,
}

/**
 * 狀態機的輸出：一個要執行的動作，加上要通知哪些客戶端。
 *
 * 通知的順序是固定的，呼叫端必須照做：
 * [notifyReleasing]（動作**之前**）→ 動作 → [notifyConnected] / [notifyDisconnected] /
 * [notifyUnavailable]（動作之後）。
 */
data class ConnectionEffect<C : Any>(
    val command: UpstreamCommand? = null,

    /**
     * 「上游即將被放掉，binder 現在還活著」。
     *
     * 呼叫端必須在執行 [UpstreamCommand.UNBIND] **之前**通知這些客戶端，它們才來得及把
     * 啟動器的 window token 用 `windowDetached` 還給 Google app（見
     * [LauncherOverlayProxy.onUpstreamReleasing]）——unbind 之後就沒有 binder 可以送了。
     */
    val notifyReleasing: List<C> = emptyList(),
    val notifyConnected: List<C> = emptyList(),
    val notifyDisconnected: List<C> = emptyList(),
    val notifyUnavailable: List<C> = emptyList(),
)

/**
 * 「外掛 ←→ Google app」這一段連線的純邏輯，完全不碰 Android API，可直接單元測試。
 *
 * 設計重點：
 * - 啟動器會對同一個 service 綁兩次（不同 flags），因此會有兩個客戶端 callback；
 *   但對 Google app 只需要綁一次，binder 同時交給兩個客戶端。
 * - 客戶端全部離開後**不會立刻** unbind，而是進入 [UpstreamState.LINGERING] 緩衝一小段
 *   時間。2026-09-20 22:48 的實機 log 顯示啟動器會在 40 毫秒內「全部 unbind → 重綁」，
 *   舊行為會在這中間把上游連線拆掉重建，`windowAttached2` 就有機會被送到一個馬上要作廢的
 *   binder 上。
 * - Google app 不存在時進入 [UpstreamState.UNAVAILABLE]；之後只要有新的客戶端加入就再試一次
 *   （使用者可能剛把 Google app 啟用回來）。
 */
class ConnectionStateMachine<C : Any> {

    private val _clients = LinkedHashSet<C>()

    /** 進入 LINGERING 時離開的那些客戶端，緩衝期結束才會收到 disconnected。 */
    private var lingeringClients: List<C> = emptyList()

    /** LINGERING 之前的狀態（CONNECTED 或 BINDING），客戶端回來時要還原成它。 */
    private var resumeState: UpstreamState = UpstreamState.BINDING

    var state: UpstreamState = UpstreamState.IDLE
        private set

    val clients: Set<C> get() = _clients.toSet()

    /** 新的客戶端要用 overlay。 */
    fun attach(client: C): ConnectionEffect<C> {
        val isNew = _clients.add(client)
        return when (state) {
            UpstreamState.CONNECTED ->
                ConnectionEffect(notifyConnected = listOf(client))

            UpstreamState.BINDING ->
                // 已經在綁了，等 onServiceConnected 一起通知。
                ConnectionEffect()

            UpstreamState.LINGERING -> {
                // 客戶端在緩衝期內回來了：連線根本沒斷過，取消預約就好。
                state = resumeState
                lingeringClients = emptyList()
                ConnectionEffect(
                    command = UpstreamCommand.CANCEL_UNBIND,
                    notifyConnected =
                        if (state == UpstreamState.CONNECTED) listOf(client) else emptyList(),
                )
            }

            UpstreamState.IDLE -> {
                state = UpstreamState.BINDING
                ConnectionEffect(command = UpstreamCommand.BIND)
            }

            UpstreamState.UNAVAILABLE ->
                if (isNew) {
                    // 重試一次：Google app 可能剛被安裝或啟用。
                    state = UpstreamState.BINDING
                    ConnectionEffect(command = UpstreamCommand.BIND)
                } else {
                    ConnectionEffect(notifyUnavailable = listOf(client))
                }
        }
    }

    /**
     * 客戶端離開（unbind、process 死亡、或 service onUnbind）。
     *
     * 還有別的客戶端在 → 離開的那個收到 disconnected（對它而言上游確實沒了；少了這一步，
     * 它會繼續抱著一個已經 unbind 的 binder，之後每一筆轉送都默默掉進黑洞）。
     *
     * 最後一個客戶端離開 → **先不要拆線**，進入 [UpstreamState.LINGERING]，請呼叫端在
     * [FeedFlags.LINGER_MILLIS] 之後呼叫 [onLingerExpired]。緩衝期內沒有人收到
     * disconnected，因為連線還在、window session 也還在。
     */
    fun detach(client: C): ConnectionEffect<C> {
        if (!_clients.remove(client)) return ConnectionEffect()
        val leaving = listOf(client)
        if (_clients.isNotEmpty()) return ConnectionEffect(notifyDisconnected = leaving)
        return when (state) {
            UpstreamState.IDLE, UpstreamState.UNAVAILABLE -> {
                state = UpstreamState.IDLE
                ConnectionEffect(notifyDisconnected = leaving)
            }

            UpstreamState.LINGERING -> ConnectionEffect()

            UpstreamState.BINDING, UpstreamState.CONNECTED -> {
                resumeState = state
                state = UpstreamState.LINGERING
                lingeringClients = leaving
                ConnectionEffect(command = UpstreamCommand.SCHEDULE_UNBIND)
            }
        }
    }

    /** 緩衝期到了，沒有人回來：真的放掉上游。 */
    fun onLingerExpired(): ConnectionEffect<C> {
        if (state != UpstreamState.LINGERING) return ConnectionEffect()
        val leaving = lingeringClients
        lingeringClients = emptyList()
        val hadBinder = resumeState == UpstreamState.CONNECTED
        state = UpstreamState.IDLE
        return ConnectionEffect(
            command = UpstreamCommand.UNBIND,
            // binder 還活著，這是最後一次能把 window token 還回去的機會。
            notifyReleasing = if (hadBinder) leaving else emptyList(),
            notifyDisconnected = leaving,
        )
    }

    /**
     * 全部客戶端一次移除（service.onDestroy）。
     *
     * 與 [detach] 不同，這裡**不緩衝**：service 本身要沒了，沒有「等它回來」這回事。
     */
    fun detachAll(): ConnectionEffect<C> {
        val leaving = if (_clients.isEmpty()) lingeringClients else _clients.toList()
        if (leaving.isEmpty()) {
            return ConnectionEffect()
        }
        _clients.clear()
        lingeringClients = emptyList()
        val effectiveState = if (state == UpstreamState.LINGERING) resumeState else state
        val wasBound =
            effectiveState == UpstreamState.BINDING || effectiveState == UpstreamState.CONNECTED
        state = UpstreamState.IDLE
        return ConnectionEffect(
            command = if (wasBound) UpstreamCommand.UNBIND else null,
            notifyReleasing = if (effectiveState == UpstreamState.CONNECTED) leaving else emptyList(),
            notifyDisconnected = leaving,
        )
    }

    /** 已取得 Google app 的 binder。 */
    fun onUpstreamConnected(): ConnectionEffect<C> {
        if (state == UpstreamState.LINGERING) {
            // 緩衝期間才連上：沒有客戶端可以通知，但緩衝結束時要知道有 binder 要還。
            resumeState = UpstreamState.CONNECTED
            return ConnectionEffect()
        }
        state = UpstreamState.CONNECTED
        return ConnectionEffect(notifyConnected = _clients.toList())
    }

    /**
     * Google app 的程序掛了。系統仍保留 binding，之後會自動 onServiceConnected，
     * 所以狀態退回 BINDING 而不是 IDLE。
     */
    fun onUpstreamDisconnected(): ConnectionEffect<C> {
        if (state == UpstreamState.LINGERING) {
            resumeState = UpstreamState.BINDING
            return ConnectionEffect()
        }
        if (state != UpstreamState.CONNECTED && state != UpstreamState.BINDING) {
            return ConnectionEffect()
        }
        state = if (_clients.isEmpty()) UpstreamState.IDLE else UpstreamState.BINDING
        return ConnectionEffect(notifyDisconnected = _clients.toList())
    }

    /** bindService 回傳 false，或 Google app 不存在／被停用。 */
    fun onBindFailed(): ConnectionEffect<C> {
        val effectiveState = if (state == UpstreamState.LINGERING) resumeState else state
        val hadBinding =
            effectiveState == UpstreamState.BINDING || effectiveState == UpstreamState.CONNECTED
        val leaving = _clients.toList() + lingeringClients
        lingeringClients = emptyList()
        state = UpstreamState.UNAVAILABLE
        return ConnectionEffect(
            // bindService 即使回傳 false 也必須 unbind，否則會洩漏 ServiceConnection。
            command = if (hadBinding) UpstreamCommand.UNBIND else null,
            notifyUnavailable = leaving,
        )
    }

    /** binding 永久失效（Google app 被移除／更新），需要重綁。 */
    fun onBindingDied(): ConnectionEffect<C> {
        lingeringClients = emptyList()
        if (_clients.isEmpty()) {
            state = UpstreamState.IDLE
            return ConnectionEffect(command = UpstreamCommand.UNBIND)
        }
        state = UpstreamState.BINDING
        return ConnectionEffect(
            command = UpstreamCommand.REBIND,
            notifyDisconnected = _clients.toList(),
        )
    }
}

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

    /** Google app 不存在／被停用／bindService 回傳 false。 */
    UNAVAILABLE,
}

/** 狀態機要求呼叫端執行的動作。 */
enum class UpstreamCommand {
    BIND,
    UNBIND,

    /** 先 unbind 再 bind（binding died 時使用）。 */
    REBIND,
}

/**
 * 狀態機的輸出：一個要執行的動作，加上要通知哪些客戶端。
 */
data class ConnectionEffect<C : Any>(
    val command: UpstreamCommand? = null,
    val notifyConnected: List<C> = emptyList(),
    val notifyDisconnected: List<C> = emptyList(),
    val notifyUnavailable: List<C> = emptyList(),
)

/**
 * 「外掛 ←→ Google app」這一段連線的純邏輯，完全不碰 Android API，可直接單元測試。
 *
 * 設計重點：
 * - 啟動器會對同一個 service 綁兩次（不同 flags），因此會有兩個客戶端 callback；
 *   但對 Google app 只需要綁一次，binder 同時交給兩個 callback。
 * - 客戶端全部離開後才 unbind，避免在 onStop／onStart 之間來回重綁。
 * - Google app 不存在時進入 UNAVAILABLE；之後只要有新的客戶端加入就再試一次
 *   （使用者可能剛把 Google app 啟用回來）。
 */
class ConnectionStateMachine<C : Any> {

    private val _clients = LinkedHashSet<C>()

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
     * 離開的客戶端一律收到 [Listener.onUpstreamDisconnected]：對它而言上游確實沒了。
     * 少了這一步，客戶端會繼續抱著一個已經 unbind 的 binder，之後每一筆轉送都默默掉進
     * 黑洞（見 `LauncherOverlayProxy.remote`）。
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

            UpstreamState.BINDING, UpstreamState.CONNECTED -> {
                state = UpstreamState.IDLE
                ConnectionEffect(command = UpstreamCommand.UNBIND, notifyDisconnected = leaving)
            }
        }
    }

    /** 全部客戶端一次移除（service.onUnbind）。 */
    fun detachAll(): ConnectionEffect<C> {
        if (_clients.isEmpty()) {
            return ConnectionEffect()
        }
        val leaving = _clients.toList()
        _clients.clear()
        val wasBound = state == UpstreamState.BINDING || state == UpstreamState.CONNECTED
        state = UpstreamState.IDLE
        return ConnectionEffect(
            command = if (wasBound) UpstreamCommand.UNBIND else null,
            notifyDisconnected = leaving,
        )
    }

    /** 已取得 Google app 的 binder。 */
    fun onUpstreamConnected(): ConnectionEffect<C> {
        state = UpstreamState.CONNECTED
        return ConnectionEffect(notifyConnected = _clients.toList())
    }

    /**
     * Google app 的程序掛了。系統仍保留 binding，之後會自動 onServiceConnected，
     * 所以狀態退回 BINDING 而不是 IDLE。
     */
    fun onUpstreamDisconnected(): ConnectionEffect<C> {
        if (state != UpstreamState.CONNECTED && state != UpstreamState.BINDING) {
            return ConnectionEffect()
        }
        state = if (_clients.isEmpty()) UpstreamState.IDLE else UpstreamState.BINDING
        return ConnectionEffect(notifyDisconnected = _clients.toList())
    }

    /** bindService 回傳 false，或 Google app 不存在／被停用。 */
    fun onBindFailed(): ConnectionEffect<C> {
        val hadBinding = state == UpstreamState.BINDING || state == UpstreamState.CONNECTED
        state = UpstreamState.UNAVAILABLE
        return ConnectionEffect(
            // bindService 即使回傳 false 也必須 unbind，否則會洩漏 ServiceConnection。
            command = if (hadBinding) UpstreamCommand.UNBIND else null,
            notifyUnavailable = _clients.toList(),
        )
    }

    /** binding 永久失效（Google app 被移除／更新），需要重綁。 */
    fun onBindingDied(): ConnectionEffect<C> {
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

/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

/**
 * 「啟動器的視窗目前附著在哪一個上游 binder 上」這件事的純邏輯，完全不碰 Android API。
 *
 * 為什麼需要它：`windowAttached2` 帶的是**啟動器的 window token**，Google app 會把
 * Discover 視窗掛在那個 token 底下。同一個 token 同時被兩個 session 認領，Google app 就
 * 不會回報 `overlayStatusChanged`，新聞頁變成一片空白。因此必須精準掌握三件事：
 *
 * 1. 上游還沒連上時收到的 attach 要暫存，連上後補送一次（**只送一次**）；
 * 2. 已經送給同一個 binder 的 attach 不可以再送第二次（避免重複認領同一個 token）；
 * 3. 綁定結束（啟動器切換提供者、unbind）時要記得「已經沒有附著」，並且讓呼叫端有機會
 *    送出 `windowDetached`，把 token 還給啟動器。
 *
 * @param A attach 的內容（`windowAttached` 或 `windowAttached2` 的參數）
 * @param R 上游 binder 的型別；以 identity（===）比較
 */
class WindowAttachState<A : Any, R : Any> {

    private var attachment: A? = null
    private var deliveredTo: R? = null

    /** 目前有效的 attach，沒有則為 null。 */
    val pending: A? get() = attachment

    /** 這個 attach 是否已經送到某個上游 binder。 */
    val isDelivered: Boolean get() = deliveredTo != null

    /**
     * 啟動器送來新的 attach。
     *
     * @param remote 現在手上的上游 binder，null 代表還沒連上。
     * @return true 代表應該立刻往上游送；false 代表先暫存，等 [onUpstreamConnected]。
     */
    fun onAttach(attach: A, remote: R?): Boolean {
        attachment = attach
        deliveredTo = remote
        return remote != null
    }

    /**
     * 啟動器送來 `windowDetached`，或整個綁定結束。
     *
     * @return 被釋放掉的 attach；原本就沒有附著時回傳 null（呼叫端就不必多送一次
     *   `windowDetached`）。
     */
    fun onDetach(): A? {
        val released = attachment
        attachment = null
        deliveredTo = null
        return released
    }

    /**
     * 上游連上（或 Google app 重啟後自動重連）。
     *
     * @return 需要補送的 attach；沒有待處理的 attach、或同一個 binder 已經收過，回傳 null。
     */
    fun onUpstreamConnected(remote: R): A? {
        val attach = attachment ?: return null
        if (deliveredTo === remote) return null
        deliveredTo = remote
        return attach
    }

    /**
     * 上游斷線／unbind。attach 的內容留著（啟動器的視窗還在），但「已送達」的標記要清掉，
     * 下次連上時才會重新補送。
     */
    fun onUpstreamLost() {
        deliveredTo = null
    }
}

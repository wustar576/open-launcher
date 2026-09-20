/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 這些規則直接對應 2026-09-20 的實機 bug：切換新聞頁提供者之後 Google app 永遠不回報
 * `overlayStatusChanged`，因為啟動器的 window token 還被前一個 session 佔著。
 */
class WindowAttachStateTest {

    private class Attach(val name: String)
    private class Remote(val name: String)

    private val state = WindowAttachState<Attach, Remote>()

    private val attachA = Attach("windowAttached2#1")
    private val attachB = Attach("windowAttached2#2")
    private val remote1 = Remote("google#1")
    private val remote2 = Remote("google#2")

    @Test
    fun `nothing is attached to begin with`() {
        assertNull(state.pending)
        assertFalse(state.isDelivered)
        assertNull(state.onDetach())
    }

    @Test
    fun `an attach that arrives with the upstream up is forwarded straight away`() {
        assertTrue(state.onAttach(attachA, remote1))
        assertSame(attachA, state.pending)
        assertTrue(state.isDelivered)
    }

    @Test
    fun `an attach that arrives before the upstream is replayed exactly once`() {
        assertFalse(state.onAttach(attachA, null))
        assertFalse(state.isDelivered)

        assertSame(attachA, state.onUpstreamConnected(remote1))
        // 同一個 binder 不可以再收到第二份 attach：同一個 window token 被認領兩次，
        // Google app 就不會回報狀態。
        assertNull(state.onUpstreamConnected(remote1))
    }

    @Test
    fun `an attach already delivered is not replayed to the same binder`() {
        state.onAttach(attachA, remote1)
        assertNull(state.onUpstreamConnected(remote1))
    }

    @Test
    fun `a new upstream binder gets the attach replayed`() {
        state.onAttach(attachA, remote1)
        assertSame(attachA, state.onUpstreamConnected(remote2))
    }

    @Test
    fun `losing the upstream keeps the window but forces a replay on reconnect`() {
        state.onAttach(attachA, remote1)
        state.onUpstreamLost()
        assertSame(attachA, state.pending)
        assertFalse(state.isDelivered)
        // Google app 重啟後系統送回同一個 component，可能是同一個 binder 物件也可能不是；
        // 兩種情況都必須補送。
        assertSame(attachA, state.onUpstreamConnected(remote1))
    }

    @Test
    fun `detach reports what was released so the caller can send windowDetached`() {
        state.onAttach(attachA, remote1)
        assertSame(attachA, state.onDetach())
        assertNull(state.pending)
        // 第二次 detach 不可以再叫呼叫端送一次 windowDetached。
        assertNull(state.onDetach())
    }

    @Test
    fun `after a detach a reconnect replays nothing`() {
        state.onAttach(attachA, remote1)
        state.onDetach()
        assertNull(state.onUpstreamConnected(remote2))
    }

    @Test
    fun `a newer attach replaces the previous one`() {
        state.onAttach(attachA, null)
        state.onAttach(attachB, null)
        assertSame(attachB, state.onUpstreamConnected(remote1))
        assertSame(attachB, state.pending)
    }
}

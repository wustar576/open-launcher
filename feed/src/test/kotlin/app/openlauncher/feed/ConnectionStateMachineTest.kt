/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionStateMachineTest {

    private val machine = ConnectionStateMachine<String>()

    private val clientA = "launcherClientService"
    private val clientB = "launcherBaseService"

    @Test
    fun `starts idle`() {
        assertEquals(UpstreamState.IDLE, machine.state)
        assertTrue(machine.clients.isEmpty())
    }

    @Test
    fun `first client triggers a single bind`() {
        val effect = machine.attach(clientA)
        assertEquals(UpstreamCommand.BIND, effect.command)
        assertEquals(UpstreamState.BINDING, machine.state)
        assertTrue(effect.notifyConnected.isEmpty())
    }

    @Test
    fun `connecting notifies every attached client`() {
        machine.attach(clientA)
        machine.attach(clientB)
        val effect = machine.onUpstreamConnected()
        assertEquals(UpstreamState.CONNECTED, machine.state)
        assertEquals(listOf(clientA, clientB), effect.notifyConnected)
    }

    @Test
    fun `the launcher binding twice only binds upstream once`() {
        assertEquals(UpstreamCommand.BIND, machine.attach(clientA).command)
        // 第二次綁定（flags 不同、callback 不同）不可以再 bind 一次 Google app。
        assertNull(machine.attach(clientB).command)
        assertEquals(2, machine.clients.size)
    }

    @Test
    fun `a client attaching after the upstream is up is notified immediately`() {
        machine.attach(clientA)
        machine.onUpstreamConnected()
        val effect = machine.attach(clientB)
        assertNull(effect.command)
        assertEquals(listOf(clientB), effect.notifyConnected)
    }

    @Test
    fun `attaching the same client twice is harmless`() {
        machine.attach(clientA)
        machine.onUpstreamConnected()
        val effect = machine.attach(clientA)
        assertNull(effect.command)
        assertEquals(listOf(clientA), effect.notifyConnected)
        assertEquals(1, machine.clients.size)
    }

    @Test
    fun `unbinding happens only when the last client goes away`() {
        machine.attach(clientA)
        machine.attach(clientB)
        machine.onUpstreamConnected()

        assertNull(machine.detach(clientA).command)
        assertEquals(UpstreamState.CONNECTED, machine.state)

        // 最後一個客戶端走了只是「預約」拆線，緩衝期過了才真的 unbind。
        val last = machine.detach(clientB)
        assertEquals(UpstreamCommand.SCHEDULE_UNBIND, last.command)
        assertEquals(UpstreamState.LINGERING, machine.state)

        val expired = machine.onLingerExpired()
        assertEquals(UpstreamCommand.UNBIND, expired.command)
        assertEquals(UpstreamState.IDLE, machine.state)
    }

    @Test
    fun `detaching an unknown client does nothing`() {
        machine.attach(clientA)
        val effect = machine.detach("someone else")
        assertNull(effect.command)
        assertTrue(effect.notifyDisconnected.isEmpty())
        assertEquals(1, machine.clients.size)
    }

    @Test
    fun `a detached client is told the upstream is gone - after the linger`() {
        machine.attach(clientA)
        machine.onUpstreamConnected()

        // 緩衝期內什麼都還沒發生：連線還在，所以也還沒有人需要被通知。
        val leaving = machine.detach(clientA)
        assertEquals(UpstreamCommand.SCHEDULE_UNBIND, leaving.command)
        assertTrue(leaving.notifyDisconnected.isEmpty())
        assertTrue(leaving.notifyReleasing.isEmpty())

        val expired = machine.onLingerExpired()
        assertEquals(UpstreamCommand.UNBIND, expired.command)
        // 先還 window token（binder 還活著），再通知「上游沒了」。
        assertEquals(listOf(clientA), expired.notifyReleasing)
        // 沒有這一步，客戶端會抱著一個已經 unbind 的 binder 繼續轉送。
        assertEquals(listOf(clientA), expired.notifyDisconnected)
    }

    @Test
    fun `a client that comes back inside the linger keeps the very same connection`() {
        machine.attach(clientA)
        machine.attach(clientB)
        machine.onUpstreamConnected()
        machine.detach(clientA)

        // 啟動器的兩條 binding 一起消失（service.onUnbind），40 毫秒後又綁回來。
        assertEquals(UpstreamCommand.SCHEDULE_UNBIND, machine.detach(clientB).command)

        val back = machine.attach(clientA)
        assertEquals(UpstreamCommand.CANCEL_UNBIND, back.command)
        assertEquals(UpstreamState.CONNECTED, machine.state)
        assertEquals(listOf(clientA), back.notifyConnected)

        // 緩衝已經取消，之後就算計時器誤觸也不可以拆線。
        assertNull(machine.onLingerExpired().command)
        assertEquals(UpstreamState.CONNECTED, machine.state)
    }

    @Test
    fun `coming back inside the linger before the bind completed stays binding`() {
        machine.attach(clientA)
        machine.detach(clientA)
        assertEquals(UpstreamState.LINGERING, machine.state)

        val back = machine.attach(clientA)
        assertEquals(UpstreamCommand.CANCEL_UNBIND, back.command)
        assertEquals(UpstreamState.BINDING, machine.state)
        // 還沒有 binder，不能謊報 connected。
        assertTrue(back.notifyConnected.isEmpty())

        assertEquals(listOf(clientA), machine.onUpstreamConnected().notifyConnected)
    }

    @Test
    fun `connecting during the linger still releases the window when it expires`() {
        machine.attach(clientA)
        machine.detach(clientA)
        // bindService 的結果在緩衝期內才回來。
        assertTrue(machine.onUpstreamConnected().notifyConnected.isEmpty())
        assertEquals(UpstreamState.LINGERING, machine.state)

        val expired = machine.onLingerExpired()
        assertEquals(UpstreamCommand.UNBIND, expired.command)
        assertEquals(listOf(clientA), expired.notifyReleasing)
    }

    @Test
    fun `a linger that expires without a binder has nothing to release`() {
        machine.attach(clientA)
        machine.detach(clientA)
        val expired = machine.onLingerExpired()
        assertEquals(UpstreamCommand.UNBIND, expired.command)
        assertTrue(expired.notifyReleasing.isEmpty())
        assertEquals(listOf(clientA), expired.notifyDisconnected)
    }

    @Test
    fun `onLingerExpired outside the linger does nothing`() {
        assertNull(machine.onLingerExpired().command)
        machine.attach(clientA)
        machine.onUpstreamConnected()
        assertNull(machine.onLingerExpired().command)
        assertEquals(UpstreamState.CONNECTED, machine.state)
    }

    @Test
    fun `a detached client is told even while others stay attached`() {
        machine.attach(clientA)
        machine.attach(clientB)
        machine.onUpstreamConnected()
        val effect = machine.detach(clientA)
        assertNull(effect.command)
        assertEquals(listOf(clientA), effect.notifyDisconnected)
        assertEquals(UpstreamState.CONNECTED, machine.state)
    }

    @Test
    fun `detachAll tells every client the upstream is gone`() {
        machine.attach(clientA)
        machine.attach(clientB)
        machine.onUpstreamConnected()
        val effect = machine.detachAll()
        assertEquals(listOf(clientA, clientB), effect.notifyDisconnected)
        assertTrue(machine.detachAll().notifyDisconnected.isEmpty())
    }

    @Test
    fun `upstream death keeps the binding and notifies clients`() {
        machine.attach(clientA)
        machine.attach(clientB)
        machine.onUpstreamConnected()

        val effect = machine.onUpstreamDisconnected()
        assertEquals(listOf(clientA, clientB), effect.notifyDisconnected)
        assertNull(effect.command)
        // Android 會保留 binding 並自動重連，所以退回 BINDING 而不是 IDLE。
        assertEquals(UpstreamState.BINDING, machine.state)

        val back = machine.onUpstreamConnected()
        assertEquals(UpstreamState.CONNECTED, machine.state)
        assertEquals(listOf(clientA, clientB), back.notifyConnected)
    }

    @Test
    fun `binding death forces a rebind`() {
        machine.attach(clientA)
        machine.onUpstreamConnected()
        val effect = machine.onBindingDied()
        assertEquals(UpstreamCommand.REBIND, effect.command)
        assertEquals(listOf(clientA), effect.notifyDisconnected)
        assertEquals(UpstreamState.BINDING, machine.state)
    }

    @Test
    fun `binding death without clients just unbinds`() {
        machine.attach(clientA)
        machine.onUpstreamConnected()
        machine.detach(clientA)
        val effect = machine.onBindingDied()
        assertEquals(UpstreamCommand.UNBIND, effect.command)
        assertEquals(UpstreamState.IDLE, machine.state)
    }

    @Test
    fun `google app absent marks the upstream unavailable`() {
        machine.attach(clientA)
        val effect = machine.onBindFailed()
        assertEquals(UpstreamState.UNAVAILABLE, machine.state)
        assertEquals(listOf(clientA), effect.notifyUnavailable)
        // bindService 即使失敗也要 unbind。
        assertEquals(UpstreamCommand.UNBIND, effect.command)
    }

    @Test
    fun `a new client retries after the google app was unavailable`() {
        machine.attach(clientA)
        machine.onBindFailed()
        val retry = machine.attach(clientB)
        assertEquals(UpstreamCommand.BIND, retry.command)
        assertEquals(UpstreamState.BINDING, machine.state)
    }

    @Test
    fun `a known client re-attaching while unavailable is told so`() {
        machine.attach(clientA)
        machine.onBindFailed()
        val again = machine.attach(clientA)
        assertNull(again.command)
        assertEquals(listOf(clientA), again.notifyUnavailable)
    }

    @Test
    fun `detaching the last client while unavailable does not unbind twice`() {
        machine.attach(clientA)
        machine.onBindFailed()
        val effect = machine.detach(clientA)
        assertNull(effect.command)
        assertEquals(UpstreamState.IDLE, machine.state)
    }

    @Test
    fun `detachAll releases the upstream in one go`() {
        machine.attach(clientA)
        machine.attach(clientB)
        machine.onUpstreamConnected()
        val effect = machine.detachAll()
        // service 正在銷毀，不緩衝：立刻還 token、立刻 unbind。
        assertEquals(UpstreamCommand.UNBIND, effect.command)
        assertEquals(listOf(clientA, clientB), effect.notifyReleasing)
        assertEquals(UpstreamState.IDLE, machine.state)
        assertTrue(machine.clients.isEmpty())
        // 再呼叫一次不可以重複 unbind。
        assertNull(machine.detachAll().command)
    }

    @Test
    fun `detachAll during the linger still releases the window`() {
        machine.attach(clientA)
        machine.onUpstreamConnected()
        machine.detach(clientA)
        assertEquals(UpstreamState.LINGERING, machine.state)

        val effect = machine.detachAll()
        assertEquals(UpstreamCommand.UNBIND, effect.command)
        assertEquals(listOf(clientA), effect.notifyReleasing)
        assertEquals(listOf(clientA), effect.notifyDisconnected)
        assertEquals(UpstreamState.IDLE, machine.state)
        // 緩衝期的計時器之後才觸發也不可以再拆一次。
        assertNull(machine.onLingerExpired().command)
    }

    @Test
    fun `full lifecycle - connect, disconnect, rebind`() {
        assertEquals(UpstreamCommand.BIND, machine.attach(clientA).command)
        machine.onUpstreamConnected()
        assertEquals(UpstreamState.CONNECTED, machine.state)

        machine.onUpstreamDisconnected()
        assertEquals(UpstreamState.BINDING, machine.state)

        machine.onUpstreamConnected()
        assertEquals(UpstreamState.CONNECTED, machine.state)

        assertEquals(UpstreamCommand.SCHEDULE_UNBIND, machine.detach(clientA).command)
        assertEquals(UpstreamCommand.UNBIND, machine.onLingerExpired().command)
        assertEquals(UpstreamState.IDLE, machine.state)

        // 啟動器 onStart 之後又回來：要能重新綁。
        assertEquals(UpstreamCommand.BIND, machine.attach(clientA).command)
    }
}

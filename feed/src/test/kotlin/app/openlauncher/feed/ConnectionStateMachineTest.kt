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

        val last = machine.detach(clientB)
        assertEquals(UpstreamCommand.UNBIND, last.command)
        assertEquals(UpstreamState.IDLE, machine.state)
    }

    @Test
    fun `detaching an unknown client does nothing`() {
        machine.attach(clientA)
        val effect = machine.detach("someone else")
        assertNull(effect.command)
        assertEquals(1, machine.clients.size)
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
        assertEquals(UpstreamCommand.UNBIND, effect.command)
        assertEquals(UpstreamState.IDLE, machine.state)
        assertTrue(machine.clients.isEmpty())
        // 再呼叫一次不可以重複 unbind。
        assertNull(machine.detachAll().command)
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

        assertEquals(UpstreamCommand.UNBIND, machine.detach(clientA).command)
        assertEquals(UpstreamState.IDLE, machine.state)

        // 啟動器 onStart 之後又回來：要能重新綁。
        assertEquals(UpstreamCommand.BIND, machine.attach(clientA).command)
    }
}

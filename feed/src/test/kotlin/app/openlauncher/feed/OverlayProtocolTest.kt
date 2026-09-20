/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OverlayProtocolTest {

    @Test
    fun `builds the exact uri the overlay service expects`() {
        assertEquals(
            "app://app.openlauncher.feed:10234?v=7&cv=9",
            OverlayProtocol.buildUri("app.openlauncher.feed", 10234),
        )
    }

    @Test
    fun `uses the protocol defaults`() {
        val uri = OverlayProtocol.buildUri("app.openlauncher", 1000)
        assertEquals("app://app.openlauncher:1000?v=7&cv=9", uri)
        assertEquals(7, OverlayProtocol.API_VERSION)
        assertEquals(9, OverlayProtocol.CLIENT_VERSION)
    }

    @Test
    fun `versions can be overridden`() {
        assertEquals(
            "app://pkg:1?v=3&cv=4",
            OverlayProtocol.buildUri("pkg", 1, apiVersion = 3, clientVersion = 4),
        )
    }

    @Test
    fun `the cv parameter can be dropped entirely`() {
        // 實機觀察：另一家外掛綁同一個 service 用的是 app://<套件>:<uid>?v=9（沒有 cv）。
        assertEquals(
            "app://pkg:1?v=9",
            OverlayProtocol.buildUri("pkg", 1, apiVersion = 9, clientVersion = null),
        )
        assertEquals("pkg", OverlayProtocol.packageNameOf("app://pkg:1?v=9"))
        assertEquals(1, OverlayProtocol.uidOf("app://pkg:1?v=9"))
    }

    @Test
    fun `rejects nonsense input`() {
        assertThrows(IllegalArgumentException::class.java) { OverlayProtocol.buildUri("", 1) }
        assertThrows(IllegalArgumentException::class.java) { OverlayProtocol.buildUri("pkg", -1) }
    }

    @Test
    fun `round trips package name and uid`() {
        val uri = OverlayProtocol.buildUri("app.openlauncher.feed", 10234)
        assertEquals("app.openlauncher.feed", OverlayProtocol.packageNameOf(uri))
        assertEquals(10234, OverlayProtocol.uidOf(uri))
    }

    @Test
    fun `parses the probe form without uid or query`() {
        // FeedBridge.getAvailableProviders() 探測時只送 app://<套件名>
        assertEquals("app.openlauncher", OverlayProtocol.packageNameOf("app://app.openlauncher"))
        assertNull(OverlayProtocol.uidOf("app://app.openlauncher"))
    }

    @Test
    fun `parses a uri with a trailing path`() {
        assertEquals("app.openlauncher", OverlayProtocol.packageNameOf("app://app.openlauncher/x?v=7"))
    }

    @Test
    fun `rejects other schemes and malformed input`() {
        assertNull(OverlayProtocol.packageNameOf(null))
        assertNull(OverlayProtocol.packageNameOf(""))
        assertNull(OverlayProtocol.packageNameOf("http://app.openlauncher:1"))
        assertNull(OverlayProtocol.packageNameOf("app://"))
        assertNull(OverlayProtocol.uidOf("app://pkg:notanumber"))
        assertNull(OverlayProtocol.uidOf("app://pkg:-5"))
    }

    @Test
    fun `constants match the wire protocol`() {
        assertEquals("com.android.launcher3.WINDOW_OVERLAY", OverlayProtocol.ACTION_WINDOW_OVERLAY)
        assertEquals("app", OverlayProtocol.SCHEME)
        assertEquals("com.google.android.googlequicksearchbox", OverlayProtocol.GOOGLE_APP_PACKAGE)
    }
}

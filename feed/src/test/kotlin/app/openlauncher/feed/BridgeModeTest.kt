/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

import org.junit.Assert.assertEquals
import org.junit.Test

class BridgeModeTest {

    @Test
    fun `defaults to the bridge design when the meta-data is missing`() {
        assertEquals(BridgeMode.BRIDGE, BridgeMode.fromManifestValue(null))
        assertEquals(BridgeMode.BRIDGE, BridgeMode.DEFAULT)
    }

    @Test
    fun `reads both designs from the manifest`() {
        assertEquals(BridgeMode.BRIDGE, BridgeMode.fromManifestValue("bridge"))
        assertEquals(BridgeMode.OVERLAY_PROXY, BridgeMode.fromManifestValue("proxy"))
    }

    @Test
    fun `tolerates whitespace and casing`() {
        assertEquals(BridgeMode.OVERLAY_PROXY, BridgeMode.fromManifestValue("  PROXY "))
    }

    @Test
    fun `falls back instead of crashing on a typo`() {
        assertEquals(BridgeMode.BRIDGE, BridgeMode.fromManifestValue("proxxy"))
        assertEquals(BridgeMode.BRIDGE, BridgeMode.fromManifestValue(""))
    }

    @Test
    fun `manifest meta-data name matches the manifest file`() {
        assertEquals("app.openlauncher.feed.bridge_mode", BridgeMode.META_DATA_NAME)
    }
}

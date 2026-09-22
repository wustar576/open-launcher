/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 實驗開關的取值規則。實機那一端是 `adb shell setprop log.tag.<tag> DEBUG`，
 * 這裡直接塞一個假的 `isEnabled` 進去，所以不需要 Android runtime。
 */
class FeedFlagsTest {

    private fun flagsWith(vararg enabled: String) =
        FeedFlags(isEnabled = { tag -> enabled.contains(tag) })

    @Test
    fun `everything is off by default`() {
        val flags = flagsWith()
        assertFalse(flags.rewriteClientIdentity)
        assertFalse(flags.wrapCallback)
        assertFalse(flags.detachBeforeAttach)
        assertFalse(flags.bindImportant)
        assertFalse(flags.noLinger)
        assertFalse(flags.dualBind)
        assertFalse(flags.forceBridgeMode)
    }

    @Test
    fun `the default wire behaviour is the one that worked on 2026-09-20`() {
        val flags = flagsWith()
        assertEquals(OverlayProtocol.API_VERSION, flags.upstreamApiVersion)
        assertEquals(OverlayProtocol.CLIENT_VERSION, flags.upstreamClientVersion)
        assertEquals(FeedFlags.BIND_AUTO_CREATE, flags.upstreamBindFlags)
        assertEquals(FeedFlags.LINGER_MILLIS, flags.lingerMillis)
    }

    @Test
    fun `each switch is read from its own tag`() {
        assertTrue(flagsWith(FeedFlags.TAG_REWRITE_IDENTITY).rewriteClientIdentity)
        assertTrue(flagsWith(FeedFlags.TAG_WRAP_CALLBACK).wrapCallback)
        assertTrue(flagsWith(FeedFlags.TAG_DETACH_BEFORE).detachBeforeAttach)
        assertTrue(flagsWith(FeedFlags.TAG_BIND_IMPORTANT).bindImportant)
        assertTrue(flagsWith(FeedFlags.TAG_NO_LINGER).noLinger)
        // 一個開關不可以順手打開別的。
        assertFalse(flagsWith(FeedFlags.TAG_REWRITE_IDENTITY).wrapCallback)
    }

    @Test
    fun `the upstream api version follows the tags`() {
        assertEquals(9, flagsWith(FeedFlags.TAG_UPSTREAM_V9).upstreamApiVersion)
        assertEquals(11, flagsWith(FeedFlags.TAG_UPSTREAM_V11).upstreamApiVersion)
        // 兩個都開＝以比較新的為準，而不是隨機一個。
        assertEquals(
            11,
            flagsWith(FeedFlags.TAG_UPSTREAM_V9, FeedFlags.TAG_UPSTREAM_V11).upstreamApiVersion,
        )
    }

    @Test
    fun `cv can be dropped`() {
        assertNull(flagsWith(FeedFlags.TAG_UPSTREAM_NO_CV).upstreamClientVersion)
    }

    @Test
    fun `turning the linger off goes back to unbinding straight away`() {
        assertEquals(0L, flagsWith(FeedFlags.TAG_NO_LINGER).lingerMillis)
    }

    @Test
    fun `bind important adds exactly one flag`() {
        assertEquals(
            FeedFlags.BIND_AUTO_CREATE or FeedFlags.BIND_IMPORTANT,
            flagsWith(FeedFlags.TAG_BIND_IMPORTANT).upstreamBindFlags,
        )
    }

    @Test
    fun `describe names every switch so a logcat trace is self-explanatory`() {
        val described = flagsWith(FeedFlags.TAG_REWRITE_IDENTITY, FeedFlags.TAG_UPSTREAM_NO_CV)
            .describe()
        assertTrue(described, described.contains("rewriteId=true"))
        assertTrue(described, described.contains("wrapCb=false"))
        assertTrue(described, described.contains("no-cv"))
        assertTrue(described, described.contains("v${OverlayProtocol.API_VERSION}"))
    }

    @Test
    fun `the manifest decides the mode while the override is off`() {
        val flags = flagsWith()
        assertEquals(BridgeMode.OVERLAY_PROXY, flags.effectiveMode(BridgeMode.OVERLAY_PROXY))
        assertEquals(BridgeMode.BRIDGE, flags.effectiveMode(BridgeMode.BRIDGE))
        // 還沒讀到 manifest 時沒得猜。
        assertNull(flags.effectiveMode)
        assertEquals(
            BridgeMode.OVERLAY_PROXY,
            flags.withManifestMode(BridgeMode.OVERLAY_PROXY).effectiveMode,
        )
    }

    @Test
    fun `the bridge switch overrides whatever the manifest says`() {
        val flags = flagsWith(FeedFlags.TAG_BRIDGE_MODE)
        assertTrue(flags.forceBridgeMode)
        assertEquals(BridgeMode.BRIDGE, flags.effectiveMode(BridgeMode.OVERLAY_PROXY))
        // manifest 都還沒讀到就已經確定是 bridge。
        assertEquals(BridgeMode.BRIDGE, flags.effectiveMode)
        assertEquals(
            BridgeMode.BRIDGE,
            flags.withManifestMode(BridgeMode.OVERLAY_PROXY).effectiveMode,
        )
    }

    @Test
    fun `the mode line says where the mode came from`() {
        assertEquals(
            "mode=proxy(manifest)",
            flagsWith().withManifestMode(BridgeMode.OVERLAY_PROXY).describeMode(),
        )
        assertEquals(
            "mode=bridge(override)",
            flagsWith(FeedFlags.TAG_BRIDGE_MODE)
                .withManifestMode(BridgeMode.OVERLAY_PROXY)
                .describeMode(),
        )
        // manifest 還沒讀到、也沒有覆寫：印得出「不知道」而不是亂猜一個。
        assertEquals("mode=?(manifest)", flagsWith().describeMode())
    }

    @Test
    fun `describe carries the mode so one log line explains the whole variant`() {
        val described = flagsWith(FeedFlags.TAG_BRIDGE_MODE)
            .withManifestMode(BridgeMode.OVERLAY_PROXY)
            .describe()
        assertTrue(described, described.startsWith("mode=bridge(override)"))
    }

    @Test
    fun `dual bind asks for the two launcher shaped connections`() {
        val flags = flagsWith(FeedFlags.TAG_DUAL_BIND)
        assertEquals(
            listOf(FeedFlags.BIND_FLAGS_IMPORTANT, FeedFlags.BIND_FLAGS_WAIVE_PRIORITY),
            flags.upstreamBindFlagsList,
        )
        assertEquals(0x41, FeedFlags.BIND_FLAGS_IMPORTANT)
        assertEquals(0x21, FeedFlags.BIND_FLAGS_WAIVE_PRIORITY)
        // 第一條就是拿 binder 的那條。
        assertEquals(FeedFlags.BIND_FLAGS_IMPORTANT, flags.upstreamBindFlags)
        assertEquals("0x41+0x21", flags.describeBindFlags())
        assertTrue(flags.describe(), flags.describe().contains("bindFlags=0x41+0x21"))
    }

    @Test
    fun `dual bind wins over bind important and says so`() {
        val flags = flagsWith(FeedFlags.TAG_DUAL_BIND, FeedFlags.TAG_BIND_IMPORTANT)
        assertEquals(
            listOf(FeedFlags.BIND_FLAGS_IMPORTANT, FeedFlags.BIND_FLAGS_WAIVE_PRIORITY),
            flags.upstreamBindFlagsList,
        )
        assertTrue(flags.bindImportantIgnored)
        assertTrue(flags.describe(), flags.describe().contains("bindImp ignored"))
        // 只開 BindImp 時不算被蓋掉。
        assertFalse(flagsWith(FeedFlags.TAG_BIND_IMPORTANT).bindImportantIgnored)
    }

    @Test
    fun `a single connection is still the default shape`() {
        assertEquals(listOf(FeedFlags.BIND_AUTO_CREATE), flagsWith().upstreamBindFlagsList)
        assertEquals("0x1", flagsWith().describeBindFlags())
        assertEquals(
            listOf(FeedFlags.BIND_FLAGS_IMPORTANT),
            flagsWith(FeedFlags.TAG_BIND_IMPORTANT).upstreamBindFlagsList,
        )
    }

    @Test
    fun `every tag fits the 23 character logcat limit`() {
        FeedFlags.ALL_TAGS.forEach { tag ->
            assertTrue("$tag is too long for Log.isLoggable", tag.length <= 23)
        }
        assertEquals(FeedFlags.ALL_TAGS.size, FeedFlags.ALL_TAGS.distinct().size)
    }
}

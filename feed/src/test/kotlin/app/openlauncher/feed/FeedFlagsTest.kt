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
        FeedFlags { tag -> enabled.contains(tag) }

    @Test
    fun `everything is off by default`() {
        val flags = flagsWith()
        assertFalse(flags.rewriteClientIdentity)
        assertFalse(flags.wrapCallback)
        assertFalse(flags.detachBeforeAttach)
        assertFalse(flags.bindImportant)
        assertFalse(flags.noLinger)
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
    fun `every tag fits the 23 character logcat limit`() {
        FeedFlags.ALL_TAGS.forEach { tag ->
            assertTrue("$tag is too long for Log.isLoggable", tag.length <= 23)
        }
        assertEquals(FeedFlags.ALL_TAGS.size, FeedFlags.ALL_TAGS.distinct().size)
    }
}

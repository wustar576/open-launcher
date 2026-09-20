/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 對應 2026-09-20 的實機發現：release 版啟動器（不是 debuggable）配同一個外掛時，
 * Google app 永遠不回報 `overlayStatusChanged`。唯一會洩漏「客戶端其實是啟動器」的欄位
 * 就是 `layout_params.packageName`，轉送前必須改成外掛自己的。
 */
class AttachPayloadTest {

    private val ourPackage = "app.openlauncher.feed"
    private val launcherPackage = "app.openlauncher"

    @Test
    fun `the launcher package name is rewritten to ours`() {
        assertEquals(ourPackage, AttachPayload.packageNameRewrite(launcherPackage, ourPackage))
    }

    @Test
    fun `a package name that is already ours is left alone`() {
        assertNull(AttachPayload.packageNameRewrite(ourPackage, ourPackage))
    }

    @Test
    fun `a missing package name is still rewritten to ours`() {
        assertEquals(ourPackage, AttachPayload.packageNameRewrite(null, ourPackage))
    }

    @Test
    fun `an unknown own package name means no rewrite at all`() {
        // 寧可維持原樣，也不要送出空的 packageName。
        assertNull(AttachPayload.packageNameRewrite(launcherPackage, ""))
    }

    @Test
    fun `the description names every key and the fields we care about`() {
        val described = AttachPayload.describe(
            keys = listOf("layout_params", "client_options", "configuration"),
            layoutParams = AttachPayload.LayoutParamsFacts(
                packageName = launcherPackage,
                type = 1,
                flags = 0x1810100,
                hasToken = true,
                title = "app.openlauncher/app.lawnchair.LawnchairLauncher",
            ),
            clientOptions = 15,
            configuration = "orientation=1",
        )
        assertTrue(described, described.contains("client_options"))
        assertTrue(described, described.contains("configuration"))
        assertTrue(described, described.contains("packageName=$launcherPackage"))
        assertTrue(described, described.contains("token=present"))
        assertTrue(described, described.contains("flags=0x1810100"))
        assertTrue(described, described.contains("0xf")) // client_options
        assertTrue(described, described.contains("LawnchairLauncher"))
    }

    @Test
    fun `a missing layout_params is called out rather than silently omitted`() {
        val described = AttachPayload.describe(null, null, null, null)
        assertTrue(described, described.contains("<absent>"))
        assertTrue(described, described.contains("<none>"))
    }

    @Test
    fun `a missing window token is called out - the overlay has nothing to attach to`() {
        val described = AttachPayload.describe(
            keys = emptyList(),
            layoutParams = AttachPayload.LayoutParamsFacts(ourPackage, 1, 0, hasToken = false, title = null),
            clientOptions = null,
            configuration = null,
        )
        assertTrue(described, described.contains("token=MISSING"))
    }

    @Test
    fun `the window title names the launcher too and is rewritten to ours`() {
        val rewritten = AttachPayload.titleRewrite(
            "$launcherPackage/app.lawnchair.LawnchairLauncher",
            ourPackage,
        )
        assertEquals("$ourPackage/app.openlauncher.feed.OverlayBridgeService", rewritten)
    }

    @Test
    fun `a title that is already ours is left alone`() {
        assertNull(AttachPayload.titleRewrite("$ourPackage/whatever", ourPackage))
    }

    @Test
    fun `a window with no title stays without one`() {
        assertNull(AttachPayload.titleRewrite(null, ourPackage))
    }

    @Test
    fun `the rewrite log line names the field and both values`() {
        val line = AttachPayload.describeRewrite("packageName", launcherPackage, ourPackage)
        assertTrue(line, line.contains("packageName"))
        assertTrue(line, line.contains(launcherPackage))
        assertTrue(line, line.contains(ourPackage))
        assertTrue(line, line.contains("token untouched"))
    }

    @Test
    fun `no rewrite is logged as kept`() {
        val line = AttachPayload.describeRewrite("title", ourPackage, null)
        assertTrue(line, line.contains("title kept as $ourPackage"))
    }

    @Test
    fun `bundle keys are the ones the launcher actually sends`() {
        // 這三個常數就是 LauncherClient.exchangeConfig() 放進 bundle 的 key，打錯字
        // 會讓改寫靜靜地失效。
        assertEquals("layout_params", AttachPayload.KEY_LAYOUT_PARAMS)
        assertEquals("configuration", AttachPayload.KEY_CONFIGURATION)
        assertEquals("client_options", AttachPayload.KEY_CLIENT_OPTIONS)
    }
}

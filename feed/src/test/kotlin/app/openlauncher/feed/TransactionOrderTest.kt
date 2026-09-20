/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

import amirz.aidlbridge.IBridge
import amirz.aidlbridge.IBridgeCallback
import com.google.android.libraries.launcherclient.ILauncherOverlay
import com.google.android.libraries.launcherclient.ILauncherOverlayCallback
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 交易順序就是 wire format。AIDL 的宣告順序決定 Binder transaction code，改動順序
 * （或不小心刪掉佔位的 `unusedMethod`）會讓每個呼叫都打到錯誤的方法，而且不會有
 * 任何編譯錯誤。這個測試把 spec 第 8.4 節的表格釘死在建置流程裡。
 *
 * 用反射讀 AIDL 產生的 `TRANSACTION_*` 常數（它們是 package-private 的 static final int）。
 */
class TransactionOrderTest {

    private val firstCallTransaction = 1 // android.os.IBinder.FIRST_CALL_TRANSACTION

    private fun transactionCode(stub: Class<*>, method: String): Int {
        val field = try {
            stub.getDeclaredField("TRANSACTION_$method")
        } catch (e: NoSuchFieldException) {
            throw AssertionError(
                "${stub.name} has no TRANSACTION_$method constant - was the AIDL method renamed or removed?",
                e,
            )
        }
        field.isAccessible = true
        return field.getInt(null)
    }

    private fun assertOrder(stub: Class<*>, vararg methods: String) {
        methods.forEachIndexed { index, method ->
            assertEquals(
                "$method must be transaction ${index + 1} of ${stub.name}",
                firstCallTransaction + index,
                transactionCode(stub, method),
            )
        }
    }

    @Test
    fun `ILauncherOverlay keeps the 17 transactions in spec order`() {
        assertOrder(
            ILauncherOverlay.Stub::class.java,
            // spec 8.4 的表格，順序不可更動
            "startScroll", // 1
            "onScroll", // 2
            "endScroll", // 3
            "windowAttached", // 4
            "windowDetached", // 5
            "closeOverlay", // 6
            "onPause", // 7
            "onResume", // 8
            "openOverlay", // 9
            "requestVoiceDetection", // 10
            "getVoiceSearchLanguage", // 11
            "isVoiceDetectionRunning", // 12
            "hasOverlayContent", // 13
            "windowAttached2", // 14
            "unusedMethod", // 15 佔位，必須存在
            "setActivityState", // 16
            "startSearch", // 17
        )
    }

    @Test
    fun `ILauncherOverlay declares exactly 17 methods`() {
        assertEquals(17, ILauncherOverlay::class.java.declaredMethods.size)
    }

    @Test
    fun `ILauncherOverlayCallback keeps its two transactions`() {
        assertOrder(
            ILauncherOverlayCallback.Stub::class.java,
            "overlayScrollChanged",
            "overlayStatusChanged",
        )
        assertEquals(2, ILauncherOverlayCallback::class.java.declaredMethods.size)
    }

    @Test
    fun `IBridge keeps its single transaction`() {
        assertOrder(IBridge.Stub::class.java, "bindService")
        assertEquals(1, IBridge::class.java.declaredMethods.size)
    }

    @Test
    fun `IBridgeCallback keeps its two transactions`() {
        assertOrder(
            IBridgeCallback.Stub::class.java,
            "onServiceConnected",
            "onServiceDisconnected",
        )
        assertEquals(2, IBridgeCallback::class.java.declaredMethods.size)
    }

    /**
     * 介面描述字串也是協定的一部分：啟動器用 `IBinder.getInterfaceDescriptor()`
     * 判斷 `onBind()` 回傳的是哪一種介面。改了套件名就等於換了一個協定。
     */
    @Test
    fun `interface descriptors match what the launcher compares against`() {
        assertEquals(
            "com.google.android.libraries.launcherclient.ILauncherOverlay",
            descriptorOf(ILauncherOverlay::class.java),
        )
        assertEquals(
            "com.google.android.libraries.launcherclient.ILauncherOverlayCallback",
            descriptorOf(ILauncherOverlayCallback::class.java),
        )
        assertEquals("amirz.aidlbridge.IBridge", descriptorOf(IBridge::class.java))
        assertEquals("amirz.aidlbridge.IBridgeCallback", descriptorOf(IBridgeCallback::class.java))
    }

    /** AIDL 把 DESCRIPTOR 產生在介面上（不是 Stub 上）。 */
    private fun descriptorOf(iface: Class<*>): String {
        val field = iface.getDeclaredField("DESCRIPTOR")
        field.isAccessible = true
        return field.get(null) as String
    }
}

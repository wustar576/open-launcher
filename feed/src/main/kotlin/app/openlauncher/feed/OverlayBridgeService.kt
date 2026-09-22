/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Open Launcher contributors
 */

package app.openlauncher.feed

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * 啟動器綁定的入口。intent-filter 只宣告 `scheme="app"`，不限制 host、不宣告 mimeType，
 * 因為啟動器探測時送來的 data URI 可能是 `app://<啟動器套件>:<uid>?v=7&cv=9`，
 * 也可能只是 `app://<套件名>`。
 *
 * 啟動器會對同一個 service 綁兩次（flags 不同）。Android 對同一個 intent 只會呼叫一次
 * [onBind]，兩次綁定拿到同一個 binder；客戶端的計數在 [BridgeBinder] 內部以 callback
 * 為單位處理。
 */
class OverlayBridgeService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    /**
     * 實機實驗開關（`adb shell setprop log.tag.OLFeed… DEBUG`），見 [FeedFlags]。
     *
     * [onCreate] 讀到 manifest 宣告的模式之後會換成 `withManifestMode(...)` 的版本，
     * 這樣連 `OLFeed.Upstream` 的 `binding:` 那行都印得出目前是哪一種模式。
     */
    private var flags = FeedFlags.fromSystemProperties

    private lateinit var connector: GoogleOverlayConnector
    private lateinit var mode: BridgeMode

    private var bridgeBinder: BridgeBinder? = null
    private var overlayProxy: LauncherOverlayProxy? = null

    override fun onCreate() {
        super.onCreate()
        val manifestMode = readManifestMode()
        flags = flags.withManifestMode(manifestMode)
        mode = resolveMode()
        connector = GoogleOverlayConnector(applicationContext, handler, flags)
        FeedLog.i(
            FeedLog.SERVICE,
            "onCreate: ${flags.describeMode()} " +
                "(manifest=${manifestMode.manifestValue}, " +
                "${FeedFlags.TAG_BRIDGE_MODE}=${flags.forceBridgeMode}), package=$packageName, " +
                "googleApp=${if (connector.isGoogleAppAvailable()) "available" else "MISSING"} | " +
                "switches: ${flags.describe()}",
        )
        when (mode) {
            BridgeMode.BRIDGE -> bridgeBinder = BridgeBinder(connector, handler)
            // 外掛自己的套件名要交給 proxy：轉送 windowAttached* 之前，
            // LayoutParams.packageName 會被改寫成它（見 AttachPayload）。
            BridgeMode.OVERLAY_PROXY -> overlayProxy = LauncherOverlayProxy(packageName, flags)
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        FeedLog.i(FeedLog.SERVICE, "onBind: data=${intent.data} -> ${flags.describeMode()}")
        val uri = intent.data?.toString()
        FeedLog.d(
            FeedLog.SERVICE,
            "caller claims package=${OverlayProtocol.packageNameOf(uri)} uid=${OverlayProtocol.uidOf(uri)}",
        )
        return when (mode) {
            BridgeMode.BRIDGE -> bridgeBinder
            BridgeMode.OVERLAY_PROXY -> overlayProxy?.also { proxy ->
                // Stub 會立刻被回傳給啟動器，因此先去把上游連起來。
                handler.post { connector.attach(proxy) }
            }
        }
    }

    override fun onRebind(intent: Intent) {
        FeedLog.i(FeedLog.SERVICE, "onRebind: data=${intent.data}")
        overlayProxy?.let { proxy -> handler.post { connector.attach(proxy) } }
    }

    override fun onUnbind(intent: Intent): Boolean {
        FeedLog.i(FeedLog.SERVICE, "onUnbind: data=${intent.data}, releasing upstream")
        bridgeBinder?.releaseAll()
        overlayProxy?.let { proxy ->
            // detach 不會馬上拆線：連線會多留 FeedFlags.LINGER_MILLIS，啟動器在那之內
            // 重綁（換提供者、收到套件更新廣播）時，上游與 window session 完全不受影響。
            // 真的要拆線時，connector 會先呼叫 proxy.onUpstreamReleasing()，在 unbind 之前
            // 把啟動器的 window token 還給 Google app（見 LauncherOverlayProxy）。
            handler.post { connector.detach(proxy) }
        }
        // 回傳 true 才會在下次綁定時收到 onRebind。
        return true
    }

    override fun onDestroy() {
        FeedLog.i(FeedLog.SERVICE, "onDestroy")
        // service 要沒了，沒有「等它回來」這回事：detachAll 立刻放手（一樣會先還 token）。
        handler.post { connector.detachAll() }
        super.onDestroy()
    }

    /**
     * 這一次真正生效的模式：manifest meta-data 的值，再讓
     * `setprop log.tag.OLFeedBridge DEBUG` 有機會把它覆寫成 [BridgeMode.BRIDGE]。
     *
     * 模式只在這裡決定一次，所以切換 `OLFeedBridge` 之後一定要
     * `am force-stop app.openlauncher.feed` 才會生效。
     */
    private fun resolveMode(): BridgeMode = flags.effectiveMode ?: BridgeMode.DEFAULT

    /** manifest 宣告的模式（沒有覆寫時就是它）。 */
    private fun readManifestMode(): BridgeMode {
        val value = try {
            packageManager.getServiceInfo(
                ComponentName(this, OverlayBridgeService::class.java),
                PackageManager.GET_META_DATA,
            ).metaData?.getString(BridgeMode.META_DATA_NAME)
        } catch (t: Throwable) {
            FeedLog.w(FeedLog.SERVICE, "cannot read ${BridgeMode.META_DATA_NAME}", t)
            null
        }
        return BridgeMode.fromManifestValue(value)
    }
}

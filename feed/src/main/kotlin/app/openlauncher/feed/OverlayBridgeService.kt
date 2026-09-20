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

    private lateinit var connector: GoogleOverlayConnector
    private lateinit var mode: BridgeMode

    private var bridgeBinder: BridgeBinder? = null
    private var overlayProxy: LauncherOverlayProxy? = null

    override fun onCreate() {
        super.onCreate()
        mode = resolveMode()
        connector = GoogleOverlayConnector(applicationContext, handler)
        FeedLog.i(
            FeedLog.SERVICE,
            "onCreate: mode=$mode, package=$packageName, " +
                "googleApp=${if (connector.isGoogleAppAvailable()) "available" else "MISSING"}",
        )
        when (mode) {
            BridgeMode.BRIDGE -> bridgeBinder = BridgeBinder(connector, handler)
            BridgeMode.OVERLAY_PROXY -> overlayProxy = LauncherOverlayProxy()
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        FeedLog.i(FeedLog.SERVICE, "onBind: data=${intent.data} -> $mode")
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
            handler.post {
                // 順序很重要：一定要在 unbind 之前把啟動器的 window token 還給 Google app，
                // 否則下一個提供者拿同一個 token 來 attach 時會被無視（見 LauncherOverlayProxy）。
                proxy.releaseWindow()
                connector.detach(proxy)
            }
        }
        // 回傳 true 才會在下次綁定時收到 onRebind。
        return true
    }

    override fun onDestroy() {
        FeedLog.i(FeedLog.SERVICE, "onDestroy")
        val proxy = overlayProxy
        handler.post {
            proxy?.releaseWindow()
            connector.detachAll()
        }
        super.onDestroy()
    }

    private fun resolveMode(): BridgeMode {
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

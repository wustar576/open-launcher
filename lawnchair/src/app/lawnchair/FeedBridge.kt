/*
 * Copyright 2021, Lawnchair
 * Copyright 2026, Open Launcher contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package app.lawnchair

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE
import android.content.pm.ApplicationInfo.FLAG_SYSTEM
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.util.Log
import app.lawnchair.preferences.PreferenceManager
import app.lawnchair.util.SingletonHolder
import app.lawnchair.util.ensureOnMainThread
import app.lawnchair.util.useApplicationContext
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import com.android.launcher3.Utilities

/**
 * 決定新聞頁（`-1` 頁）要綁哪一個套件。
 *
 * 只有兩條路：
 * 1. **Open Launcher Feed**（`app.openlauncher.feed`）——本專案的外掛 APK。它是
 *    debuggable 的，可以代表啟動器去綁 Google app，再把 binder 交還給啟動器。
 * 2. **直接綁 Google app**——只有在啟動器本身就是系統 app 或 debuggable build
 *    時才可能成功（[isPrivilegedClient]）。這種情況 [resolveBridge] 回傳 null，
 *    由 `LauncherClient.getIntent()` 退回 `com.google.android.googlequicksearchbox`。
 *
 * 原本的第三方白名單（Lawnfeed、Pixel bridge、NeoFeed、HomeFeeder、libre、
 * AIDLBridge、Smartspacer）全部移除：本專案不推薦、也不驗證那些 APK。
 * 使用者仍可在「除錯選單 → 忽略新聞頁白名單」後手動指定任意提供者。
 */
class FeedBridge(private val context: Context) {

    /**
     * 啟動器自己有沒有資格直接當 overlay 客戶端。Google app 只接受系統 app 或
     * debuggable app；一般安裝的 release 啟動器兩者都不是，必須走外掛。
     */
    private val isPrivilegedClient =
        context.applicationInfo.flags and (FLAG_DEBUGGABLE or FLAG_SYSTEM) != 0

    private val prefs by lazy { PreferenceManager.getInstance(context) }

    /** 內建（自動解析）的 bridge 清單。目前只有本專案的外掛。 */
    private val bridgePackages by lazy {
        listOf(BridgeInfo(FEED_PACKAGE, R.integer.feed_bridge_signature_hash))
    }

    /**
     * 找出要綁定的 bridge 套件；回傳 null 代表「不經過 bridge，直接綁 Google app」。
     *
     * 注意：就算啟動器本身是 debuggable（可以直接綁 Google app），只要外掛裝著，
     * 仍然優先走外掛。debug build 因此和使用者實際安裝的 release build 走同一條
     * 路徑，才測得到 bridge。
     */
    @JvmOverloads
    fun resolveBridge(customPackage: String = prefs.feedProvider.get()): BridgeInfo? {
        customBridgeOrNull(customPackage)?.let { return it }
        return bridgePackages.firstOrNull { it.isAvailable() }
    }

    private fun customBridgeOrNull(customPackage: String = prefs.feedProvider.get()): CustomBridgeInfo? {
        return if (customPackage.isNotBlank()) {
            val bridge = CustomBridgeInfo(customPackage)
            if (bridge.isAvailable()) bridge else null
        } else {
            null
        }
    }

    /** 本專案的外掛是否已安裝且簽章正確。 */
    fun isCompanionInstalled(): Boolean = bridgePackages.any { it.isAvailable() }

    /** 新聞頁到底有沒有辦法運作（有 bridge，或啟動器本身就有資格直連）。 */
    fun isInstalled(): Boolean = resolveBridge() != null || isPrivilegedClient

    open inner class BridgeInfo(val packageName: String, signatureHashRes: Int) {
        protected open val signatureHash =
            if (signatureHashRes > 0) context.resources.getInteger(signatureHashRes) else 0

        fun isAvailable(): Boolean {
            val info = context.packageManager.resolveService(
                Intent(OVERLAY_ACTION)
                    .setPackage(packageName)
                    .setData(
                        Uri.parse(
                            StringBuilder(packageName.length + 18)
                                .append("app://")
                                .append(packageName)
                                .append(":")
                                .append(Process.myUid())
                                .toString(),
                        )
                            .buildUpon()
                            .appendQueryParameter("v", 7.toString())
                            .appendQueryParameter("cv", 9.toString())
                            .build(),
                    ),
                0,
            )
            return info != null && isSigned()
        }

        open fun isSigned(): Boolean {
            // debug build 不驗簽章：外掛是用 debug keystore 簽的，雜湊每台機器都不同。
            if (BuildConfig.DEBUG) return true
            if (signatureHash == 0) {
                Log.e(
                    TAG,
                    "Feed provider $packageName rejected: the release signature hash in " +
                        "bridge.xml is still the placeholder. See feed/README.md for how to fill it in.",
                )
                logSignatureHashes(packageName)
                return false
            }
            return try {
                val hashes = signatureHashesOf(packageName)
                val matched = hashes.any { it == signatureHash }
                if (!matched) {
                    Log.w(
                        TAG,
                        "Feed provider $packageName rejected: signature hash " +
                            hashes.joinToString { "0x${Integer.toHexString(it)}" } +
                            " does not match expected 0x${Integer.toHexString(signatureHash)}",
                    )
                }
                matched
            } catch (e: Exception) {
                Log.w(TAG, "Unable to read the signature of $packageName", e)
                false
            }
        }
    }

    /**
     * 使用者在設定頁手動挑的提供者。除非在除錯選單打開「忽略白名單」，否則
     * 必須是白名單內的套件且簽章相符。
     */
    private inner class CustomBridgeInfo(packageName: String) : BridgeInfo(packageName, 0) {
        override val signatureHash = whitelist[packageName] ?: -1
        val ignoreWhitelist = prefs.ignoreFeedWhitelist.get()

        override fun isSigned(): Boolean {
            if (ignoreWhitelist) return true
            if (signatureHash == -1) {
                logSignatureHashes(packageName)
                return false
            }
            return super.isSigned()
        }
    }

    /**
     * 把某個套件目前的簽章雜湊印到 logcat。發佈階段要把外掛 release 簽章的雜湊
     * 填進 `lawnchair/res/values/bridge.xml` 時，就是從這一行取得（見 feed/README.md）。
     */
    private fun logSignatureHashes(packageName: String) {
        try {
            signatureHashesOf(packageName).forEach {
                Log.d(TAG, "Feed provider $packageName(0x${Integer.toHexString(it)}) isn't whitelisted")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to read the signature of $packageName", e)
        }
    }

    private fun signatureHashesOf(packageName: String): List<Int> {
        val pm = context.packageManager
        return if (Utilities.ATLEAST_P) {
            val signingInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            if (signingInfo == null || signingInfo.hasMultipleSigners()) {
                emptyList()
            } else {
                signingInfo.signingCertificateHistory?.map { it.hashCode() } ?: emptyList()
            }
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                .signatures?.map { it.hashCode() } ?: emptyList()
        }
    }

    companion object : SingletonHolder<FeedBridge, Context>(
        ensureOnMainThread(
            useApplicationContext(::FeedBridge),
        ),
    ) {
        private const val TAG = "FeedBridge"
        private const val OVERLAY_ACTION = "com.android.launcher3.WINDOW_OVERLAY"

        /** 提供 Discover 畫面的 Google app。 */
        const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"

        /** 本專案的新聞頁外掛 APK。 */
        const val FEED_PACKAGE = "app.openlauncher.feed"

        /** 外掛的下載位置，設定頁在未安裝時會導向這裡。 */
        const val RELEASES_URL = "https://github.com/wustar576/open-launcher/releases"

        private val whitelist = mutableMapOf<String, Int>()

        fun initializeWhitelist(context: Context) {
            whitelist[FEED_PACKAGE] = context.resources.getInteger(R.integer.feed_bridge_signature_hash)
            whitelist[GOOGLE_APP_PACKAGE] = 0xe3ca78d8.toInt()
        }

        fun getAvailableProviders(context: Context) = context.packageManager
            .queryIntentServices(
                Intent(OVERLAY_ACTION).setData(Uri.parse("app://${context.packageName}")),
                PackageManager.GET_META_DATA,
            )
            .asSequence()
            .map { it.serviceInfo.applicationInfo }
            .distinct()
            .filter { getInstance(context).CustomBridgeInfo(it.packageName).isSigned() }

        /**
         * 是否要把 `LauncherClientBridge` 掛上去、並且把綁定目標指向 bridge 套件。
         * 沒有 bridge 時回傳 false，`LauncherClient.getIntent()` 會直接綁 Google app。
         */
        @JvmStatic
        fun useBridge(context: Context) = getInstance(context).resolveBridge() != null
    }

    init {
        initializeWhitelist(context)
    }
}

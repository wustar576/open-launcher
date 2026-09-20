package app.lawnchair.nexuslauncher

import android.app.Activity
import android.content.Context
import android.os.Bundle
import app.lawnchair.FeedBridge
import app.lawnchair.LawnchairLauncher
import app.lawnchair.preferences2.PreferenceManager2
import app.lawnchair.preferences2.firstCached
import com.android.launcher3.Launcher
import com.android.launcher3.LauncherPrefs
import com.android.systemui.plugins.shared.LauncherOverlayManager
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlay
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayCallbacks
import com.google.android.libraries.launcherclient.ISerializableScrollCallback
import com.google.android.libraries.launcherclient.LauncherClient
import com.google.android.libraries.launcherclient.LauncherClientCallbacks
import com.google.android.libraries.launcherclient.LauncherClientService
import com.google.android.libraries.launcherclient.StaticInteger

/**
 * Implements [LauncherOverlay] and passes all the corresponding events to [LauncherClient],
 * see [LauncherClientService.setClient].
 *
 * Implements [LauncherClientCallbacks] and sends all the corresponding callbacks to [Launcher].
 */
class OverlayCallbackImpl(private val mLauncher: LawnchairLauncher) :
    LauncherOverlay,
    LauncherClientCallbacks,
    LauncherOverlayManager,
    ISerializableScrollCallback {
    private val mClient: LauncherClient
    private var mFlagsChanged = false
    private var mLauncherOverlayCallbacks: LauncherOverlayCallbacks? = null
    private var mWasOverlayAttached = false
    private var mFlags = 0

    init {
        val prefs = PreferenceManager2.getInstance(mLauncher)
        val enableFeed = prefs.enableFeed.firstCached()
        mClient = LauncherClient(
            mLauncher,
            this,
            StaticInteger((if (enableFeed) 1 else 0) or 2 or 4 or 8),
        )
    }

    fun reconnect() {
        mClient.reconnect()
    }

    fun setEnableFeed(enable: Boolean) {
        mClient.setEnableFeed(enable)
        reconnect()
    }

    override fun onDeviceProvideChanged() {
        mClient.redraw()
    }

    override fun onAttachedToWindow() {
        mClient.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        mClient.onDetachedFromWindow()
    }

    override fun openOverlay() {
        mClient.showOverlay(true)
    }

    override fun hideOverlay(animate: Boolean) {
        mClient.hideOverlay(animate)
    }

    override fun hideOverlay(duration: Int) {
        mClient.hideOverlay(duration)
    }

    fun onActivityCreated(activity: Activity, bundle: Bundle?) = Unit

    override fun onActivityStarted() {
        mClient.onStart()
    }

    override fun onActivityResumed() {
        mClient.onResume()
    }

    override fun onActivityPaused() {
        mClient.onPause()
    }

    override fun onActivityStopped() {
        mClient.onStop()
    }

    fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) = Unit

    override fun onActivityDestroyed() {
        mClient.onDestroy()
    }

    override fun onOverlayScrollChanged(progress: Float) {
        mLauncherOverlayCallbacks?.onOverlayScrollChanged(progress)
    }

    override fun onServiceStateChanged(overlayAttached: Boolean, hotwordActive: Boolean) {
        onServiceStateChanged(overlayAttached)
    }

    override fun onServiceStateChanged(overlayAttached: Boolean) {
        if (overlayAttached != mWasOverlayAttached) {
            mWasOverlayAttached = overlayAttached
            mLauncher.setLauncherOverlay(if (overlayAttached) this else null)
        }
    }

    override fun onScrollInteractionBegin() {
        mClient.startScroll()
    }

    override fun onScrollInteractionEnd() {
        mClient.endScroll()
    }

    override fun onScrollChange(progress: Float, rtl: Boolean) {
        mClient.setScroll(progress)
    }

    override fun setOverlayCallbacks(callbacks: LauncherOverlayCallbacks?) {
        mLauncherOverlayCallbacks = callbacks
    }

    override fun setPersistentFlags(flags: Int) {
        val newFlags = flags and (8 or 16)
        if (newFlags != mFlags) {
            mFlagsChanged = true
            mFlags = newFlags
            LauncherPrefs.getDevicePrefs(mLauncher).edit().putInt(PREF_PERSIST_FLAGS, newFlags).apply()
        }
    }

    companion object {
        private const val PREF_PERSIST_FLAGS = "pref_persistent_flags"

        /**
         * 新聞頁是否可用：裝了 Open Launcher Feed 外掛（或使用者指定的提供者），
         * 或者這個啟動器本身就是系統／debuggable build，可以直接連 Google app。
         */
        @JvmStatic
        fun minusOneAvailable(context: Context): Boolean = FeedBridge.getInstance(context).isInstalled()

        /** 本專案的外掛裝了沒有；設定頁用這個決定要不要顯示「需要安裝」提示。 */
        @JvmStatic
        fun companionInstalled(context: Context): Boolean = FeedBridge.getInstance(context).isCompanionInstalled()
    }
}

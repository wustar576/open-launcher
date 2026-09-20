package com.google.android.libraries.launcherclient;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Point;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

import androidx.annotation.NonNull;

import app.lawnchair.FeedBridge;
import app.lawnchair.FeedBridge.BridgeInfo;
import java.lang.ref.WeakReference;

public class LauncherClient {
    private static final String TAG = "LauncherClient";

    private static int apiVersion = -1;

    private ILauncherOverlay mOverlay;
    public final IScrollCallback mScrollCallback;

    public final BaseClientService mBaseService;
    public final LauncherClientService mLauncherService;

    /**
     * One package update must cause exactly one reconnect. Installing a package delivers up to
     * three broadcasts we listen for - {@code PACKAGE_REMOVED} and {@code PACKAGE_ADDED}, both
     * carrying {@link Intent#EXTRA_REPLACING}, plus {@code PACKAGE_REPLACED} - and acting on all
     * of them used to tear the overlay down and build it up again two or three times in a row.
     * <p>
     * LC-Fix (2026-09-20): on device that burst showed up on the companion side as
     * "upstream gone / connected" twice inside 40 ms, which means {@code windowAttached2} could
     * be forwarded on a binder that was about to be replaced.
     */
    private static final long RECONNECT_DEBOUNCE_MS = 200L;

    private final Handler mReconnectHandler = new Handler(Looper.getMainLooper());

    private final Runnable mReconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mDestroyed) {
                reconnect();
            }
        }
    };

    /**
     * Reconnects when either the feed companion app or the Google app is installed, updated or
     * removed. Without watching the companion package, installing Open Launcher Feed while the
     * launcher is already running would leave the feed dead until the launcher process restarts.
     */
    public final BroadcastReceiver googleInstallListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            boolean replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false);
            // An update is represented by PACKAGE_REPLACED; the ADDED/REMOVED pair that comes
            // with it is the same event seen twice more.
            if (replacing && !Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                Log.i(TAG, "feed provider package changed: " + action + " " + intent.getData()
                        + " (replacing; folded into the PACKAGE_REPLACED reconnect)");
                return;
            }
            Log.i(TAG, "feed provider package changed: " + action + " " + intent.getData());
            // Coalesce whatever is left: the companion and the Google app can be updated back
            // to back, and one reconnect covers both.
            mReconnectHandler.removeCallbacks(mReconnectRunnable);
            mReconnectHandler.postDelayed(mReconnectRunnable, RECONNECT_DEBOUNCE_MS);
        }
    };

    private int mActivityState = 0;
    int mServiceState = 0;
    public int mFlags;

    public LayoutParams mLayoutParams;
    public OverlayCallback mOverlayCallback;
    public final Activity mActivity;

    public boolean mDestroyed = false;
    private Bundle mLayoutBundle;

    public static class OverlayCallback extends ILauncherOverlayCallback.Stub implements Callback {
        public LauncherClient mClient;
        private final Handler mUIHandler = new Handler(Looper.getMainLooper(), this);
        public Window mWindow;
        private boolean mWindowHidden = false;
        public WindowManager mWindowManager;
        int mWindowShift;

        public void destroy() {
            mClient = null;
            mWindow = null;
            mWindowManager = null;
            mUIHandler.removeCallbacksAndMessages(null);
        }

        @Override
        public final void overlayScrollChanged(float f) {
            mUIHandler.removeMessages(2);
            Message.obtain(mUIHandler, 2, f).sendToTarget();
            if (f > 0f && mWindowHidden) {
                mWindowHidden = false;
            }
        }

        @Override
        public final void overlayStatusChanged(int i) {
            Message.obtain(mUIHandler, 4, i, 0).sendToTarget();
        }

        @Override
        public boolean handleMessage(@NonNull Message message) {
            if (mClient == null) {
                return true;
            }

            switch (message.what) {
                case 2:
                    if ((mClient.mServiceState & 1) != 0) {
                        float floatValue = (float) message.obj;
                        mClient.mScrollCallback.onOverlayScrollChanged(floatValue);
                    }
                    return true;
                case 3:
                    WindowManager.LayoutParams attributes = mWindow.getAttributes();
                    if ((Boolean) message.obj) {
                        attributes.x = mWindowShift;
                        attributes.flags |= 512;
                    } else {
                        attributes.x = 0;
                        attributes.flags &= -513;
                    }
                    mWindowManager.updateViewLayout(mWindow.getDecorView(), attributes);
                    return true;
                case 4:
                    mClient.setServiceState(message.arg1);
                    if (mClient.mScrollCallback instanceof ISerializableScrollCallback) {
                        ((ISerializableScrollCallback) mClient.mScrollCallback).setPersistentFlags(message.arg1);
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    public LauncherClient(Activity activity, IScrollCallback scrollCallback, StaticInteger flags) {
        mActivity = activity;
        mScrollCallback = scrollCallback;
        mBaseService = new BaseClientService(activity, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT);
        mFlags = flags.mData;

        mLauncherService = LauncherClientService.getInstance(activity);
        mLauncherService.mClient = new WeakReference<>(this);
        mOverlay = mLauncherService.mOverlay;

        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        intentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        intentFilter.addDataScheme("package");
        intentFilter.addDataSchemeSpecificPart(FeedBridge.GOOGLE_APP_PACKAGE, 0);
        intentFilter.addDataSchemeSpecificPart(FeedBridge.FEED_PACKAGE, 0);
        mActivity.registerReceiver(googleInstallListener, intentFilter);

        if (apiVersion <= 0) {
            loadApiVersion(activity);
        }

        connect();
        if (mActivity.getWindow() != null &&
                mActivity.getWindow().peekDecorView() != null &&
                mActivity.getWindow().peekDecorView().isAttachedToWindow()) {
            onAttachedToWindow();
        }
    }

    public void setEnableFeed(boolean enable) {
        if (enable) {
            mFlags |= 1;
        } else {
            mFlags &= ~1;
        }
    }

    public final void onAttachedToWindow() {
        if (!mDestroyed) {
            setLayoutParams(mActivity.getWindow().getAttributes());
        }
    }

    public final void onDetachedFromWindow() {
        if (!mDestroyed) {
            setLayoutParams(null);
        }
    }

    public final void onResume() {
        if (!mDestroyed) {
            mActivityState |= 2;
            if (mOverlay != null && mLayoutParams != null) {
                try {
                    if (apiVersion < 4) {
                        mOverlay.onResume();
                    } else {
                        mOverlay.setActivityState(mActivityState);
                    }
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    public final void onPause() {
        if (!mDestroyed) {
            mActivityState &= -3;
            if (mOverlay != null && mLayoutParams != null) {
                try {
                    if (apiVersion < 4) {
                        mOverlay.onPause();
                    } else {
                        mOverlay.setActivityState(mActivityState);
                    }
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    public final void onStart() {
        if (!mDestroyed) {
            mLauncherService.setStopped(false);
            connect();
            mActivityState |= 1;
            if (mOverlay != null && mLayoutParams != null) {
                try {
                    mOverlay.setActivityState(mActivityState);
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    public final void onStop() {
        if (!mDestroyed) {
            mLauncherService.setStopped(true);
            mBaseService.disconnect();
            mActivityState &= -2;
            if (!(mOverlay == null || mLayoutParams == null)) {
                try {
                    mOverlay.setActivityState(mActivityState);
                } catch (RemoteException ignored) {
                }
            }
        }
    }

    public void onDestroy() {
        mDestroyed = true;
        mReconnectHandler.removeCallbacks(mReconnectRunnable);
        try {
            mActivity.unregisterReceiver(googleInstallListener);
        } catch (Exception ignored) {
            // LC-Ignored
        }
        if (mOverlayCallback != null) {
            mOverlayCallback.destroy();
            mOverlayCallback = null;
        }
        if (mLauncherService.mClient != null && mLauncherService.mClient.get() == this) {
            mLauncherService.mClient = null;
        }
        mBaseService.disconnect();
    }

    private void connect() {
        if (!mDestroyed && (!mLauncherService.connect() || !mBaseService.connect())) {
            mActivity.runOnUiThread(() -> setServiceState(0));
        }
    }

    /**
     * Switches to whatever feed provider is configured right now.
     * <p>
     * LC-Fix: this used to drop the service bindings and nothing else, which broke every
     * <em>runtime</em> provider switch (Settings &rarr; Home &rarr; Feed provider):
     * <ol>
     *   <li>The previous provider was never told {@link ILauncherOverlay#windowDetached} before
     *       we unbound, so it kept our window token attached. {@code unbindService()} does not
     *       deliver {@code onServiceDisconnected()}, so nothing else would have told it either.
     *       When the next provider then attached the very same token, the Google app had it
     *       twice over and stayed silent - no {@code overlayStatusChanged}, dead feed.</li>
     *   <li>{@link #mOverlay} kept pointing at the now-unbound binder of the old provider.</li>
     *   <li>{@link #mServiceState} kept the old provider's status (e.g. {@code 0x19}), so when
     *       the new provider reported the identical status {@link #setServiceState(int)} saw no
     *       change and swallowed it - both the callback and the log line.</li>
     * </ol>
     */
    public void reconnect() {
        Log.i(TAG, "reconnect: releasing the current overlay before re-binding");
        detachOverlay();
        mBaseService.disconnect();
        mLauncherService.disconnect();
        LauncherClient.loadApiVersion(mActivity);
        if ((mActivityState & 2) != 0) {
            connect();
        }
    }

    /**
     * Hands our window back to the overlay we are currently attached to and forgets every piece
     * of state that belongs to it, so that the next provider starts from a clean slate.
     */
    private void detachOverlay() {
        ILauncherOverlay overlay = mOverlay;
        mOverlay = null;
        if (overlay != null) {
            try {
                overlay.windowDetached(mActivity.isChangingConfigurations());
                Log.i(TAG, "windowDetached sent to the previous overlay");
            } catch (RemoteException e) {
                Log.w(TAG, "windowDetached failed on the previous overlay", e);
            }
        }
        // A fresh callback binder per attach cycle: the provider keys its session on the binder
        // it was handed, and reusing the old one lets a stale session shadow the new one.
        if (mOverlayCallback != null) {
            mOverlayCallback.destroy();
            mOverlayCallback = null;
        }
        // The new provider has to report its own status. Without this reset an identical status
        // would be deduplicated away by setServiceState() and we would keep forwarding scroll
        // events to a provider that never acknowledged us.
        setServiceState(0);
    }

    public final void setLayoutParams(LayoutParams layoutParams) {
        if (mLayoutParams != layoutParams) {
            mLayoutParams = layoutParams;
            if (mLayoutParams != null) {
                exchangeConfig();
            } else if (mOverlay != null) {
                try {
                    mOverlay.windowDetached(mActivity.isChangingConfigurations());
                } catch (RemoteException ignored) {
                }
                mOverlay = null;
            }
        }
    }

    public final void exchangeConfig() {
        if (mOverlay != null) {
            try {
                if (mOverlayCallback == null) {
                    mOverlayCallback = new OverlayCallback();
                }
                OverlayCallback overlayCallback = mOverlayCallback;
                overlayCallback.mClient = this;
                overlayCallback.mWindowManager = mActivity.getWindowManager();
                Point point = new Point();
                overlayCallback.mWindowManager.getDefaultDisplay().getRealSize(point);
                overlayCallback.mWindowShift = -Math.max(point.x, point.y);
                overlayCallback.mWindow = mActivity.getWindow();
                if (apiVersion < 3) {
                    mOverlay.windowAttached(mLayoutParams, mOverlayCallback, mFlags);
                } else {
                    Bundle bundle = new Bundle();
                    bundle.putParcelable("layout_params", mLayoutParams);
                    bundle.putParcelable("configuration", mActivity.getResources().getConfiguration());
                    bundle.putInt("client_options", mFlags);
                    if (mLayoutBundle != null) {
                        bundle.putAll(mLayoutBundle);
                    }
                    mOverlay.windowAttached2(bundle, mOverlayCallback);
                }
                if (apiVersion >= 4) {
                    mOverlay.setActivityState(mActivityState);
                } else if ((mActivityState & 2) != 0) {
                    mOverlay.onResume();
                } else {
                    mOverlay.onPause();
                }
                Log.i(TAG, "windowAttached2 sent (api " + apiVersion + ", flags " + mFlags
                        + "), waiting for overlayStatusChanged");
            } catch (RemoteException e) {
                // LC-Fix: this used to be swallowed, which made a dead overlay binder (e.g. after
                // the Google app was killed and the bridge re-connected) indistinguishable from a
                // working one that simply never reports a status.
                Log.e(TAG, "windowAttached2 failed; the feed will stay empty", e);
            }
        }
    }

    private boolean isConnected() {
        return mOverlay != null;
    }

    public final void startScroll() {
        if (isConnected()) {
            try {
                mOverlay.startScroll();
            } catch (RemoteException ignored) {
            }
        }
    }

    public final void endScroll() {
        if (isConnected()) {
            try {
                mOverlay.endScroll();
            } catch (RemoteException ignored) {
            }
        }
    }

    public final void setScroll(float f) {
        if (isConnected()) {
            try {
                mOverlay.onScroll(f);
            } catch (RemoteException ignored) {
            }
        }
    }

    private int verifyAndGetAnimationFlags(int duration) {
        if ((duration <= 0) || (duration > 2047)) {
            throw new IllegalArgumentException("Invalid duration");
        }
        return 0x1 | duration << 2;
    }

    public final void hideOverlay(boolean feedRunning) {
        if (mOverlay != null) {
            try {
                mOverlay.closeOverlay(feedRunning ? 1 : 0);
            } catch (RemoteException ignored) {
            }
        }
    }

    public final void hideOverlay(int duration) {
        if (mOverlay != null) {
            try {
                mOverlay.closeOverlay(verifyAndGetAnimationFlags(duration));
            } catch (RemoteException ignored) {
            }
        }
    }

    // Only used for accessibility
    public final void showOverlay(boolean feedRunning) {
        if (mOverlay != null) {
            try {
                mOverlay.openOverlay(feedRunning ? 1 : 0);
            } catch (RemoteException ignored) {
            }
        }
    }

    public final boolean startSearch(byte[] bArr, Bundle bundle) {
        if (apiVersion >= 6 && mOverlay != null) {
            try {
                return mOverlay.startSearch(bArr, bundle);
            } catch (Throwable e) {
                Log.e(TAG, "Error starting session for search", e);
            }
        }
        return false;
    }

    public final void redraw(Bundle layoutBundle) {
        mLayoutBundle = layoutBundle;
        if (mLayoutParams != null && apiVersion >= 7) {
            exchangeConfig();
        }
    }

    public final void redraw() {
        if (mLayoutParams != null && apiVersion >= 7) {
            exchangeConfig();
        }
    }

    final void setOverlay(ILauncherOverlay overlay) {
        mOverlay = overlay;
        if (mOverlay == null) {
            setServiceState(0);
        } else if (mLayoutParams != null) {
            exchangeConfig();
        }
    }

    void setServiceState(int serviceState) {
        if (mServiceState != serviceState) {
            Log.i(TAG, "overlay status changed: 0x" + Integer.toHexString(serviceState)
                    + " (scroll events " + (((serviceState & 1) != 0) ? "accepted)" : "dropped)"));
            mServiceState = serviceState;
            mScrollCallback.onServiceStateChanged((serviceState & 1) != 0);
        } else {
            // LC-Diag: without this line an overlay that answers with the status we already have
            // is indistinguishable from an overlay that never answers at all, which is exactly
            // the confusion that hid the provider-switch bug.
            Log.i(TAG, "overlay status unchanged: 0x" + Integer.toHexString(serviceState));
        }
    }

    static Intent getIntent(Context context, boolean proxy) {
        BridgeInfo bridgeInfo = proxy ? FeedBridge.Companion.getInstance(context).resolveBridge() : null;
        String pkg = context.getPackageName();
        return new Intent("com.android.launcher3.WINDOW_OVERLAY")
                .setPackage(bridgeInfo != null ? bridgeInfo.getPackageName() : FeedBridge.GOOGLE_APP_PACKAGE)
                .setData(Uri.parse("app://" +
                                pkg +
                                ":" +
                                Process.myUid())
                        .buildUpon()
                        .appendQueryParameter("v", Integer.toString(7))
                        .appendQueryParameter("cv", Integer.toString(9))
                        .build());
    }

    /**
     * Reads {@code service.api.version} from the package we are actually going to bind to.
     * <p>
     * This used to always resolve against the Google app, which is wrong when a bridge is in
     * play: the launcher would negotiate whatever protocol version the Google app advertises,
     * even though every call goes through the bridge. Open Launcher Feed declares 7.
     */
    private static void loadApiVersion(Context context) {
        Intent intent = getIntent(context, FeedBridge.useBridge(context));
        ResolveInfo resolveService = context.getPackageManager()
                .resolveService(intent, PackageManager.GET_META_DATA);
        apiVersion = resolveService == null || resolveService.serviceInfo.metaData == null ?
                1 :
                resolveService.serviceInfo.metaData.getInt("service.api.version", 1);
        Log.i(TAG, "overlay api version " + apiVersion + " from " + intent.getPackage()
                + (resolveService == null ? " (service not found)" : ""));
    }
}

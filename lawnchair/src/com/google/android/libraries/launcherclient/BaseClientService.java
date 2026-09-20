package com.google.android.libraries.launcherclient;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;
import app.lawnchair.FeedBridge;

public class BaseClientService implements ServiceConnection {
    private static final String TAG = "LauncherClient";

    private boolean mConnected;
    private final Context mContext;
    private final int mFlags;

    /**
     * The {@link ServiceConnection} handed to {@code bindService()}. It has to be re-chosen on
     * every {@link #connect()}: whether we talk to the feed companion (which answers with
     * {@code amirz.aidlbridge.IBridge}) or straight to the Google app (which answers with
     * {@code ILauncherOverlay}) depends on whether the companion is installed *right now*.
     * <p>
     * LC-Fix: this used to be decided once in the constructor, so installing Open Launcher Feed
     * while the launcher was already running left us binding the companion but reading its
     * {@code IBridge} binder as if it were an overlay — every overlay call then went nowhere and
     * the feed stayed dead until the launcher process was restarted.
     */
    private ServiceConnection mBridge;

    BaseClientService(Context context, int flags) {
        mContext = context;
        mFlags = flags;
    }

    public final boolean connect() {
        if (!mConnected) {
            boolean useBridge = FeedBridge.useBridge(mContext);
            Intent intent = LauncherClient.getIntent(mContext, useBridge);
            // LauncherClientBridge copes with both a direct ILauncherOverlay and an IBridge proxy,
            // so it is safe to always use it when a bridge package is resolvable.
            mBridge = useBridge ? new LauncherClientBridge(this, mFlags) : this;
            try {
                mConnected = mContext.bindService(intent, mBridge, mFlags);
                Log.i(TAG, "bindService(" + intent.getPackage() + ", data=" + intent.getData()
                        + ", flags=" + mFlags + ", bridge=" + useBridge + ") = " + mConnected);
            } catch (Throwable e) {
                Log.e(TAG, "Unable to connect to overlay service " + intent.getPackage(), e);
            }
        }
        return mConnected;
    }

    public final void disconnect() {
        if (mConnected) {
            // Unbind with the very same ServiceConnection instance we bound with.
            mContext.unbindService(mBridge);
            mConnected = false;
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
    }
}

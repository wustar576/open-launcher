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
    private final ServiceConnection mBridge;

    BaseClientService(Context context, int flags) {
        mContext = context;
        mFlags = flags;
        // LauncherClientBridge copes with both a direct ILauncherOverlay and an IBridge proxy,
        // so it is safe to always use it when any bridge package is resolvable.
        mBridge = FeedBridge.useBridge(context)
                ? new LauncherClientBridge(this, flags)
                : this;
    }

    public final boolean connect() {
        if (!mConnected) {
            Intent intent = LauncherClient.getIntent(mContext, FeedBridge.useBridge(mContext));
            try {
                mConnected = mContext.bindService(intent, mBridge, mFlags);
                Log.i(TAG, "bindService(" + intent.getPackage() + ", data=" + intent.getData()
                        + ", flags=" + mFlags + ") = " + mConnected);
            } catch (Throwable e) {
                Log.e(TAG, "Unable to connect to overlay service " + intent.getPackage(), e);
            }
        }
        return mConnected;
    }

    public final void disconnect() {
        if (mConnected) {
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

package com.google.android.libraries.launcherclient;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import amirz.aidlbridge.IBridge;
import amirz.aidlbridge.IBridgeCallback;

/**
 * Handles both shapes a feed provider may take (see the spec, section 8.3):
 *
 * <ul>
 *   <li>(A) the service hands back {@code ILauncherOverlay} directly, or</li>
 *   <li>(B) the service hands back {@code amirz.aidlbridge.IBridge}, we ask it to bind on our
 *       behalf, and it returns the real overlay binder through {@link IBridgeCallback}.</li>
 * </ul>
 *
 * Note that {@link IBridgeCallback} and {@link ServiceConnection} happen to declare identical
 * method signatures, so the two methods below serve both roles. Which one is being invoked is
 * told apart by the interface descriptor of the binder we are handed.
 */
public class LauncherClientBridge extends IBridgeCallback.Stub implements ServiceConnection {
    private static final String TAG = "LauncherClientBridge";
    private static final String INTERFACE_DESCRIPTOR = "amirz.aidlbridge.IBridge";

    private final BaseClientService mClientService;
    private final int mFlags;
    private ComponentName mConnectionName;

    public LauncherClientBridge(BaseClientService launcherClientService, int flags) {
        mClientService = launcherClientService;
        mFlags = flags;
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        try {
            String descriptor = service.getInterfaceDescriptor();
            if (INTERFACE_DESCRIPTOR.equals(descriptor)) {
                Log.i(TAG, "bound to bridge " + name + ", asking it to connect on our behalf");
                IBridge bridge = IBridge.Stub.asInterface(service);
                try {
                    bridge.bindService(this, mFlags);
                } catch (RemoteException e) {
                    Log.e(TAG, "bridge.bindService failed", e);
                }
            } else {
                Log.i(TAG, "got overlay binder from " + name + " (" + descriptor + ")");
                if (descriptor.isEmpty()) {
                    // LC-Note: an empty descriptor means the remote answered INTERFACE_TRANSACTION
                    // with nothing, which we have seen when the Google app is handing out an
                    // overlay binder while its process is still coming back up. Calls on it are
                    // silently dropped, so say so rather than leaving a dead feed unexplained.
                    Log.w(TAG, "overlay binder from " + name + " has no interface descriptor; "
                            + "the overlay will most likely not respond");
                }
                mClientService.onServiceConnected(name, service);
                mConnectionName = name;
            }
        } catch (RemoteException e) {
            Log.e(TAG, "unable to read the interface descriptor of " + name, e);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Log.i(TAG, "disconnected from " + name);
        if (mConnectionName != null) {
            mClientService.onServiceDisconnected(mConnectionName);
            mConnectionName = null;
        }
    }
}

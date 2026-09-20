/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Independent interface description, written from scratch for interoperability.
 *
 * This file is NOT copied from, and was not derived from, any third-party
 * source. It is an original description of an existing IPC wire protocol,
 * reconstructed solely from the protocol table in
 * docs/specs/open-launcher-spec.md (section 8.4) so that Open Launcher Feed can
 * interoperate with the overlay service that Open Launcher already speaks to.
 *
 * The package name, interface name, method names, argument types and — above
 * all — the method ORDER are dictated by the wire format: in AIDL the
 * declaration order defines the Binder transaction codes. They are facts about
 * the protocol, not creative expression, and must match exactly or no call
 * would reach its intended handler.
 *
 * Transaction codes are FIRST_CALL_TRANSACTION + (index below - 1):
 *   1 startScroll             10 requestVoiceDetection
 *   2 onScroll                11 getVoiceSearchLanguage
 *   3 endScroll               12 isVoiceDetectionRunning
 *   4 windowAttached          13 hasOverlayContent
 *   5 windowDetached          14 windowAttached2
 *   6 closeOverlay            15 unusedMethod (placeholder, must exist)
 *   7 onPause                 16 setActivityState
 *   8 onResume                17 startSearch
 *   9 openOverlay
 */

package com.google.android.libraries.launcherclient;

import android.view.WindowManager.LayoutParams;
import com.google.android.libraries.launcherclient.ILauncherOverlayCallback;

interface ILauncherOverlay {

    oneway void startScroll();

    oneway void onScroll(in float progress);

    oneway void endScroll();

    oneway void windowAttached(in LayoutParams lp, in ILauncherOverlayCallback cb, in int flags);

    oneway void windowDetached(in boolean isChangingConfigurations);

    oneway void closeOverlay(in int flags);

    oneway void onPause();

    oneway void onResume();

    oneway void openOverlay(in int flags);

    oneway void requestVoiceDetection(in boolean start);

    String getVoiceSearchLanguage();

    boolean isVoiceDetectionRunning();

    boolean hasOverlayContent();

    oneway void windowAttached2(in Bundle bundle, in ILauncherOverlayCallback cb);

    oneway void unusedMethod();

    oneway void setActivityState(in int flags);

    boolean startSearch(in byte[] data, in Bundle bundle);

}

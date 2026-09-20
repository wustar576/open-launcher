/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Independent interface description, written from scratch for interoperability.
 *
 * Reconstructed solely from the protocol table in
 * docs/specs/open-launcher-spec.md (section 8.4). Not copied from any
 * third-party source. The package name, interface name and method order are
 * part of the wire format and must match exactly.
 *
 * Transaction codes: 1 overlayScrollChanged, 2 overlayStatusChanged.
 * The overlay must report overlayStatusChanged with bit0 = 1 after attaching,
 * otherwise the launcher discards every scroll event.
 */

package com.google.android.libraries.launcherclient;

interface ILauncherOverlayCallback {

    oneway void overlayScrollChanged(float progress);

    oneway void overlayStatusChanged(int status);

}

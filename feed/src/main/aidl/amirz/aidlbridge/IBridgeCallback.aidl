/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Independent interface description, written from scratch for interoperability.
 *
 * Reconstructed solely from the protocol table in
 * docs/specs/open-launcher-spec.md (section 8.3). Not copied from any
 * third-party source. Names and method order are part of the wire format.
 *
 * Transaction codes: 1 onServiceConnected, 2 onServiceDisconnected.
 */

package amirz.aidlbridge;

interface IBridgeCallback {

    oneway void onServiceConnected(in ComponentName name, in IBinder service);

    oneway void onServiceDisconnected(in ComponentName name);

}

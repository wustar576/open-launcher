/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Independent interface description, written from scratch for interoperability.
 *
 * Reconstructed solely from the protocol table in
 * docs/specs/open-launcher-spec.md (section 8.3). Not copied from, and not
 * derived from, any third-party source.
 *
 * The package name `amirz.aidlbridge` and the interface name `IBridge` are NOT
 * a choice: the launcher identifies which of the two possible interfaces it was
 * handed by comparing IBinder.getInterfaceDescriptor() against this exact
 * string. Using any other name would make the handshake fail. The single method
 * and its argument order likewise define transaction code 1.
 */

package amirz.aidlbridge;

import amirz.aidlbridge.IBridgeCallback;

interface IBridge {
    oneway void bindService(in IBridgeCallback cb, in int flags);
}

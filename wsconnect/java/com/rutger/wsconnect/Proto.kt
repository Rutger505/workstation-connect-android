// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect

import java.util.UUID

/**
 * Control channel between laptop (client) and phone (server) over classic Bluetooth RFCOMM.
 * Keep in sync with laptop/src/proto.rs. Secure socket: only bonded laptops, encrypted link.
 *
 * Framing, both directions: [len: u8][payload: len bytes]; len 0 = keepalive, ignored.
 * - laptop → phone: 1 byte command ([CMD_OFF], [CMD_ON], [CMD_NET_CHANGED], [CMD_PING]);
 *   retired and unknown commands are ignored
 * - phone → laptop: 4 bytes state [flags, battery %, signal level 0-4 (255 unknown), [VERSION]],
 *   sent right after accept, on every change and as the reply to [CMD_PING]
 */
object Proto {
    /** RFCOMM service record UUID; the laptop finds the channel via SDP. */
    val SERVICE: UUID = UUID.fromString("6f2b0010-3c1d-4e8a-9f41-7a5d2c9e0b10")

    const val VERSION: Byte = 5

    const val CMD_OFF: Byte = 0     // stop the hotspot
    const val CMD_ON: Byte = 1      // start the hotspot
    const val CMD_NET_CHANGED: Byte = 3 // laptop's Wi-Fi network changed/dropped: drop KDE Connect LAN links to it
    const val CMD_PING: Byte = 5    // reply with a state frame
    // Retired in v4 (Bluetooth tethering removed): no-op when received, never reuse.
    const val CMD_RETIRED_BT_OFF: Byte = 2
    const val CMD_RETIRED_ON_BT: Byte = 4

    const val FLAG_SHIZUKU = 1      // Shizuku helper bound, commands can work
    // 2: was "bt-pan tethered" (< v4), always 0 now, never reuse
    const val FLAG_HOTSPOT = 4      // Wi-Fi hotspot tethered
    const val FLAG_WANTED = 8       // laptop asked for "on" and hasn't said "off"
    const val FLAG_ERROR = 16       // last tether command failed (details in app log)
}

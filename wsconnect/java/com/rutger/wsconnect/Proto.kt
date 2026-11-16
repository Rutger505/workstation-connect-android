// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect

import java.util.UUID

/**
 * BLE control channel between laptop (GATT client) and phone (GATT server). Keep in sync with
 * laptop/src/proto.rs. Both characteristics need an encrypted link, so only bonded laptops work.
 */
object Proto {
    val SERVICE: UUID = UUID.fromString("6f2b0001-3c1d-4e8a-9f41-7a5d2c9e0b10")
    /** write, 1 byte: [CMD_OFF], [CMD_ON], [CMD_BT_OFF], [CMD_NET_CHANGED]; unknown commands are ignored */
    val CONTROL: UUID = UUID.fromString("6f2b0002-3c1d-4e8a-9f41-7a5d2c9e0b10")
    /** read + notify, 4 bytes: [flags, battery %, signal level 0-4 (255 unknown), protocol version] */
    val STATE: UUID = UUID.fromString("6f2b0003-3c1d-4e8a-9f41-7a5d2c9e0b10")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val VERSION: Byte = 2

    const val CMD_OFF: Byte = 0     // stop BT tethering + hotspot
    const val CMD_ON: Byte = 1      // start BT tethering + hotspot
    const val CMD_BT_OFF: Byte = 2  // laptop is on the hotspot: stop BT tethering only
    const val CMD_NET_CHANGED: Byte = 3 // laptop's Wi-Fi network changed/dropped: drop KDE Connect LAN links to it

    const val FLAG_SHIZUKU = 1      // Shizuku helper bound, commands can work
    const val FLAG_BT_TETHER = 2    // bt-pan tethered
    const val FLAG_HOTSPOT = 4      // Wi-Fi hotspot tethered
    const val FLAG_WANTED = 8       // laptop asked for "on" and hasn't said "off"
    const val FLAG_ERROR = 16       // last tether command failed (details in app log)
}

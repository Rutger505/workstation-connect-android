// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import java.util.UUID

/**
 * Control channel between laptop (client) and phone (server) over classic Bluetooth RFCOMM.
 * Keep in sync with laptop/src/proto.rs. Secure socket: only bonded laptops, encrypted link.
 *
 * Framing, both directions: [len: u8][payload: len bytes]; len 0 = keepalive, ignored.
 * - laptop → phone: command byte first ([CMD_OFF], [CMD_ON], [CMD_NET_CHANGED], [CMD_PING]: 1 byte;
 *   [CMD_TAILSCALE_ADDR]: [6, a, b, c, d]); retired and unknown commands are ignored
 * - phone → laptop: 8 bytes state [flags, battery %, signal level 0-4 (255 unknown), [VERSION], a, b, c, d]
 *   (a.b.c.d = phone's Tailscale IPv4, network order, 0.0.0.0 = none), sent right after accept, on every
 *   change and as the reply to [CMD_PING]
 */
object Proto {
    /** RFCOMM service record UUID; the laptop finds the channel via SDP. */
    val SERVICE: UUID = UUID.fromString("6f2b0010-3c1d-4e8a-9f41-7a5d2c9e0b10")

    const val VERSION: Byte = 6

    const val CMD_OFF: Byte = 0     // stop the hotspot
    const val CMD_ON: Byte = 1      // start the hotspot
    const val CMD_NET_CHANGED: Byte = 3 // laptop's Wi-Fi network changed/dropped: drop KDE Connect LAN links to it
    const val CMD_PING: Byte = 5    // reply with a state frame
    const val CMD_TAILSCALE_ADDR: Byte = 6 // + 4 bytes: laptop's Tailscale IPv4, 0.0.0.0 = none (v6)
    // Retired in v4 (Bluetooth tethering removed): no-op when received, never reuse.
    const val CMD_RETIRED_BT_OFF: Byte = 2
    const val CMD_RETIRED_ON_BT: Byte = 4

    const val FLAG_SHIZUKU = 1      // Shizuku helper bound, commands can work
    // 2: was "bt-pan tethered" (< v4), always 0 now, never reuse
    const val FLAG_HOTSPOT = 4      // Wi-Fi hotspot tethered
    const val FLAG_WANTED = 8       // laptop asked for "on" and hasn't said "off"
    const val FLAG_ERROR = 16       // last tether command failed (details in app log)

    /** Command byte → name, for logs. */
    fun cmdName(cmd: Byte): String = when (cmd) {
        CMD_OFF -> "off"
        CMD_ON -> "on"
        CMD_NET_CHANGED -> "net-changed"
        CMD_PING -> "ping"
        CMD_TAILSCALE_ADDR -> "tailscale-addr"
        CMD_RETIRED_BT_OFF, CMD_RETIRED_ON_BT -> "retired($cmd)"
        else -> "unknown($cmd)"
    }

    /** State frame → "flags=shizuku,hotspot battery=80 signal=3 v6 ts=100.64.0.2", for logs. */
    fun stateString(state: ByteArray): String {
        if (state.size < 4) return "short state frame (${state.size} bytes)"
        val f = state[0].toInt() and 0xff
        val flags = listOf(FLAG_SHIZUKU to "shizuku", FLAG_HOTSPOT to "hotspot", FLAG_WANTED to "wanted", FLAG_ERROR to "error")
            .filter { f and it.first != 0 }.joinToString(",") { it.second }.ifEmpty { "none" }
        val signal = state[2].toInt() and 0xff
        return "flags=$flags battery=${state[1].toInt() and 0xff} signal=${if (signal == 255) "?" else signal} v${state[3]}" +
            if (state.size >= 8) " ts=${TailscaleLogic.host(state.copyOfRange(4, 8)) ?: "none"}" else ""
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import android.content.Context
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.backends.BaseLink
import org.kde.kdeconnect.backends.bluetooth.BluetoothLink
import org.kde.kdeconnect.backends.lan.LanLink
import org.kde.kdeconnect.helpers.ThreadHelper

/**
 * KDE Connect links of a [Device]. Upstream keeps them in the private `Device.links` (sorted by
 * provider priority, LAN 20 > Bluetooth 10; sendPacket uses the first that accepts a packet), read
 * here by reflection instead of adding an upstream hook. Recheck the field on upstream merges:
 * if it's gone, [of] logs it once and returns no links.
 */
object Links {
    enum class Via { WIFI, BLUETOOTH, OTHER }

    private val field by lazy {
        runCatching { Device::class.java.getDeclaredField("links").apply { isAccessible = true } }
            .onFailure { AppLog.i("Links: Device.links not found (${it.message}), link type/LAN drop disabled") }
            .getOrNull()
    }

    fun of(d: Device): List<BaseLink> =
        (runCatching { field?.get(d) as? List<*> }.getOrNull() ?: emptyList<Any>()).filterIsInstance<BaseLink>()

    /** The link packets go over: the first one, like Device.sendPacketBlocking. Null when unreachable. */
    fun via(d: Device): Via? = when (of(d).firstOrNull()) {
        null -> null
        is LanLink -> Via.WIFI
        is BluetoothLink -> Via.BLUETOOTH
        else -> Via.OTHER
    }

    /**
     * Laptop says its Wi-Fi changed: its LAN link is probably dead (TCP only notices after ~15 min).
     * Drop it now, so the Device falls back to its Bluetooth link or becomes unreachable, then let
     * the LAN provider rediscover, so a laptop still on a shared network reconnects in seconds.
     * Only the laptop's LAN link; Bluetooth links and tethering are untouched.
     */
    fun dropLaptopLan(c: Context) = ThreadHelper.runOnNewThread {
        val d = WsConnect.laptop(c) ?: return@runOnNewThread AppLog.i("net changed: no paired laptop")
        val lan = of(d).filterIsInstance<LanLink>()
        if (lan.isEmpty()) return@runOnNewThread AppLog.i("net changed: ${d.name} has no LAN link")
        lan.forEach { link ->
            AppLog.i("net changed: dropping LAN link to ${d.name}")
            // Remove first: Device falls back right away; the read thread's later onConnectionLost is a
            // no-op (LanLinkProvider only removes its current link). Closing a dead SSL socket can block.
            link.linkProvider.onConnectionLost(link)
            link.disconnect()
        }
        lan.first().linkProvider.onNetworkChange(null)
    }
}

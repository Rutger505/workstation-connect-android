// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import android.content.Context
import org.kde.kdeconnect.BackgroundService
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * KDE Connect over Tailscale, for networks without UDP broadcast between laptop and phone (eduroam client
 * isolation). Both sides swap their Tailscale IPv4 over the Bluetooth link ([Proto]): ours goes in every
 * state frame, the laptops' arrive as [Proto.CMD_TAILSCALE_ADDR] and are stored per laptop (Bluetooth
 * address) under [PREF_KEY] in upstream's default prefs file. LanLinkProvider (hook in
 * `updateCustomDevicesList` + its pref listener) merges [laptopAddresses] into its custom devices: they get
 * our UDP identity and pass its address filter (100.64/10 isn't "private"). Upstream's own custom device
 * list (Rutger's) is never touched.
 */
object Tailscale {
    /** In `<package>_preferences`, the file LanLinkProvider watches. "<bt address>=<ip>;…" */
    const val PREF_KEY = "wsconnect_tailscale_laptops"
    private val log = AppLog.tag("tailscale")

    /** Our Tailscale IPv4 (up interface in 100.64.0.0/10, tun0), null = none. Updated by [refreshOwn]. */
    @Volatile var ownIp: Inet4Address? = null; private set

    /** Re-reads [ownIp] from the network interfaces. True when it changed. */
    fun refreshOwn(): Boolean {
        val found = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().filter { it.isUp }.firstNotNullOfOrNull { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    .firstOrNull { TailscaleLogic.isTailscale(it.address) }?.let { nif.name to it }
            }
        }.onFailure { log.w("listing network interfaces failed, own tailscale ip unchanged (${ownIp?.hostAddress ?: "none"})", it) }
            .getOrElse { return false }
        val ip = found?.second
        if (ip == ownIp) return false
        log.i("own tailscale ip: ${ownIp?.hostAddress ?: "none"} → ${ip?.hostAddress ?: "none"}${found?.let { " (${it.first})" } ?: ""}")
        ownIp = ip
        return true
    }

    /** 4 bytes for the state frame, 0.0.0.0 = none. */
    fun ownBytes(): ByteArray = ownIp?.address ?: ByteArray(4)

    private fun prefs(c: Context) = c.getSharedPreferences("${c.packageName}_preferences", Context.MODE_PRIVATE)

    /** Laptop [client] (Bluetooth address) sent its Tailscale IPv4 ([ip] 4 bytes, 0.0.0.0 = it has none). */
    fun laptopSent(c: Context, client: String, ip: ByteArray) {
        val host = TailscaleLogic.host(ip)
        if (host != null && !TailscaleLogic.isTailscale(ip)) return log.w("laptop $client sent $host, not in 100.64.0.0/10: ignored")
        val p = prefs(c)
        val raw = p.getString(PREF_KEY, "") ?: ""
        val updated = TailscaleLogic.update(raw, client, host)
            ?: return log.d("laptop $client tailscale ip ${host ?: "none"}: unchanged")
        // LanLinkProvider's listener re-reads its custom device list on this key
        p.edit().putString(PREF_KEY, updated).apply()
        log.i("laptop $client tailscale ip: ${TailscaleLogic.parse(raw)[client] ?: "none"} → ${host ?: "none"} " +
            "(${TailscaleLogic.parse(updated).size} known): rebroadcasting identity")
        BackgroundService.ForceRefreshConnections(c.applicationContext)
    }

    /** Known laptop Tailscale IPs, for LanLinkProvider's custom devices (numeric: no DNS). */
    @JvmStatic
    fun laptopAddresses(c: Context): List<InetAddress> =
        TailscaleLogic.parse(prefs(c).getString(PREF_KEY, "") ?: "").values.mapNotNull { host ->
            runCatching { InetAddress.getByName(host) }.onFailure { log.w("bad stored laptop ip $host", it) }.getOrNull()
        }

    /** Own changed while laptops are known: send our identity to them now (new source address). */
    fun rebroadcastIfLaptops(c: Context) {
        val n = TailscaleLogic.parse(prefs(c).getString(PREF_KEY, "") ?: "").size
        if (n == 0) return log.d("own ip changed, no laptop tailscale ip known: no rebroadcast")
        log.d("own ip changed: rebroadcasting identity ($n laptop ip(s))")
        BackgroundService.ForceRefreshConnections(c.applicationContext)
    }
}

/** Pure helpers (JVM unit tests). */
object TailscaleLogic {
    /** IPv4 in Tailscale's CGNAT range 100.64.0.0/10. */
    fun isTailscale(a: ByteArray): Boolean = a.size == 4 && (a[0].toInt() and 0xff) == 100 && (a[1].toInt() and 0xc0) == 64

    /** 4 bytes → dotted quad, null for 0.0.0.0 (none) or a wrong length. */
    fun host(a: ByteArray): String? =
        if (a.size != 4 || a.all { it.toInt() == 0 }) null else a.joinToString(".") { (it.toInt() and 0xff).toString() }

    /** Pref "<client>=<ip>;…" → map. Malformed entries dropped. */
    fun parse(raw: String): Map<String, String> = raw.split(';').mapNotNull { e ->
        val i = e.indexOf('=')
        if (i <= 0 || i == e.length - 1) null else e.substring(0, i) to e.substring(i + 1)
    }.toMap()

    /** [raw] with [client] set to [host] (null = removed); null when nothing changes. */
    fun update(raw: String, client: String, host: String?): String? {
        val m = parse(raw).toMutableMap()
        if (m[client] == host) return null
        if (host == null) m.remove(client) else m[client] = host
        return m.toSortedMap().entries.joinToString(";") { "${it.key}=${it.value}" }
    }
}

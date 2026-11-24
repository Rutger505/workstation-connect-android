// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.DeviceHost
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.PairingHandler.PairState
import org.kde.kdeconnect.PairingHandler.PairingCallback
import org.kde.kdeconnect.ui.CustomDevicesActivity
import java.net.InetAddress
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Pairing from our home screen:
 * - every device gets a [PairingCallback] (logs the outcome, keeps the last failure for the UI), so a
 *   pending request never dead-ends silently
 * - add by IP: stores the host in upstream's custom device list (same pref as CustomDevicesActivity, which
 *   LanLinkProvider watches), refreshes discovery and requests pairing once a device at that address shows
 *   up ([SEARCH_MS] max)
 * Main thread for state changes (handler); reads from the UI's 1 s poll.
 */
object Pairing {
    private val log = AppLog.tag("pairing")
    const val SEARCH_MS = 30_000L
    /** Upstream PairingHandler gives the peer 30 s, then falls back to NotPaired + pairingFailed(timeout). */
    const val PEER_TIMEOUT_MS = 30_000L
    private const val FAILURE_SHOWN_MS = 60_000L
    private const val TICK_MS = 1_000L
    private const val REBROADCAST_MS = 10_000L

    sealed interface ByIp {
        val host: String
        data class Searching(override val host: String, val since: Long) : ByIp
        data class Requested(override val host: String, val name: String) : ByIp
        data class AlreadyPaired(override val host: String, val name: String) : ByIp
        data class NotFound(override val host: String) : ByIp
        data class Failed(override val host: String, val message: String) : ByIp
    }

    @Volatile var byIp: ByIp? = null
        private set

    private val handler = Handler(Looper.getMainLooper())
    // per id the Device object that has our callback (Device.equals compares ids; upstream may recreate it)
    private val attached = ConcurrentHashMap<String, WeakReference<Device>>()
    private val failures = ConcurrentHashMap<String, Pair<String, Long>>() // id → message, elapsedRealtime
    private val requestedAt = ConcurrentHashMap<String, Long>() // id → elapsedRealtime of our last request
    private var search: Search? = null // main thread

    private class Search(val host: String, val since: Long, var addresses: Set<String>? = null, var lastBroadcast: Long = since)

    /** Main process (WsConnect.init), after upstream loaded the remembered devices. */
    fun init(app: Application) {
        KdeConnect.getInstance().addDeviceListChangedCallback("wsconnect-pairing") { attachAll() }
        attachAll()
        log.d("init: callbacks on ${attached.size} devices")
    }

    @Synchronized
    private fun attachAll() = KdeConnect.getInstance().devices.values.forEach { d ->
        if (attached[d.deviceId]?.get() === d) return@forEach
        attached[d.deviceId] = WeakReference(d)
        d.addPairingCallback(callback(d.deviceId, d.name))
    }

    private fun callback(id: String, name: String) = object : PairingCallback {
        override fun incomingPairRequest() {
            log.i("$name ($id): pair request from the device")
            failures.remove(id)
        }

        override fun pairingSuccessful() {
            log.i("$name ($id): paired")
            failures.remove(id)
            requestedAt.remove(id)
            handler.post { if (byIp is ByIp.Requested) byIp = null }
        }

        override fun pairingFailed(error: String) {
            log.w("$name ($id): pairing failed: $error")
            failures[id] = error to SystemClock.elapsedRealtime()
            requestedAt.remove(id)
            handler.post { (byIp as? ByIp.Requested)?.let { if (it.name == name) byIp = ByIp.Failed(it.host, "Koppelen met $name mislukt: $error") } }
        }

        override fun unpaired(device: Device) {
            log.i("$name ($id): unpaired")
            requestedAt.remove(id)
        }
    }

    /** Last pairing failure of [id] within the last minute, for the UI. */
    fun failure(id: String): String? = failures[id]?.takeIf { SystemClock.elapsedRealtime() - it.second < FAILURE_SHOWN_MS }?.first

    /** ms since our last pair request to [id] (null = none pending from us). */
    fun requestedFor(id: String): Long? = requestedAt[id]?.let { SystemClock.elapsedRealtime() - it }

    // ---- actions (UI) ----

    fun request(c: Context, id: String, source: String) {
        val d = KdeConnect.getInstance().getDevice(id) ?: return log.w("request ($source): device $id gone")
        log.i("${d.name} ($id): pair request ($source), state ${d.pairStatus}, reachable ${d.isReachable}, via ${Links.via(d)?.name?.lowercase() ?: "-"}")
        failures.remove(id)
        WsConnect.setLaptop(c, id)
        requestedAt[id] = SystemClock.elapsedRealtime()
        d.requestPairing() // RequestedByPeer → accepts theirs; unreachable → pairingFailed
    }

    fun cancel(id: String, source: String) {
        val d = KdeConnect.getInstance().getDevice(id) ?: return log.w("cancel ($source): device $id gone")
        log.i("${d.name} ($id): pairing cancelled ($source), state ${d.pairStatus}")
        requestedAt.remove(id)
        if (d.pairStatus == PairState.Requested || d.pairStatus == PairState.RequestedByPeer) d.cancelPairing()
        else log.d("${d.name}: nothing pending to cancel")
    }

    fun accept(c: Context, id: String) {
        val d = KdeConnect.getInstance().getDevice(id) ?: return log.w("accept: device $id gone")
        log.i("${d.name} ($id): accepting its pair request")
        WsConnect.setLaptop(c, id)
        d.acceptPairing()
    }

    // ---- add by IP ----

    /**
     * Adds [raw] to upstream's custom device list and starts looking for it. Returns an error message for
     * the UI (invalid input) or null.
     */
    fun addByIp(c: Context, raw: String): String? {
        val host = raw.trim()
        if (DeviceHost.toDeviceHostOrNull(host) == null) {
            log.i("add by IP: invalid input rejected (${host.length} chars)")
            return "Ongeldig IP-adres of hostnaam"
        }
        val prefs = c.getSharedPreferences("${c.packageName}_preferences", Context.MODE_PRIVATE)
        val merged = PairingLogic.addHost(prefs.getString(CustomDevicesActivity.KEY_CUSTOM_DEVICE_LIST_PREFERENCE, "") ?: "", host,
            CustomDevicesActivity.IP_DELIM)
        if (merged == null) log.i("add by IP: $host already in the custom device list")
        else {
            // LanLinkProvider listens for this key and re-reads the list
            prefs.edit().putString(CustomDevicesActivity.KEY_CUSTOM_DEVICE_LIST_PREFERENCE, merged).apply()
            log.i("add by IP: $host added to the custom device list (${CustomDevicesActivity.getCustomDeviceList(c).size} entries)")
        }
        val app = c.applicationContext
        handler.post { startSearch(app, host) }
        return null
    }

    private fun startSearch(c: Context, host: String) {
        val now = SystemClock.elapsedRealtime()
        val s = Search(host, now)
        search = s
        byIp = ByIp.Searching(host, now)
        log.i("add by IP: looking for a device at $host (max ${SEARCH_MS / 1000} s)")
        Thread({
            val addrs = runCatching { InetAddress.getAllByName(host).map { it.hostAddress }.toSet() }
                .onFailure { log.w("add by IP: resolving $host failed", it) }.getOrNull()
            handler.post {
                if (search !== s) return@post
                if (addrs == null) return@post finish(ByIp.Failed(host, "Kan $host niet opzoeken"))
                s.addresses = addrs
                log.d("add by IP: $host = ${addrs.joinToString()}")
            }
        }, "wsconnect-resolve").start()
        BackgroundService.ForceRefreshConnections(c)
        handler.postDelayed({ tick(c, s) }, TICK_MS)
    }

    private fun tick(c: Context, s: Search) {
        if (search !== s) return
        val now = SystemClock.elapsedRealtime()
        val addrs = s.addresses
        if (addrs != null) {
            val match = KdeConnect.getInstance().devices.values.firstOrNull { d ->
                d.isReachable && Links.lanAddresses(d).any { it.hostAddress in addrs }
            }
            if (match != null) return found(c, s, match)
        }
        if (now - s.since >= SEARCH_MS) {
            val reachable = KdeConnect.getInstance().devices.values.count { it.isReachable }
            log.w("add by IP: no device at ${s.host} within ${SEARCH_MS / 1000} s ($reachable reachable devices elsewhere)")
            return finish(ByIp.NotFound(s.host))
        }
        if (now - s.lastBroadcast >= REBROADCAST_MS) {
            log.d("add by IP: ${s.host} not seen yet, refreshing discovery again")
            s.lastBroadcast = now
            BackgroundService.ForceRefreshConnections(c) // UDP identity can get lost
        }
        handler.postDelayed({ tick(c, s) }, TICK_MS)
    }

    private fun found(c: Context, s: Search, d: Device) {
        val ms = SystemClock.elapsedRealtime() - s.since
        if (d.isPaired) {
            log.i("add by IP: ${s.host} is ${d.name}, already paired (found after $ms ms)")
            WsConnect.setLaptop(c, d.deviceId)
            return finish(ByIp.AlreadyPaired(s.host, d.name))
        }
        log.i("add by IP: ${s.host} is ${d.name} (found after $ms ms, state ${d.pairStatus}): requesting pairing")
        finish(ByIp.Requested(s.host, d.name))
        request(c, d.deviceId, "add by IP")
    }

    private fun finish(result: ByIp) {
        search = null
        byIp = result
    }

    /** UI: hide the add-by-IP result line. */
    fun dismissByIp() {
        if (search != null) return log.d("add by IP: dismiss ignored while searching")
        byIp?.let { log.d("add by IP: result for ${it.host} dismissed") }
        byIp = null
    }
}

/** Pure helpers (JVM unit tests). */
object PairingLogic {
    /** Upstream's custom device list pref ([raw], hosts joined by [delim]) with [host] added, null if already in. */
    fun addHost(raw: String, host: String, delim: String): String? {
        val hosts = raw.split(delim).filter { it.isNotEmpty() }
        if (host in hosts) return null
        return (hosts + host).sorted().joinToString(delim)
    }

    /** Seconds left of a [totalMs] wait after [elapsedMs], never negative. */
    fun secondsLeft(totalMs: Long, elapsedMs: Long): Long = ((totalMs - elapsedMs).coerceAtLeast(0) + 999) / 1000
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.rutger.workstationconnect.logs.LogUpload
import com.rutger.workstationconnect.logs.LogUploadLogic.Reason
import com.rutger.workstationconnect.ui.HomeActivity
import com.rutger.workstationconnect.update.Updater
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.helpers.NotificationHelper
import java.io.DataInputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service: classic Bluetooth RFCOMM server the laptop(s) connect to (bonded + encrypted
 * only, see [Proto]). Turns the Wi-Fi hotspot on/off on command and pushes tethering flags, battery
 * and signal to every client. If the last client disappears while tethering is wanted, turns it off
 * after [GONE_TIMEOUT_MS].
 */
@SuppressLint("MissingPermission") // HomeActivity requests BLUETOOTH_CONNECT before enabling the link
class LinkService : Service() {

    private val log = AppLog.tag("link")
    private val main = Handler(Looper.getMainLooper())
    private lateinit var bt: BluetoothManager

    // Server socket + accept thread. [generation] bumps on every stop, so a stale accept thread
    // (its socket closed by us) exits quietly instead of scheduling a restart.
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var generation = 0
    private var backoffMs = BACKOFF_MIN_MS
    private val restart = Runnable { startServer() }

    private val clients = CopyOnWriteArrayList<Client>()
    @Volatile private var battery = 0
    @Volatile private var signal = 255
    private var lastSent: ByteArray? = null

    private val goneTimeout = Runnable {
        if (Tether.wanted && clients.isEmpty()) {
            log.i("laptop gone ${GONE_TIMEOUT_MS / 60000} min: tethering off")
            Tether.off("laptop gone")
        } else log.d("gone timeout: nothing to do (wanted=${Tether.wanted}, ${clients.size} connected)")
    }

    private val tetherListener: () -> Unit = { main.post { pushState(); checkShizuku(); armGoneTimeout() } }

    /** Every [TICK_MS] while the service runs (WorkManager's minimum is 15 min): update check + periodic log upload. */
    private val tick = object : Runnable {
        override fun run() {
            Updater.checkAsync()
            refreshTailscale() // backup for a missed VPN callback
            LogUpload.trigger(Reason.PERIODIC)
            main.postDelayed(this, TICK_MS)
        }
    }

    private val shizukuWarning = Runnable(::showShizukuWarning)
    private var shizukuWarned = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_BATTERY_CHANGED -> {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                    if (level >= 0) battery = level * 100 / scale
                    pushState()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED ->
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        // Always reopen on ON: after a stack restart without TURNING_OFF the old
                        // server socket may be dead but non-null.
                        BluetoothAdapter.STATE_ON -> { log.i("bluetooth on: rfcomm server (re)start"); stopServer(); startServer() }
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                            if (server != null || clients.isNotEmpty()) log.i("bluetooth off: rfcomm server stopped, ${clients.size} client(s) closed")
                            stopServer()
                            closeClients("bluetooth off")
                        }
                        else -> log.d("bluetooth state ${intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)}: ignored")
                    }
            }
        }
    }

    private val signalCallback = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
        override fun onSignalStrengthsChanged(s: SignalStrength) {
            signal = s.level
            main.post(::pushState)
        }
    }

    /** VPN networks (Tailscale): our Tailscale IP may have changed → state frame + identity rebroadcast. */
    private val vpnCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post(::refreshTailscale) }
        override fun onLost(network: Network) { main.post(::refreshTailscale); main.postDelayed(::refreshTailscale, 2_000) } // tun0 may linger
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { main.post(::refreshTailscale) }
    }

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        log.i("service create")
        running = true
        startForeground(StatusNotification.ID, StatusNotification.build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        // BackgroundService refreshes on device changes itself; cover the time it isn't running
        KdeConnect.getInstance().addDeviceListChangedCallback(DEVICES_CALLBACK) {
            if (BackgroundService.instance == null) StatusNotification.refresh(this)
        }
        deleteSharedPreferences(OLD_PREFS) // BLE era: laptop_addresses
        bt = getSystemService(BluetoothManager::class.java)
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }, RECEIVER_EXPORTED)
        runCatching {
            getSystemService(TelephonyManager::class.java).registerTelephonyCallback(mainExecutor, signalCallback)
        }.onFailure { log.w("signal listener not registered, signal stays unknown", it) }
        Tailscale.refreshOwn()
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), vpnCallback)
        }.onFailure { log.w("vpn callback not registered, own tailscale ip only checked every ${TICK_MS / 60000} min", it) }
        Tether.listen(tetherListener)
        Tether.bind()
        checkShizuku()
        if (bt.adapter?.isEnabled == true) startServer() else log.i("bluetooth is off: waiting")
        main.postDelayed(tick, TICK_MS) // Updater.init already checked at app start
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        log.d("start command (startId $startId${if (intent == null) ", sticky restart" else ""})")
        return START_STICKY
    }

    override fun onDestroy() {
        log.i("service destroy (${clients.size} client(s) closed)")
        running = false
        main.removeCallbacks(tick)
        main.removeCallbacks(goneTimeout)
        main.removeCallbacks(shizukuWarning)
        cancelShizukuWarning()
        Tether.unlisten(tetherListener)
        runCatching { unregisterReceiver(receiver) }.onFailure { log.w("unregister receiver", it) }
        runCatching { getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(signalCallback) }
            .onFailure { log.d("unregister signal listener: ${it.javaClass.simpleName}") } // never registered
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(vpnCallback) }
            .onFailure { log.d("unregister vpn callback: ${it.javaClass.simpleName}") } // never registered
        stopServer()
        closeClients("service stopped")
        clientLabels = emptyList()
        KdeConnect.getInstance().removeDeviceListChangedCallback(DEVICES_CALLBACK)
        // Shared notification stays while BackgroundService holds it: show the link as off. Without it the
        // system already removed it; refresh() is then a no-op (running = false), so no orphan
        StatusNotification.refresh(this)
        super.onDestroy()
    }

    // ---- server ----

    /** Main thread only. Opens the SDP record + server socket and starts the accept loop. */
    private fun startServer() {
        main.removeCallbacks(restart)
        if (server != null || !running) return log.d("start server: skipped (${if (!running) "service stopped" else "already listening"})")
        if (bt.adapter?.isEnabled != true) return log.i("bluetooth is off: waiting")
        val s = try {
            bt.adapter.listenUsingRfcommWithServiceRecord(SDP_NAME, Proto.SERVICE)
        } catch (e: Exception) { // IOException, or SecurityException without BLUETOOTH_CONNECT
            log.e("rfcomm listen failed", e)
            return scheduleRestart()
        }
        server = s
        listening = true
        val gen = generation
        log.i("rfcomm server listening (generation $gen)")
        Thread({ acceptLoop(s, gen) }, "wsconnect-accept").start()
    }

    private fun acceptLoop(s: BluetoothServerSocket, gen: Int) {
        while (true) {
            val socket = try {
                s.accept()
            } catch (e: IOException) {
                main.post {
                    if (gen != generation) return@post log.d("accept loop $gen ended: server stopped on purpose")
                    log.w("rfcomm accept failed", e)
                    stopServer()
                    scheduleRestart()
                }
                return
            }
            main.post { accepted(socket, gen) }
        }
    }

    /** Main thread only. Retries [startServer] with exponential backoff while Bluetooth is on. */
    private fun scheduleRestart() {
        if (!running || bt.adapter?.isEnabled != true) return log.d("server restart: skipped (running=$running, bluetooth off)")
        log.i("rfcomm server restart in ${backoffMs / 1000} s")
        main.removeCallbacks(restart)
        main.postDelayed(restart, backoffMs)
        backoffMs = (backoffMs * 2).coerceAtMost(BACKOFF_MAX_MS)
    }

    /** Main thread only. Closes the server socket (not the clients). */
    private fun stopServer() {
        main.removeCallbacks(restart)
        generation++
        server?.let { s -> runCatching { s.close() }.onFailure { log.d("server socket close: $it") } }
        server = null
        listening = false
    }

    // ---- clients ----

    /** Main thread only. */
    private fun accepted(socket: BluetoothSocket, gen: Int) {
        val device = socket.remoteDevice
        if (gen != generation || !running) {
            log.d("accepted ${device.address} from a stopped server: closed")
            runCatching { socket.close() }.onFailure { log.d("socket close: $it") }
            return
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) { // secure socket: shouldn't happen
            log.w("rfcomm: rejected unbonded ${device.address}")
            runCatching { socket.close() }.onFailure { log.d("socket close: $it") }
            return
        }
        backoffMs = BACKOFF_MIN_MS
        val c = Client(socket, device.address, "${device.address}/${runCatching { device.name }.getOrNull() ?: "?"}")
        clients.add(c)
        clientsChanged()
        main.removeCallbacks(goneTimeout)
        log.i("laptop connected ${c.label} (${clients.size} connected)")
        LogUpload.trigger(Reason.LINK_CONNECT)
        val state = stateBytes()
        log.d("state → ${c.label}: ${Proto.stateString(state)} (initial)")
        c.send(state)
        c.startReader()
    }

    /** Main thread only. */
    private fun removed(c: Client) {
        if (!clients.remove(c)) return
        clientsChanged()
        val secs = (System.currentTimeMillis() - c.since) / 1000
        log.i("laptop disconnected ${c.label}: ${c.reason} after ${secs} s, ${c.received} command(s) (${clients.size} connected)")
        LogUpload.trigger(Reason.LINK_LOST)
        if (running && clients.isEmpty() && Tether.wanted) {
            log.i("last laptop gone while hotspot wanted: off in ${GONE_TIMEOUT_MS / 60000} min unless one reconnects")
            main.postDelayed(goneTimeout, GONE_TIMEOUT_MS)
        }
    }

    /**
     * Main thread only. Wanted without any laptop connected (hotspot restored after an update before the
     * laptop reconnected): same [GONE_TIMEOUT_MS] as a disconnect.
     */
    private fun armGoneTimeout() {
        if (running && clients.isEmpty() && Tether.wanted && !main.hasCallbacks(goneTimeout)) {
            log.i("hotspot wanted without a laptop: off in ${GONE_TIMEOUT_MS / 60000} min unless one connects")
            main.postDelayed(goneTimeout, GONE_TIMEOUT_MS)
        }
    }

    private fun closeClients(reason: String) = clients.forEach { it.close(reason) }

    /** Main thread only. */
    private fun command(c: Client, payload: ByteArray) {
        val cmd = payload[0]
        if (cmd != Proto.CMD_PING) c.received++
        when (cmd) {
            Proto.CMD_ON -> { log.i("laptop: on (${c.label})"); Tether.on("laptop") }
            Proto.CMD_OFF -> { log.i("laptop: off (${c.label})"); Tether.off("laptop") }
            Proto.CMD_NET_CHANGED -> { log.i("laptop: net changed (${c.label})"); Links.dropLaptopLan(this) }
            Proto.CMD_PING -> { // the laptop's liveness check: only the first per client is logged
                if (!c.pinged) { c.pinged = true; log.d("first ping from ${c.label}") }
                c.send(stateBytes())
            }
            Proto.CMD_TAILSCALE_ADDR ->
                if (payload.size < 5) log.w("laptop: tailscale-addr frame of ${payload.size} bytes ignored (${c.label})")
                else Tailscale.laptopSent(this, c.address, payload.copyOfRange(1, 5))
            Proto.CMD_RETIRED_BT_OFF, Proto.CMD_RETIRED_ON_BT ->
                log.w("laptop: ${Proto.cmdName(cmd)} command ignored (${c.label}, old laptop app?)")
            else -> log.w("laptop: ${Proto.cmdName(cmd)} command ignored (${c.label}, newer laptop app?)")
        }
    }

    /** Main thread only. */
    private fun clientsChanged() {
        if (!running) return // onDestroy cleared it; don't clobber a newer instance
        clientLabels = clients.map { it.label }
        StatusNotification.refresh(this)
    }

    /**
     * One connected laptop. Reads frames on its own thread, writes on its own single-thread executor
     * (a stalled peer can't block the main thread or other clients). Any I/O error closes just this one.
     */
    private inner class Client(val socket: BluetoothSocket, val address: String, val label: String) {
        private val closed = AtomicBoolean(false)
        val since = System.currentTimeMillis()
        @Volatile var reason = "closed"
        var received = 0 // main thread
        var pinged = false // main thread
        private val writer: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "wsconnect-write") }

        fun startReader() = Thread({ readLoop() }, "wsconnect-read").start()

        private fun readLoop() {
            try {
                val input = DataInputStream(socket.inputStream)
                while (!closed.get()) {
                    val len = input.readUnsignedByte()
                    if (len == 0) continue // keepalive
                    val payload = ByteArray(len).also(input::readFully)
                    // Command byte first, then its arguments (extra bytes from a newer laptop: ignored by [command])
                    if (payload[0] != Proto.CMD_PING) log.d("frame ← $label: ${Proto.cmdName(payload[0])} ($len bytes)")
                    main.post { if (!closed.get()) command(this, payload) else log.d("command after close dropped ($label)") }
                }
            } catch (e: IOException) {
                if (!closed.get()) return close("read ended: ${e.message}")
            }
            close("closed")
        }

        fun send(payload: ByteArray) {
            if (closed.get()) return
            val frame = byteArrayOf(payload.size.toByte()) + payload
            runCatching {
                writer.execute {
                    try {
                        synchronized(this@Client) {
                            socket.outputStream.write(frame)
                            socket.outputStream.flush()
                        }
                    } catch (e: IOException) {
                        if (!closed.get()) log.w("rfcomm write failed ($label): ${e.message}")
                        close("write failed: ${e.message}")
                    }
                }
            }.onFailure { log.d("send to $label after close dropped: ${it.javaClass.simpleName}") } // RejectedExecutionException
        }

        /** Any thread, idempotent. First [why] wins (logged by [removed]). */
        fun close(why: String) {
            if (!closed.compareAndSet(false, true)) return
            reason = why
            runCatching { socket.close() }.onFailure { log.d("socket close ($label): $it") }
            writer.shutdown()
            main.post { removed(this) }
        }
    }

    // ---- state ----

    private fun stateBytes(): ByteArray {
        var flags = 0
        if (Tether.ready) flags = flags or Proto.FLAG_SHIZUKU
        if (Tether.hotspotOn) flags = flags or Proto.FLAG_HOTSPOT
        if (Tether.wanted) flags = flags or Proto.FLAG_WANTED
        if (Tether.lastError) flags = flags or Proto.FLAG_ERROR
        return byteArrayOf(flags.toByte(), battery.toByte(), signal.toByte(), Proto.VERSION) + Tailscale.ownBytes()
    }

    /** Main thread only. Re-reads our Tailscale IP; on change: state frame + identity to the laptops' Tailscale IPs. */
    private fun refreshTailscale() {
        if (!running || !Tailscale.refreshOwn()) return
        pushState()
        Tailscale.rebroadcastIfLaptops(this)
    }

    /** Main thread only. Sends the state to every client when it changed. */
    private fun pushState() {
        val value = stateBytes()
        if (value.contentEquals(lastSent)) return
        val old = lastSent
        lastSent = value
        // battery-only changes: no line (every percent would be one)
        if (old == null || old[0] != value[0] || old[2] != value[2] || !old.copyOfRange(4, 8).contentEquals(value.copyOfRange(4, 8))) log.d("state → ${clients.size} client(s): ${Proto.stateString(value)}")
        clients.forEach { it.send(value) }
    }

    /** Main thread only. Warns when the auto hotspot can't work; debounced so app start doesn't flash it. */
    private fun checkShizuku() {
        main.removeCallbacks(shizukuWarning)
        if (Tether.ready) cancelShizukuWarning()
        else {
            if (!shizukuWarned) log.d("shizuku helper not ready: warning in ${SHIZUKU_GRACE_MS / 1000} s unless it binds")
            main.postDelayed(shizukuWarning, SHIZUKU_GRACE_MS)
        }
    }

    private fun showShizukuWarning() {
        if (Tether.ready) return
        val text = when {
            !Tether.shizukuRunning -> "Shizuku draait niet. Start het via draadloze foutopsporing (wifi nodig)."
            !Tether.shizukuAllowed -> "Workstation Connect heeft geen Shizuku-toestemming."
            else -> "Shizuku-helper niet verbonden. Open de app."
        }
        if (!shizukuWarned) log.w("shizuku warning shown: $text")
        shizukuWarned = true
        val nm = getSystemService(NotificationManager::class.java)
        // Not running: Shizuku's own app is where it gets started. Otherwise our home screen fixes it.
        val target = if (!Tether.shizukuRunning) packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) else null
        val open = PendingIntent.getActivity(this, 1, target ?: Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(SHIZUKU_NOTIFICATION_ID, Notification.Builder(this, NotificationHelper.Channels.DEFAULT)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("Automatische hotspot werkt niet")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build())
    }

    private fun cancelShizukuWarning() {
        if (shizukuWarned) log.i("shizuku warning cleared")
        shizukuWarned = false
        getSystemService(NotificationManager::class.java).cancel(SHIZUKU_NOTIFICATION_ID)
    }

    companion object {
        const val GONE_TIMEOUT_MS = 3 * 60_000L
        const val TICK_MS = 10 * 60_000L
        const val BACKOFF_MIN_MS = 2_000L
        const val BACKOFF_MAX_MS = 60_000L
        const val SHIZUKU_NOTIFICATION_ID = 7702
        const val SHIZUKU_GRACE_MS = 15_000L
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val SDP_NAME = "Workstation Connect"
        private const val OLD_PREFS = "wsconnect_link"
        private const val DEVICES_CALLBACK = "wsconnect-status"

        @Volatile var running = false; private set
        /** RFCOMM server socket open and accepting. */
        @Volatile var listening = false; private set
        /** Connected laptops as "address/name". */
        @Volatile var clientLabels: List<String> = emptyList(); private set
        /** At least one laptop is connected over Bluetooth right now. */
        val laptopConnected get() = clientLabels.isNotEmpty()

        /** No-op if already running. Background FGS starts can be refused (Android 12+): logged, not thrown. */
        fun start(c: Context) {
            if (running) return
            runCatching { c.startForegroundService(Intent(c, LinkService::class.java)) }
                .onSuccess { AppLog.d("link", "service start requested") }
                .onFailure { AppLog.e("link", "service start refused", it) }
        }

        fun stop(c: Context) {
            AppLog.i("link", "service stop requested (running=$running)")
            c.stopService(Intent(c, LinkService::class.java))
        }
    }
}

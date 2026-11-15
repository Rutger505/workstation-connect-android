// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.rutger.wsconnect.ui.HomeActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground service: BLE GATT server the laptop talks to. Turns tethering on/off on command and
 * notifies the laptop of tethering flags, battery and signal. If the laptop disappears while
 * tethering is wanted, turns it off after [GONE_TIMEOUT_MS].
 */
@SuppressLint("MissingPermission") // MainActivity requests BLUETOOTH_CONNECT/ADVERTISE before starting us
class LinkService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var bt: BluetoothManager
    private var server: BluetoothGattServer? = null
    private var stateChar: BluetoothGattCharacteristic? = null
    private var advertising = false
    // Main thread only. Any LE link to the phone (watch, earbuds, ...) shows up as a GATT server
    // connection, so a device only counts as the laptop once it's bonded and subscribed to our
    // state characteristic, which the laptop daemon does on every connect.
    private val subscribed: MutableSet<BluetoothDevice> = ConcurrentHashMap.newKeySet()
    private var battery = 0
    private var signal = 255
    private var lastSent: ByteArray? = null

    private val goneTimeout = Runnable {
        if (Tether.wanted && subscribed.isEmpty()) {
            AppLog.i("laptop gone ${GONE_TIMEOUT_MS / 60000} min: tethering off")
            Tether.off()
        }
    }

    private val tetherListener: () -> Unit = { main.post { pushState(); checkShizuku() } }

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
                        BluetoothAdapter.STATE_ON -> startGatt()
                        BluetoothAdapter.STATE_TURNING_OFF -> stopGatt()
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

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        bt = getSystemService(BluetoothManager::class.java)
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }, RECEIVER_EXPORTED)
        runCatching {
            getSystemService(TelephonyManager::class.java).registerTelephonyCallback(mainExecutor, signalCallback)
        }.onFailure { AppLog.i("signal listener: $it") }
        Tether.listen(tetherListener)
        Tether.bind()
        checkShizuku()
        if (bt.adapter?.isEnabled == true) startGatt() else AppLog.i("bluetooth is off: waiting")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        running = false
        laptopConnected = false
        main.removeCallbacks(goneTimeout)
        main.removeCallbacks(shizukuWarning)
        cancelShizukuWarning()
        Tether.unlisten(tetherListener)
        runCatching { unregisterReceiver(receiver) }
        runCatching { getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(signalCallback) }
        stopGatt()
        super.onDestroy()
    }

    private fun startGatt() {
        if (server != null) return
        val s = bt.openGattServer(this, gattCallback) ?: return AppLog.i("openGattServer failed")
        server = s
        val state = BluetoothGattCharacteristic(
            Proto.STATE,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        ).apply {
            addDescriptor(BluetoothGattDescriptor(Proto.CCCD,
                BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }
        val control = BluetoothGattCharacteristic(
            Proto.CONTROL,
            BluetoothGattCharacteristic.PROPERTY_WRITE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        )
        stateChar = state
        s.addService(BluetoothGattService(Proto.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            addCharacteristic(control)
            addCharacteristic(state)
        })
        // Advertising starts in onServiceAdded, once the service exists.
    }

    private fun startAdvertising() {
        val adv = bt.adapter?.bluetoothLeAdvertiser ?: return AppLog.i("no BLE advertiser")
        adv.startAdvertising(
            AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .setTimeout(0)
                .build(),
            AdvertiseData.Builder().addServiceUuid(ParcelUuid(Proto.SERVICE)).build(),
            advertiseCallback,
        )
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            advertising = true
            AppLog.i("advertising")
        }
        override fun onStartFailure(errorCode: Int) = AppLog.i("advertising failed: $errorCode")
    }

    private fun stopGatt() {
        if (advertising) runCatching { bt.adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback) }
        advertising = false
        runCatching { server?.close() }
        server = null
        stateChar = null
        subscribed.clear()
        laptopConnected = false
    }

    private val gattCallback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            AppLog.i("gatt service added: status $status")
            if (status == BluetoothGatt.GATT_SUCCESS) main.post(::startAdvertising)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            main.post { connectionChanged(device, newState) }
        }

        private fun connectionChanged(device: BluetoothDevice, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                AppLog.i("ble connected: ${device.address} bond=${device.bondState}")
            } else {
                AppLog.i("ble disconnected: ${device.address}")
                setSubscribed(device, false)
            }
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
            val value = stateBytes()
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0))
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val ok = c.uuid == Proto.CONTROL && value?.size == 1 && device.bondState == BluetoothDevice.BOND_BONDED
            if (responseNeeded) {
                server?.sendResponse(device, requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION, 0, null)
            }
            if (!ok) return AppLog.i("rejected write from ${device.address} (bond=${device.bondState})")
            when (value!![0]) {
                Proto.CMD_ON -> { AppLog.i("laptop: on"); Tether.on() }
                Proto.CMD_BT_OFF -> { AppLog.i("laptop: bt off"); Tether.btOff() }
                Proto.CMD_OFF -> { AppLog.i("laptop: off"); Tether.off() }
                else -> AppLog.i("laptop: unknown command ${value[0]}")
            }
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
            val on = device in subscribed
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0,
                if (on) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            val enable = value?.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true
            main.post { setSubscribed(device, enable) }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    /** Main thread only. Tracks the laptop (bonded subscriber) and arms the gone timeout. */
    private fun setSubscribed(device: BluetoothDevice, on: Boolean) {
        if (on && device.bondState != BluetoothDevice.BOND_BONDED) {
            return AppLog.i("ignored subscribe from unbonded ${device.address}")
        }
        val changed = if (on) subscribed.add(device) else subscribed.remove(device)
        if (!changed) return
        laptopConnected = subscribed.isNotEmpty()
        if (on) {
            main.removeCallbacks(goneTimeout)
            AppLog.i("laptop connected: ${device.address}")
            lastSent = null
            pushState()
        } else {
            AppLog.i("laptop disconnected: ${device.address}")
            if (subscribed.isEmpty() && Tether.wanted) main.postDelayed(goneTimeout, GONE_TIMEOUT_MS)
        }
    }

    private fun stateBytes(): ByteArray {
        var flags = 0
        if (Tether.ready) flags = flags or Proto.FLAG_SHIZUKU
        if (Tether.btTethered) flags = flags or Proto.FLAG_BT_TETHER
        if (Tether.hotspotOn) flags = flags or Proto.FLAG_HOTSPOT
        if (Tether.wanted) flags = flags or Proto.FLAG_WANTED
        if (Tether.lastError) flags = flags or Proto.FLAG_ERROR
        return byteArrayOf(flags.toByte(), battery.toByte(), signal.toByte(), Proto.VERSION)
    }

    /** Main thread only. Notifies subscribers when the state bytes changed. */
    private fun pushState() {
        val value = stateBytes()
        if (value.contentEquals(lastSent)) return
        lastSent = value
        val s = server ?: return
        val c = stateChar ?: return
        subscribed.forEach { s.notifyCharacteristicChanged(it, c, false, value) }
    }

    /** Main thread only. Warns when the auto hotspot can't work; debounced so app start doesn't flash it. */
    private fun checkShizuku() {
        main.removeCallbacks(shizukuWarning)
        if (Tether.ready) cancelShizukuWarning() else main.postDelayed(shizukuWarning, SHIZUKU_GRACE_MS)
    }

    private fun showShizukuWarning() {
        if (Tether.ready) return
        val text = when {
            !Tether.shizukuRunning -> "Shizuku draait niet. Start het via draadloze foutopsporing (wifi nodig)."
            !Tether.shizukuAllowed -> "Workstation Connect heeft geen Shizuku-toestemming."
            else -> "Shizuku-helper niet verbonden. Open de app."
        }
        if (!shizukuWarned) AppLog.i("shizuku warning: $text")
        shizukuWarned = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("shizuku", "Shizuku-waarschuwing", NotificationManager.IMPORTANCE_DEFAULT))
        // Not running: Shizuku's own app is where it gets started. Otherwise our home screen fixes it.
        val target = if (!Tether.shizukuRunning) packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE) else null
        val open = PendingIntent.getActivity(this, 1, target ?: Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(SHIZUKU_NOTIFICATION_ID, Notification.Builder(this, "shizuku")
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
        if (shizukuWarned) AppLog.i("shizuku warning cleared")
        shizukuWarned = false
        getSystemService(NotificationManager::class.java).cancel(SHIZUKU_NOTIFICATION_ID)
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("link", "Verbinding met laptop", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, HomeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "link")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Workstation Connect")
            .setContentText("Wacht op laptop")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val GONE_TIMEOUT_MS = 3 * 60_000L
        const val NOTIFICATION_ID = 7701 // KDE Connect's BackgroundService uses 1
        const val SHIZUKU_NOTIFICATION_ID = 7702
        const val SHIZUKU_GRACE_MS = 15_000L
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        @Volatile var running = false; private set
        /** A laptop is connected over BLE right now. */
        @Volatile var laptopConnected = false; private set

        /** No-op if already running. Background FGS starts can be refused (Android 12+): logged, not thrown. */
        fun start(c: Context) {
            if (running) return
            runCatching { c.startForegroundService(Intent(c, LinkService::class.java)) }
                .onFailure { AppLog.i("link service start refused: $it") }
        }

        fun stop(c: Context) = c.stopService(Intent(c, LinkService::class.java))
    }
}

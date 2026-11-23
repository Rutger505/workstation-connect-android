// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import android.widget.Toast
import androidx.preference.PreferenceManager
import com.rutger.workstationconnect.Clipboard
import com.rutger.workstationconnect.LinkService
import com.rutger.workstationconnect.Links
import com.rutger.workstationconnect.Tether
import com.rutger.workstationconnect.WsConnect
import com.rutger.workstationconnect.update.Updater
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.kde.kdeconnect.BackgroundService
import org.kde.kdeconnect.Device
import org.kde.kdeconnect.KdeConnect
import org.kde.kdeconnect.PairingHandler.PairState
import org.kde.kdeconnect.plugins.Plugin
import org.kde.kdeconnect.plugins.battery.BatteryPlugin
import org.kde.kdeconnect.plugins.clipboard.ClipboardFloatingActivity
import org.kde.kdeconnect.plugins.notifications.NotificationsPlugin
import org.kde.kdeconnect.plugins.share.SendFileActivity
import org.kde.kdeconnect.ui.SettingsFragment
import org.kde.kdeconnect.ui.compose.KdeTheme
import org.kde.kdeconnect_tp.BuildConfig

/**
 * workstation-connect's own home screen (launcher). Everything Rutger uses daily is here; upstream
 * KDE Connect screens are only reached for deep settings ("Geavanceerde instellingen").
 * UI renders an immutable [Ui] snapshot; listeners + a 1 s poll rebuild it (equal snapshot = no recompose).
 * Actions look devices up by id again, never hold Device objects across ticks.
 */
class HomeActivity : AppCompatActivity() {

    @Immutable
    data class Laptop(
        val id: String, val name: String, val reachable: Boolean, val via: Links.Via?, val battery: Int?, val charging: Boolean,
        val notifyOn: Boolean, val others: List<Pair<String, String>>,
    )

    @Immutable
    data class Candidate(val id: String, val name: String, val state: PairState, val code: String?)

    @Immutable
    data class Ui(
        val laptop: Laptop?, val candidates: List<Candidate>, val setup: List<String>,
        val linkEnabled: Boolean, val shizukuReady: Boolean, val error: Boolean, val hotspot: Boolean,
        val wanted: Boolean, val btLaptops: List<String>, val notifyAccess: Boolean,
        val autoClip: Boolean, val kdeBluetooth: Boolean, val update: Update?,
    )

    @Immutable
    data class Update(val versionCode: Int, val line: String, val canInstall: Boolean)

    private val ui = mutableStateOf<Ui?>(null) // set in onCreate: snapshot() needs a context
    private var poll: Job? = null
    private val bump: () -> Unit = { runOnUiThread { ui.value = snapshot() } }
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { bump() }
    private val askBtPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) setKdeBluetooth(true) else bump()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BackgroundService.Start(applicationContext)
        ui.value = snapshot()
        setContent {
            KdeTheme(this) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { ui.value?.let { Home(it) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        BackgroundService.ForceRefreshConnections(applicationContext)
        Clipboard.defaultSkipSensitive(this)
        Tether.listen(bump)
        KdeConnect.getInstance().addDeviceListChangedCallback(TAG) { bump() }
        Updater.onResume()
        poll = lifecycleScope.launch { while (true) { bump(); delay(1000) } }
    }

    override fun onPause() {
        poll?.cancel()
        Tether.unlisten(bump)
        KdeConnect.getInstance().removeDeviceListChangedCallback(TAG)
        super.onPause()
    }

    // ---- state ----

    /** Setup steps, in order. Labels double as keys for [runSetup]. */
    private fun setupSteps(laptop: Device?): List<String> = buildList {
        if (missing().isNotEmpty()) add(STEP_PERMISSIONS)
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) add(STEP_BATTERY)
        if (!Tether.shizukuRunning) add(STEP_SHIZUKU_START) else if (!Tether.shizukuAllowed) add(STEP_SHIZUKU_ALLOW)
        else if (Tether.ready && !Clipboard.autoSync(this@HomeActivity)) add(STEP_AUTO_CLIP)
        laptop?.pluginsWithoutPermissions?.values?.sortedBy { it.displayName }?.forEach { add(STEP_PLUGIN + it.displayName) }
    }

    private fun snapshot(): Ui {
        val all = KdeConnect.getInstance().devices.values
        val d = WsConnect.laptop(this)
        val laptop = d?.let {
            val battery = it.getPlugin(BatteryPlugin::class.java)?.remoteBatteryInfo
            Laptop(
                id = it.deviceId, name = it.name, reachable = it.isReachable, via = Links.via(it),
                battery = battery?.currentCharge, charging = battery?.isCharging == true,
                notifyOn = it.isPluginEnabled(NOTIFY_KEY),
                others = all.filter { o -> o.isPaired && o.deviceId != it.deviceId }.map { o -> o.deviceId to o.name },
            )
        }
        val candidates = if (d != null) emptyList() else all.filter { it.isReachable && !it.isPaired }
            .sortedBy { it.name }.map { Candidate(it.deviceId, it.name, it.pairStatus, it.verificationKey) }
        return Ui(
            laptop = laptop, candidates = candidates, setup = setupSteps(d),
            linkEnabled = WsConnect.linkEnabled(this), shizukuReady = Tether.ready, error = Tether.lastError,
            hotspot = Tether.hotspotOn, wanted = Tether.wanted,
            btLaptops = LinkService.clientLabels.map { it.substringAfter('/') },
            notifyAccess = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName),
            autoClip = Clipboard.autoSync(this),
            kdeBluetooth = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(SettingsFragment.KEY_BLUETOOTH_ENABLED, false),
            update = update(),
        )
    }

    private fun update(): Update? {
        val r = Updater.available ?: return null
        val s = Updater.status
        val line = when (s) {
            is Updater.Status.Downloading -> "Downloaden… ${s.bytes / 1_048_576}/${r.size / 1_048_576} MB"
            is Updater.Status.NeedsTap -> "Gedownload, installeren vraagt een tik"
            is Updater.Status.Installing -> "Installeren…"
            is Updater.Status.Failed -> s.message
            else -> ""
        }
        return Update(r.versionCode, line, s !is Updater.Status.Downloading && s !is Updater.Status.Installing)
    }

    private fun device(id: String) = KdeConnect.getInstance().getDevice(id)

    // ---- UI ----

    @Composable
    private fun Home(s: Ui) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Workstation Connect", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            s.update?.let { UpdateCard(it) }
            if (s.setup.isNotEmpty()) Setup(s.setup)
            if (s.laptop == null) Pairing(s.candidates) else LaptopCard(s.laptop)
            InternetCard(s)
            ConnectionCard(s)
            if (s.laptop != null) NotificationsCard(s.laptop, s.notifyAccess)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ startActivity(Intent(this@HomeActivity, LogActivity::class.java)) }) { Text("Log") }
                TextButton({ startActivity(HomeRedirect.advancedIntent(this@HomeActivity)) }) { Text("Geavanceerde instellingen") }
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }

    @Composable
    private fun Line(text: String, warn: Boolean = false) = Text(
        text, style = MaterialTheme.typography.bodyMedium,
        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )

    @Composable
    private fun UpdateCard(u: Update) = Section("Update beschikbaar (v${u.versionCode})") {
        if (u.line.isNotEmpty()) Line(u.line)
        Button(Updater::installNow, enabled = u.canInstall) { Text("Nu installeren") }
    }

    @Composable
    private fun Setup(steps: List<String>) = Section("Nog instellen") {
        steps.forEach { step -> FilledTonalButton({ runSetup(step) }, Modifier.fillMaxWidth()) { Text(step) } }
    }

    @Composable
    private fun Pairing(candidates: List<Candidate>) = Section("Laptop koppelen") {
        if (candidates.isEmpty()) {
            Line("Geen laptop gevonden. Start kdeconnectd op je laptop. Zitten jullie niet op hetzelfde netwerk, voeg dan het Tailscale-IP van je laptop toe via Geavanceerde instellingen → Apparaat toevoegen via IP.")
        }
        candidates.forEach { c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, Modifier.weight(1f))
                when (c.state) {
                    PairState.RequestedByPeer -> {
                        TextButton({ device(c.id)?.cancelPairing() }) { Text("Weigeren") }
                        Button({ WsConnect.setLaptop(this@HomeActivity, c.id); device(c.id)?.acceptPairing() }) { Text("Accepteren") }
                    }
                    PairState.Requested -> Text("Wacht op laptop…", style = MaterialTheme.typography.labelMedium)
                    else -> Button({ WsConnect.setLaptop(this@HomeActivity, c.id); device(c.id)?.requestPairing() }) { Text("Koppelen") }
                }
            }
            if (c.state != PairState.NotPaired && c.code != null) Line("Controlecode: ${c.code}")
        }
    }

    @Composable
    private fun LaptopCard(l: Laptop) = Section(l.name) {
        Line(buildString {
            append(if (!l.reachable) "Niet bereikbaar" else when (l.via) {
                Links.Via.WIFI -> "Verbonden via wifi"
                Links.Via.BLUETOOTH -> "Verbonden via Bluetooth"
                else -> "Verbonden"
            })
            if (l.reachable && l.battery != null) append(" · batterij ${l.battery}%${if (l.charging) " ⚡" else ""}")
        }, warn = !l.reachable)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ startActivity(TrackpadActivity.intent(this@HomeActivity, l.id)) }, enabled = l.reachable) { Text("Trackpad") }
            OutlinedButton({
                startActivity(Intent(this@HomeActivity, SendFileActivity::class.java).putExtra(SendFileActivity.EXTRA_DEVICE_ID, l.id))
            }, enabled = l.reachable) { Text("Bestanden") }
            OutlinedButton({ startActivity(ClipboardFloatingActivity.getIntent(this@HomeActivity, true)) }, enabled = l.reachable) {
                Text("Klembord sturen")
            }
            OutlinedButton({ startActivity(Intent(this@HomeActivity, ClipboardActivity::class.java)) }) { Text("Klembordgeschiedenis") }
        }
        l.others.forEach { (id, name) ->
            TextButton({ WsConnect.setLaptop(this@HomeActivity, id); bump() }) { Text("Wissel naar $name") }
        }
    }

    @Composable
    private fun InternetCard(s: Ui) = Section("Internet delen") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Automatisch", style = MaterialTheme.typography.bodyLarge)
                Line("Laptop zonder internet → hotspot aan")
            }
            Switch(s.linkEnabled, { setLink(it) })
        }
        if (!s.linkEnabled) return@Section
        Line(when {
            !s.shizukuReady -> "Shizuku niet klaar: hotspot kan niet aan"
            s.error -> "Laatste poging mislukt, zie Log"
            s.hotspot -> "Hotspot aan"
            s.wanted -> "Bezig met aanzetten…"
            else -> "Klaar, staat uit"
        }, warn = !s.shizukuReady || s.error)
        Line(if (s.btLaptops.isEmpty()) "Geen laptop verbonden (Bluetooth)" else "Verbonden via Bluetooth: ${s.btLaptops.joinToString()}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(Tether::on, enabled = s.shizukuReady && !s.wanted) { Text("Nu aanzetten") }
            OutlinedButton(Tether::off, enabled = s.wanted || s.hotspot) { Text("Uitzetten") }
        }
    }

    @Composable
    private fun ConnectionCard(s: Ui) = Section("Verbinding met laptop") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ook via Bluetooth", style = MaterialTheme.typography.bodyLarge)
                Line("Meldingen, klembord en bestanden zonder gedeeld wifi (eduroam, trein). Langzamer dan wifi.")
            }
            Switch(s.kdeBluetooth, { on ->
                if (on && BT_PERMISSIONS.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
                    askBtPermissions.launch(BT_PERMISSIONS)
                } else setKdeBluetooth(on)
            })
        }
        Line(if (s.autoClip) "Klembord: automatisch naar laptop" else "Klembord: alleen handmatig sturen")
    }

    @Composable
    private fun NotificationsCard(l: Laptop, access: Boolean) = Section("Meldingen") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Doorsturen naar laptop", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(l.notifyOn, { device(l.id)?.setPluginEnabled(NOTIFY_KEY, it); bump() })
        }
        if (!l.notifyOn) return@Section
        if (!access) FilledTonalButton({ startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Meldingtoegang geven") }
        OutlinedButton({ startActivity(Intent(this@HomeActivity, NotificationFilterActivity::class.java)) }) { Text("Per app kiezen") }
    }

    // ---- actions ----

    private fun missing() = PERMISSIONS.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun runSetup(step: String) {
        when {
            step == STEP_PERMISSIONS -> askPermissions.launch(missing().toTypedArray())
            step == STEP_BATTERY -> {
                @Suppress("BatteryLife") // the Bluetooth link and KDE Connect must survive Doze
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
            step == STEP_SHIZUKU_START -> startActivity(
                packageManager.getLaunchIntentForPackage(SHIZUKU)
                    ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$SHIZUKU"))
            )
            step == STEP_SHIZUKU_ALLOW -> Tether.requestPermission()
            step == STEP_AUTO_CLIP -> Clipboard.enableAutoSync(applicationContext) { r ->
                runOnUiThread { Toast.makeText(this, "Mislukt: $r", Toast.LENGTH_LONG).show() }
            }
            step.startsWith(STEP_PLUGIN) -> {
                val name = step.removePrefix(STEP_PLUGIN)
                WsConnect.laptop(this)?.pluginsWithoutPermissions?.values?.firstOrNull { it.displayName == name }
                    ?.permissionExplanationDialog?.show(supportFragmentManager, null)
            }
        }
    }

    private fun setKdeBluetooth(on: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(this).edit()
            .putBoolean(SettingsFragment.KEY_BLUETOOTH_ENABLED, on).apply()
        BackgroundService.ForceRefreshConnections(applicationContext) // restarts BluetoothLinkProvider
        bump()
    }

    private fun setLink(on: Boolean) {
        if (on && missing().isNotEmpty()) return askPermissions.launch(missing().toTypedArray())
        WsConnect.setLinkEnabled(this, on)
        if (on) LinkService.start(this) else LinkService.stop(this)
        bump()
    }

    private companion object {
        const val TAG = "wsconnect-home"
        const val SHIZUKU = "moe.shizuku.privileged.api"
        const val STEP_PERMISSIONS = "Rechten geven"
        const val STEP_BATTERY = "Batterijbeperking uitzetten"
        const val STEP_SHIZUKU_START = "Shizuku starten (draadloze foutopsporing)"
        const val STEP_SHIZUKU_ALLOW = "Shizuku-toestemming geven"
        const val STEP_AUTO_CLIP = "Klembord automatisch delen (via Shizuku)"
        const val STEP_PLUGIN = "Rechten voor "
        val NOTIFY_KEY: String = Plugin.getPluginKey(NotificationsPlugin::class.java)
        val BT_PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        val PERMISSIONS = listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.rutger.wsconnect.AppLog
import com.rutger.wsconnect.LinkService
import com.rutger.wsconnect.Tether
import com.rutger.wsconnect.WsConnect
import com.rutger.wsconnect.logs.LogUpload
import com.rutger.wsconnect.logs.LogUploadLogic.Reason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.kde.kdeconnect.ui.compose.KdeTheme
import org.kde.kdeconnect_tp.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Debug screen: status chips, manual tether commands, persistent log with filters + export (file/share). */
class LogActivity : AppCompatActivity() {

    enum class Level { OK, OFF, WARN, ERROR }

    @Immutable
    data class Item(val label: String, val level: Level)

    @Immutable
    data class Status(val groups: List<Pair<String, List<Item>>>)

    enum class Filter(val label: String) { ALL("Alles"), BT("Bluetooth"), TETHER("Tethering"), ERRORS("Fouten") }

    private val status = mutableStateOf<Status?>(null) // set in onCreate: snapshot() needs a context
    private val lines = mutableStateOf(AppLog.lines())
    private var poll: Job? = null
    private val onLog: () -> Unit = { runOnUiThread { lines.value = AppLog.lines() } }
    private val onTether: () -> Unit = { runOnUiThread { status.value = snapshot() } }
    private var pendingExport: String? = null
    private val saveFile = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val text = pendingExport
        pendingExport = null
        if (uri != null && text != null) save(uri, text)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status.value = snapshot()
        setContent {
            KdeTheme(this) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { status.value?.let { Screen(it, lines.value) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.listen(onLog)
        Tether.listen(onTether)
        onLog()
        poll = lifecycleScope.launch { while (true) { status.value = snapshot(); delay(1000) } }
    }

    override fun onPause() {
        poll?.cancel()
        AppLog.unlisten(onLog)
        Tether.unlisten(onTether)
        super.onPause()
    }

    private fun snapshot(): Status {
        fun item(label: String, on: Boolean, ifOff: Level = Level.OFF) = Item(label, if (on) Level.OK else ifOff)
        val service = LinkService.running
        val autostart = WsConnect.linkEnabled(applicationContext)
        return Status(listOf(
            "Shizuku" to listOf(
                item("draait", Tether.shizukuRunning, Level.WARN),
                item("toestemming", Tether.shizukuAllowed, Level.WARN),
                item("helper", Tether.ready, Level.WARN),
            ),
            "Bluetooth-link" to buildList {
                add(item("service", service, if (autostart) Level.ERROR else Level.OFF))
                add(item("luistert", LinkService.listening, if (service) Level.WARN else Level.OFF))
                val laptops = LinkService.clientLabels
                if (laptops.isEmpty()) add(item("geen laptop", false))
                else laptops.forEach { add(Item(it.substringAfter('/'), Level.OK)) }
                add(item("autostart", autostart))
            },
            "Tethering" to listOf(
                item("gewenst", Tether.wanted),
                item("hotspot", Tether.hotspotOn),
                if (Tether.lastError) Item("fout", Level.ERROR) else Item("geen fout", Level.OK),
            ),
        ))
    }

    // ---- UI ----

    @Composable
    private fun Screen(s: Status, all: List<String>) {
        var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
        val shown = remember(all, filter) { all.filter { matches(it, filter) } }
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Log", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            StatusCard(s)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(::exportToFile) { Text("Opslaan als bestand") }
                if (LogUpload.enabled) FilledTonalButton(::upload) { Text("Logs uploaden") }
                FilledTonalButton(::share) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delen")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Filter.entries.forEach { f ->
                    FilterChip(filter == f, { filter = f }, { Text(f.label) })
                }
            }
            LogList(shown, Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(::copyLog) { Text("Kopiëren") }
                TextButton(::confirmClear) { Text("Wissen") }
            }
        }
    }

    @Composable
    private fun StatusCard(s: Status) {
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                s.groups.forEach { (name, items) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.width(84.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items.forEach { Chip(it) }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Test", Modifier.width(84.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    OutlinedButton(Tether::on) { Text("Aan") }
                    OutlinedButton(Tether::off) { Text("Uit") }
                }
            }
        }
    }

    @Composable
    private fun levelColor(l: Level) = when (l) {
        Level.OK -> OK_GREEN
        Level.OFF -> MaterialTheme.colorScheme.onSurfaceVariant
        Level.WARN -> WARN_AMBER
        Level.ERROR -> MaterialTheme.colorScheme.error
    }

    @Composable
    private fun Chip(item: Item) {
        val c = levelColor(item.level)
        Surface(shape = RoundedCornerShape(50), color = c.copy(alpha = 0.14f)) {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                when (item.level) {
                    Level.OK -> Icon(Icons.Filled.CheckCircle, null, Modifier.size(14.dp), tint = c)
                    Level.WARN -> Icon(Icons.Filled.Info, null, Modifier.size(14.dp), tint = c)
                    Level.ERROR -> Icon(Icons.Filled.Warning, null, Modifier.size(14.dp), tint = c)
                    Level.OFF -> Box(Modifier.size(8.dp).background(c.copy(alpha = 0.6f), CircleShape))
                }
                Spacer(Modifier.width(4.dp))
                Text(item.label, style = MaterialTheme.typography.labelMedium, color = c)
            }
        }
    }

    @Composable
    private fun LogList(shown: List<String>, modifier: Modifier) {
        val state = rememberLazyListState()
        var follow by remember { mutableStateOf(true) }
        // Follow the tail unless the user scrolled up; scrolling back to the end re-enables it.
        LaunchedEffect(state) {
            snapshotFlow { state.isScrollInProgress }.drop(1).filter { !it }.collect { follow = !state.canScrollForward }
        }
        LaunchedEffect(shown) { if (follow && shown.isNotEmpty()) state.scrollToItem(shown.lastIndex) }
        Card(modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            if (shown.isEmpty()) {
                Text("Geen regels.", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SelectionContainer {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp), state) {
                    itemsIndexed(shown) { _, line -> LogLine(line) }
                }
            }
        }
    }

    @Composable
    private fun LogLine(line: String) {
        val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
        val (time, msg) = split(line)
        if (msg.startsWith("--- ")) {
            val date = if (time.isEmpty()) "" else line.take(19) + " · "
            Text(date + msg.trim('-', ' '), Modifier.fillMaxWidth().padding(vertical = 6.dp), style = mono,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            return
        }
        Row(Modifier.padding(vertical = 1.dp)) {
            Text(time, style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            Spacer(Modifier.width(8.dp))
            val level = severity(msg)
            Text(msg, style = mono, color = when (level) {
                Level.OFF, Level.OK -> MaterialTheme.colorScheme.onSurface
                else -> levelColor(level)
            }, fontWeight = if (level == Level.ERROR) FontWeight.SemiBold else null)
        }
    }

    // ---- actions ----

    private fun exportName() = "wsconnect-log-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.txt"

    private suspend fun exportText(): String {
        val header = buildString {
            appendLine("Workstation Connect log")
            appendLine("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Geëxporteerd: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())}")
            appendLine()
            append(statusText())
            appendLine()
            appendLine("-".repeat(40))
        }
        return header + withContext(Dispatchers.IO) { AppLog.fullText() } + "\n"
    }

    private fun statusText() = buildString {
        snapshot().groups.forEach { (name, items) ->
            appendLine("$name: " + items.joinToString(", ") { if ("fout" in it.label) it.label else "${it.label} ${if (it.level == Level.OK) "ja" else "NEE"}" })
        }
    }

    private fun exportToFile() {
        lifecycleScope.launch {
            pendingExport = exportText()
            saveFile.launch(exportName())
        }
    }

    /** Manual upload (lines since the last upload + the status chips as extra header). */
    private fun upload() {
        lifecycleScope.launch {
            val status = statusText()
            val r = withContext(Dispatchers.IO) { runCatching { LogUpload.uploadNow(Reason.MANUAL, status) } }
            val msg = r.fold({ if (it != null) "Logs geüpload" else "Niets te uploaden" }, { "Uploaden mislukt: ${it.message}" })
            Toast.makeText(this@LogActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun save(uri: Uri, text: String) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray()) } }
            }
            ok.onFailure { AppLog.i("log export failed: $it") }
            Toast.makeText(this@LogActivity, if (ok.isSuccess) "Log opgeslagen" else "Opslaan mislukt", Toast.LENGTH_SHORT).show()
        }
    }

    private fun share() {
        lifecycleScope.launch {
            val text = exportText()
            val uri = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "logs").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val f = File(dir, exportName()).apply { writeText(text) }
                FileProvider.getUriForFile(this@LogActivity, "$packageName.wsconnect.logs", f)
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Workstation Connect log")
                clipData = ClipData.newRawUri(null, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Log delen"))
        }
    }

    private fun copyLog() {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("wsconnect log", statusText() + "\n" + AppLog.text()))
        Toast.makeText(this, "Log gekopieerd", Toast.LENGTH_SHORT).show()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage("Log wissen? Ook het bestand op de telefoon wordt gewist.")
            .setPositiveButton("Wissen") { _, _ -> AppLog.clear() }
            .setNegativeButton("Annuleren", null)
            .show()
    }

    companion object {
        private val OK_GREEN = Color(0xFF2E9D4A)
        private val WARN_AMBER = Color(0xFFC77700)
        private val ERROR_WORDS = listOf("fail", "error", "refused", "rejected", "exception", "denied", "mislukt")
        private val WARN_WORDS = listOf("restart in", "read ended", "warning", "gone", "bluetooth off", "ignored")
        private val BT_WORDS = listOf("rfcomm", "laptop", "bluetooth")
        private val TETHER_WORDS = listOf("tether", "hotspot", "shizuku", "helper", "laptop: on", "laptop: off")

        /** "yyyy-MM-dd HH:mm:ss.SSS msg" → ("HH:mm:ss", msg); other lines (old format, separators) as-is. */
        fun split(line: String): Pair<String, String> {
            if (line.length > 24 && line[4] == '-' && line[10] == ' ' && line[23] == ' ') return line.substring(11, 19) to line.substring(24)
            return "" to line
        }

        fun severity(msg: String): Level {
            val m = msg.lowercase()
            return when {
                ERROR_WORDS.any { it in m } -> Level.ERROR
                WARN_WORDS.any { it in m } -> Level.WARN
                else -> Level.OFF
            }
        }

        fun matches(line: String, f: Filter): Boolean {
            if (f == Filter.ALL) return true
            val msg = split(line).second
            if (msg.startsWith("--- ")) return true
            val m = msg.lowercase()
            return when (f) {
                Filter.BT -> BT_WORDS.any { it in m }
                Filter.TETHER -> TETHER_WORDS.any { it in m }
                Filter.ERRORS -> severity(msg) != Level.OFF
                Filter.ALL -> true
            }
        }
    }
}

/** Own FileProvider subclass (manifest needs a distinct class next to upstream's): serves cache/logs/ for "Delen". */
class LogFileProvider : FileProvider()

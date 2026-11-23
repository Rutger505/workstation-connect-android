// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.workstationconnect.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rutger.workstationconnect.Clipboard
import com.rutger.workstationconnect.WsConnect
import org.kde.kdeconnect.plugins.clipboard.ClipboardPlugin
import org.kde.kdeconnect.ui.compose.KdeTheme

/** Clipboard history of both devices. Tap = copy on phone, "→ laptop" = send that item. */
class ClipboardActivity : AppCompatActivity() {

    private val items = mutableStateOf(Clipboard.history())
    private val refresh: () -> Unit = { runOnUiThread { items.value = Clipboard.history() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KdeTheme(this) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Klembord", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                            TextButton(Clipboard::clear) { Text("Wissen") }
                        }
                        if (items.value.isEmpty()) Text("Nog niets gekopieerd.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(items.value, key = { it.at.toString() + it.text.hashCode() }) { item ->
                                Row(Modifier.fillMaxWidth().clickable { copy(item.text) }.padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(item.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            (if (item.fromLaptop) "van laptop · " else "van telefoon · ") +
                                                DateUtils.getRelativeTimeSpanString(item.at),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    TextButton({ send(item.text) }) { Text("→ laptop") }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Clipboard.listen(refresh)
        refresh()
    }

    override fun onPause() {
        Clipboard.unlisten(refresh)
        super.onPause()
    }

    private fun copy(text: String) {
        // Plain system copy: counts as a phone copy (moves to top, auto-syncs like any copy).
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(null, text))
        Toast.makeText(this, "Gekopieerd", Toast.LENGTH_SHORT).show()
    }

    private fun send(text: String) {
        val plugin = WsConnect.laptop(this)?.getPlugin(ClipboardPlugin::class.java)
            ?: return Toast.makeText(this, "Laptop niet bereikbaar", Toast.LENGTH_SHORT).show()
        plugin.propagateClipboard(text)
        Toast.makeText(this, "Naar laptop gestuurd", Toast.LENGTH_SHORT).show()
    }
}

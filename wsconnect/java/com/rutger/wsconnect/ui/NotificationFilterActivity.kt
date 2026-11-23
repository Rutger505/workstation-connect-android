// SPDX-License-Identifier: GPL-3.0-or-later
package com.rutger.wsconnect.ui

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rutger.wsconnect.AppLog
import org.kde.kdeconnect.plugins.notifications.AppDatabase
import org.kde.kdeconnect.ui.compose.KdeTheme

/**
 * Which apps' notifications go to the laptop, plus per-app privacy (hide text / images).
 * Same storage as upstream (AppDatabase), so the plugin honours it directly.
 */
class NotificationFilterActivity : AppCompatActivity() {

    data class App(val pkg: String, val name: String, val icon: Bitmap)

    private lateinit var db: AppDatabase
    private val apps = mutableStateOf<List<App>?>(null)
    private val enabled = mutableStateMapOf<String, Boolean>()
    private val expanded = mutableStateOf<String?>(null)
    private val query = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = AppDatabase.getInstance(this)
        lifecycleScope.launch {
            val (list, states) = withContext(Dispatchers.IO) { loadApps().let { l -> l to l.associate { it.pkg to db.isEnabled(it.pkg) } } }
            enabled.putAll(states)
            apps.value = list
        }
        setContent {
            KdeTheme(this) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Screen() }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Screen() = Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Meldingen per app", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Nieuwe apps standaard aan", Modifier.weight(1f))
            var all by mutableBool(db.allEnabled)
            Switch(all, { db.allEnabled = it; all = it; AppLog.i("notif filter: all apps ${if (it) "on" else "off"}") })
        }
        OutlinedTextField(query.value, { query.value = it }, Modifier.fillMaxWidth(), label = { Text("Zoeken") }, singleLine = true)
        val list = apps.value ?: return@Column Text("Apps laden…")
        val q = query.value.trim()
        LazyColumn {
            items(list.filter { q.isEmpty() || it.name.contains(q, true) || it.pkg.contains(q, true) }, key = { it.pkg }) { app ->
                Column {
                    Row(Modifier.fillMaxWidth().clickable { expanded.value = if (expanded.value == app.pkg) null else app.pkg }
                        .padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Image(app.icon.asImageBitmap(), null, Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(app.name, Modifier.weight(1f))
                        Switch(enabled[app.pkg] ?: false, { db.setEnabled(app.pkg, it); enabled[app.pkg] = it; AppLog.i("notif filter: ${app.pkg} ${if (it) "on" else "off"}") })
                    }
                    if (expanded.value == app.pkg) Privacy(app.pkg)
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Privacy(pkg: String) = Column(Modifier.padding(start = 48.dp, bottom = 8.dp)) {
        listOf(
            AppDatabase.PrivacyOptions.BLOCK_CONTENTS to "Inhoud verbergen (alleen app-naam)",
            AppDatabase.PrivacyOptions.BLOCK_IMAGES to "Afbeeldingen niet sturen",
        ).forEach { (opt, label) ->
            var on by mutableBool(db.getPrivacy(pkg, opt))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, { db.setPrivacy(pkg, opt, it); on = it; AppLog.i("notif filter: $pkg ${opt.name.lowercase()} ${if (it) "on" else "off"}") })
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun mutableBool(initial: Boolean) = androidx.compose.runtime.remember { mutableStateOf(initial) }

    private fun loadApps(): List<App> {
        val pm = packageManager
        val size = (40 * resources.displayMetrics.density).toInt()
        return pm.getInstalledApplications(0)
            .filter { it.packageName != packageName && canPostNotifications(pm, it) }
            .map { App(it.packageName, it.loadLabel(pm).toString(), it.loadIcon(pm).toBitmap(size, size)) }
            .sortedBy { it.name.lowercase() }
    }

    // Same rule as upstream's NotificationsSettingsFragment.
    private fun canPostNotifications(pm: PackageManager, info: ApplicationInfo): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || info.targetSdkVersion < Build.VERSION_CODES.TIRAMISU) return true
        return runCatching {
            pm.getPackageInfo(info.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
                .requestedPermissions?.contains(Manifest.permission.POST_NOTIFICATIONS) == true
        }.getOrDefault(false)
    }
}

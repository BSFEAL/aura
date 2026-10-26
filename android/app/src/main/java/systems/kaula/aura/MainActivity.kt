package systems.kaula.aura

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.all { it }) MonitorService.error.value = "Permissions granted. Tap Start monitoring to activate the microphone."
        else MonitorService.error.value = "Monitoring needs microphone and notification permission. Enable them in Android settings, then tap Start."
    }
    private fun startMonitoring() {
        val required = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
        if (required.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) { permissions.launch(required.toTypedArray()); return }
        if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) { MonitorService.error.value = "Enable AURA notifications in Android settings before monitoring"; return }
        startForegroundService(Intent(this, MonitorService::class.java).setAction("START"))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF54DCEB), secondary = Color(0xFF8C94FF), background = Color(0xFF080E20), surface = Color(0xFF121E35))) {
                App()
            }
        }
    }
    @Composable private fun App() {
        var tab by remember { mutableStateOf("Dashboard") }
        var text by remember { mutableStateOf("Notify me when sound level is above 0.15") }
        var message by remember { mutableStateOf("") }
        var base by remember { mutableStateOf(Store.setting(this, "base")) }
        var token by remember { mutableStateOf("") }
        var deviceId by remember { mutableStateOf(Store.setting(this, "deviceId")) }
        var alerts by remember { mutableStateOf(Store.notifications(this)) }
        var syncing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val revision = Store.revision.value
        val rules = remember(revision) { Store.rules(this) }
        val events = remember(revision) { Store.events(this) }
        Scaffold(bottomBar = {
            NavigationBar { listOf("Dashboard", "Devices", "Rules", "Events", "Alerts").forEach { page ->
                NavigationBarItem(selected = tab == page, onClick = { tab = page }, icon = { Text(when(page) { "Dashboard" -> "◈"; "Devices" -> "▣"; "Rules" -> "⌘"; "Events" -> "≋"; else -> "◉" }) }, label = { Text(page, style = MaterialTheme.typography.labelSmall) })
            } }
        }) { padding ->
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("AURA ◌", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
                Text("by kaula.systems · v0.2", style = MaterialTheme.typography.labelMedium)
                Text(tab, style = MaterialTheme.typography.headlineSmall)
                if (MonitorService.active.value) {
                    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Microphone active · local processing", color = MaterialTheme.colorScheme.primary)
                        Text("Uncalibrated RMS: %.3f".format(MonitorService.level.value))
                        Button(onClick = { stopService(Intent(this@MainActivity, MonitorService::class.java)) }) { Text("Stop monitoring") }
                    } }
                }
                if (MonitorService.error.value.isNotBlank()) Text(MonitorService.error.value, color = MaterialTheme.colorScheme.secondary)
                if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.secondary)
                when (tab) {
                    "Dashboard" -> {
                        Text("Stay aware. Stay in control.", style = MaterialTheme.typography.headlineMedium)
                        Text("${if (MonitorService.active.value) "Active" else "Stopped"} · ${rules.count { it.enabled }} enabled rules · ${events.size} local events")
                        Text("RMS measures amplitude only. It cannot identify rain, speech or specific sounds and is not calibrated decibels. No audio is stored or uploaded.")
                        if (!MonitorService.active.value) Button(onClick = { startMonitoring() }) { Text("Start monitoring") }
                        Text("Starting requests microphone and notification permission. After granting permissions, tap Start again. A persistent notification includes Stop. No automatic restart after reboot or termination.")
                        Text("Add a rule in Rules first. Event alerts have a 30-second cooldown per rule. Android microphone privacy controls may mute input.")
                    }
                    "Rules" -> {
                        OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Natural language rule") }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            val threshold = Signal.parseRule(text)
                            if (threshold == null) message = "Supported: Notify me when sound level is above 0.15 (0 < threshold ≤ 1)"
                            else { Store.saveRules(this@MainActivity, rules + LocalRule(UUID.randomUUID().toString(), text.trim(), threshold)); message = "Local rule created" }
                        }) { Text("Create local rule") }
                        Text("Only amplitude thresholds are supported. Downloaded API rules are refreshed with Sync in Devices.")
                        rules.forEach { rule -> Card { Column(Modifier.padding(16.dp)) {
                            Text(rule.text)
                            Text(if (rule.remote) "Downloaded from API" else "Phone-only rule", style = MaterialTheme.typography.labelSmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Switch(checked = rule.enabled, onCheckedChange = { enabled -> Store.saveRules(this@MainActivity, rules.map { if (it.id == rule.id) it.copy(enabled = enabled) else it }) })
                                if (!rule.remote) TextButton(onClick = { Store.saveRules(this@MainActivity, rules.filter { it.id != rule.id }) }) { Text("Delete") }
                            }
                        } } }
                    }
                    "Devices" -> {
                        Text("This Android phone", style = MaterialTheme.typography.titleLarge)
                        Text("Phone-only monitoring needs no account, server or network. Optional sync uploads event amplitudes and downloads rules only when you tap Sync. Use trusted HTTPS with a valid certificate.")
                        OutlinedTextField(value = base, onValueChange = { base = it }, label = { Text("HTTPS backend URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("API token (not saved)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = deviceId, onValueChange = { deviceId = it }, label = { Text("Device ID; blank registers phone") }, modifier = Modifier.fillMaxWidth())
                        Button(enabled = !syncing && !MonitorService.active.value, onClick = {
                            syncing = true
                            val syncBase = base; val syncToken = token; val syncId = deviceId
                            scope.launch {
                                try {
                                    message = withContext(Dispatchers.IO) { Api.sync(this@MainActivity, syncBase, syncToken, syncId) }
                                    Store.setting(this@MainActivity, "base", syncBase)
                                    deviceId = Store.setting(this@MainActivity, "deviceId")
                                } catch (e: Exception) { message = "Sync failed: ${e.message}" }
                                finally { syncing = false }
                            }
                        }) { Text(if (syncing) "Syncing…" else "Sync rules & events") }
                        Text("Stop monitoring before sync. API registration never activates a microphone. A new registration is created if Device ID is blank. Toggling a downloaded rule affects this phone until the next sync.")
                    }
                    "Events" -> {
                        Text("Recent local events (up to 200)")
                        if (events.isEmpty()) Text("No events yet. Add a rule and explicitly start monitoring.")
                        events.forEach { event -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                            Text("Sound-level threshold exceeded")
                            Text("RMS %.3f · %s".format(event.rms, DateFormat.getDateTimeInstance().format(Date(event.time))))
                            Text("Device report · local", style = MaterialTheme.typography.labelSmall)
                        } } }
                    }
                    "Alerts" -> {
                        Text("Local notifications", style = MaterialTheme.typography.titleLarge)
                        Row { Text("Threshold alerts", Modifier.weight(1f)); Switch(checked = alerts, onCheckedChange = { alerts = it; Store.notifications(this@MainActivity, it) }) }
                        Text("The active microphone notification always remains visible. Threshold notifications respect Android notification settings. Events are saved even with alerts off.")
                        Text("AURA never places emergency calls. It is not an emergency or safety-critical service.")
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

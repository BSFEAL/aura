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
import androidx.compose.foundation.horizontalScroll
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
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        MonitorService.error.value=if(results.values.all { it }) "Permissions granted. Tap Start monitoring to activate the microphone." else "Microphone and notification permission required. Restore denied permissions in Android settings."
    }
    private fun startMonitoring() {
        val required=mutableListOf(Manifest.permission.RECORD_AUDIO)
        if(Build.VERSION.SDK_INT>=33) required+=Manifest.permission.POST_NOTIFICATIONS
        if(required.any { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }) { permissions.launch(required.toTypedArray()); return }
        if(!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) { MonitorService.error.value="Enable AURA notifications in Android settings before monitoring"; return }
        try { startForegroundService(Intent(this,MonitorService::class.java).setAction("START")) }
        catch(e: Exception) { MonitorService.status.value="Error"; MonitorService.error.value="Monitoring could not start: ${e.message}" }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(!MonitorService.active.value) Store.recoverInterruptedEvents(this)
        setContent { MaterialTheme(colorScheme=darkColorScheme(primary=Color(0xFF54DCEB),secondary=Color(0xFF9296FF),background=Color(0xFF080E20),surface=Color(0xFF121E35))) { App() } }
    }
    @Composable private fun App() {
        var tab by remember { mutableStateOf("Dashboard") }
        var message by remember { mutableStateOf("") }
        var base by remember { mutableStateOf(Store.setting(this,"base")) }
        var token by remember { mutableStateOf("") }
        var deviceId by remember { mutableStateOf(Store.setting(this,"deviceId")) }
        var alerts by remember { mutableStateOf(Store.notifications(this)) }
        var syncing by remember { mutableStateOf(false) }
        var cadence by remember { mutableStateOf(Store.cadence(this).toString()) }
        val scope=rememberCoroutineScope()
        val revision=Store.revision.intValue
        val rules=remember(revision) { Store.rules(this) }
        val events=remember(revision) { Store.events(this) }
        Scaffold(topBar={
            Column(Modifier.background(MaterialTheme.colorScheme.background).statusBarsPadding().padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("AURA ◌",style=MaterialTheme.typography.headlineLarge,color=MaterialTheme.colorScheme.primary)
                Text("SOUND INTELLIGENCE · by kaula.systems · v0.3",style=MaterialTheme.typography.labelSmall)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    listOf("Dashboard","Live","Rules","Events","Diagnostics","Devices","Alerts").forEach { page -> FilterChip(selected=tab==page,onClick={tab=page},label={Text(page)}) }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(tab,style=MaterialTheme.typography.headlineSmall)
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("${MonitorService.status.value} · ${if(MonitorService.active.value) "Microphone in use" else "Microphone off"}",color=MaterialTheme.colorScheme.primary)
                    Text("Uncalibrated RMS %.3f".format(MonitorService.level.doubleValue))
                    Text(if(MonitorService.modelLoaded.value) "On-device YAMNet inference · no audio upload" else "Classifier inactive/unavailable · RMS remains independent",style=MaterialTheme.typography.labelMedium)
                    if(MonitorService.active.value) Button(onClick={stopService(Intent(this@MainActivity,MonitorService::class.java))}) { Text("Stop monitoring") }
                    else Button(enabled=!syncing && !MonitorService.finishing.value,onClick={startMonitoring()}) { Text("Start monitoring") }
                } }
                if(MonitorService.error.value.isNotBlank()) Text(MonitorService.error.value,color=MaterialTheme.colorScheme.secondary)
                if(Store.error.value.isNotBlank()) Text(Store.error.value,color=MaterialTheme.colorScheme.error)
                if(message.isNotBlank()) Text(message,color=MaterialTheme.colorScheme.secondary)
                when(tab) {
                    "Dashboard" -> {
                        Text("Stay aware. Stay in control.",style=MaterialTheme.typography.headlineMedium)
                        val active=MonitorService.live.value.any { (id,update)-> update.phase==Phase.ACTIVE && rules.any { it.id==id && it.condition is Condition.Audio } }
                        val best=MonitorService.live.value.filterKeys { id->rules.any { it.id==id && it.condition is Condition.Audio } }.maxByOrNull { it.value.smoothed }
                        Text(if(active && best!=null) "Possible ${rules.find { it.id==best.key }?.label}" else "Unknown / unconfirmed")
                        best?.value?.let { Text("Raw score %.3f · smoothed score %.3f".format(it.raw,it.smoothed)) }
                        Text("Last rule-confirmed episode: ${events.firstOrNull()?.let { it.label+" · "+date(it.startMs) } ?: "None"}")
                        Text("${rules.count { it.enabled }} enabled rules · ${events.size} local events")
                        Text("Experimental model scores are not calibrated probabilities. A confirmed rule episode is not proof of a real-world event. RMS is amplitude only, not sound classification or physical decibels.")
                        Text("Grant microphone and notification permissions, then tap Start again. The persistent notification includes Stop. No automatic reboot/start. This is not a safety, fire, medical or emergency detector.")
                    }
                    "Live" -> {
                        Text("Live sound intelligence",style=MaterialTheme.typography.titleLarge)
                        Text("${ModelSpec.VERSION} · ${ModelSpec.MAPPING} · ${ModelSpec.THRESHOLDS}",style=MaterialTheme.typography.labelSmall)
                        Text("Top model outputs (uncalibrated scores)")
                        if(MonitorService.top.value.isEmpty()) Text("Unknown / unconfirmed. Start monitoring to run local inference.")
                        MonitorService.top.value.forEach { score ->
                            Text("${score.label}: %.3f".format(score.score)); LinearProgressIndicator(progress={score.score},modifier=Modifier.fillMaxWidth())
                            Text(score.classId,style=MaterialTheme.typography.labelSmall)
                        }
                        rules.forEach { rule -> MonitorService.live.value[rule.id]?.let { live ->
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                                Text(rule.label); Text("${live.phase} · persistence ${live.persistenceMs} ms")
                                Text("Raw %.3f · EMA %.3f".format(live.raw,live.smoothed))
                            } }
                        } }
                    }
                    "Rules" -> RuleEditor(rules,onMessage={message=it})
                    "Events" -> {
                        Text("Event history · latest 200")
                        if(events.isEmpty()) Text("No local episodes. Create a rule, then explicitly start monitoring.")
                        events.forEach { event -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(event.label,style=MaterialTheme.typography.titleMedium)
                            Text("${event.provenance} · ${event.state}",color=MaterialTheme.colorScheme.primary)
                            Text("Start ${date(event.startMs)} · ${if(event.endMs==null) "active / duration updates at end" else "${event.durationMs} ms"}")
                            Text("${event.classId ?: "rms_above"} · RMS %.3f".format(event.rms),style=MaterialTheme.typography.labelSmall)
                            if(event.peakScore!=null) Text("Peak %.3f · mean %.3f (uncalibrated)".format(event.peakScore,event.meanScore))
                            Text("Device ${event.deviceId}\nModel ${event.modelVersion ?: "none"}\nThreshold ${event.thresholdVersion ?: "none"}\nNotification ${event.notificationStatus}",style=MaterialTheme.typography.labelSmall)
                        } } }
                    }
                    "Diagnostics" -> {
                        Text("Model ${if(MonitorService.modelLoaded.value) "loaded" else "inactive / unavailable"}")
                        Text("${ModelSpec.VERSION} · 4,126,810-byte asset · 15,600-sample window · 521 classes")
                        Text(MonitorService.stats.value.ifBlank { "No session measurements yet. 16 kHz mono PCM16 input. CPU-only inference, two native threads." })
                        OutlinedTextField(value=cadence,onValueChange={cadence=it},label={Text("Inference interval ms (250–2000)")},modifier=Modifier.fillMaxWidth())
                        Button(enabled=!MonitorService.active.value,onClick={
                            val ms=cadence.toIntOrNull()
                            if(ms==null || ms !in 250..2000) message="Cadence must be 250–2000 ms"
                            else { Store.setting(this@MainActivity,"cadence",ms.toString()); message="Cadence saved for the next session" }
                        }) { Text("Save cadence") }
                        Text("500 ms default uses overlapping 975 ms windows. Faster cadence uses more processing; slower cadence may miss short sounds. No RMS noise gate is used, so quiet events are not discarded by an amplitude threshold. Battery and thermal measurements require a physical phone.")
                    }
                    "Devices" -> {
                        Text("Local phone ${Store.localDeviceId(this@MainActivity)}")
                        Text("Optional explicit HTTPS sync sends event metadata and scores, never audio. Stop monitoring before syncing. Tokens stay in memory.")
                        OutlinedTextField(value=base,onValueChange={base=it},label={Text("HTTPS backend URL")},modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(value=token,onValueChange={token=it},label={Text("API token (not saved)")},visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(value=deviceId,onValueChange={deviceId=it},label={Text("Device ID; blank registers phone")},modifier=Modifier.fillMaxWidth())
                        Button(enabled=!syncing && !MonitorService.active.value && !MonitorService.finishing.value,onClick={
                            syncing=true
                            val b=base; val t=token; val d=deviceId
                            scope.launch {
                                try { message=withContext(Dispatchers.IO) { Api.sync(this@MainActivity,b,t,d) }; Store.setting(this@MainActivity,"base",b); deviceId=Store.setting(this@MainActivity,"deviceId") }
                                catch(e: Exception) { message="Sync failed: ${e.message}" }
                                finally { syncing=false }
                            }
                        }) { Text(if(syncing) "Syncing…" else "Sync rules & events") }
                        Text("API rules only take effect after sync. Their local edits/toggles last until the next sync; manage remote rules in the web dashboard. Registration never activates the microphone.")
                    }
                    "Alerts" -> {
                        Row { Text("Threshold and possible-sound alerts",Modifier.weight(1f)); Switch(checked=alerts,onCheckedChange={alerts=it;Store.notifications(this@MainActivity,it)}) }
                        Text("Alerts are experimental and never describe an uncertain model output as a confirmed real-world event. The active microphone notification stays visible. Disabling its channel stops monitoring. No emergency calls.")
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
    @Composable private fun RuleEditor(rules: List<LocalRule>,onMessage: (String)->Unit) {
        var editing by remember { mutableStateOf<LocalRule?>(null) }
        var eventType by remember { mutableStateOf("rms_above") }
        var value by remember { mutableStateOf("0.15") }
        var classId by remember { mutableStateOf<String?>(null) }
        var release by remember { mutableStateOf("0.5") }
        var persistence by remember { mutableStateOf("3000") }
        var gap by remember { mutableStateOf("1000") }
        var alpha by remember { mutableStateOf("0.5") }
        var cooldown by remember { mutableStateOf("30000") }
        var notify by remember { mutableStateOf(true) }
        var natural by remember { mutableStateOf("Notify me when sound level is above 0.15") }
        Text(if(editing==null) "Create a typed rule" else "Edit ${editing?.label}",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            (listOf("rms_above" to "RMS")+SoundCatalog.events.map { it.id to it.label }).forEach { (id,label) -> FilterChip(selected=eventType==id,onClick={eventType=id;classId=null;value=if(id=="rms_above") "0.15" else "0.7"},label={Text(label)}) }
        }
        OutlinedTextField(value=value,onValueChange={value=it},label={Text(if(eventType=="rms_above") "RMS threshold" else "Activation score (provisional)")},modifier=Modifier.fillMaxWidth())
        if(eventType!="rms_above") {
            Text("Per-class threshold scope (all mapped classes use max score by default)")
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=classId==null,onClick={classId=null},label={Text("All mapped classes")})
                SoundCatalog.event(eventType)?.classIds?.sorted()?.forEach { id -> FilterChip(selected=classId==id,onClick={classId=id},label={Text(id)}) }
            }
            OutlinedTextField(value=release,onValueChange={release=it},label={Text("Release score (< activation)")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=persistence,onValueChange={persistence=it},label={Text("Persistence ms (0–60000)")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=gap,onValueChange={gap=it},label={Text("Max gap ms (0–10000)")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=alpha,onValueChange={alpha=it},label={Text("EMA alpha (0.01–1)")},modifier=Modifier.fillMaxWidth())
            Text("${if(MonitorService.modelLoaded.value) "Classifier available" else "Classifier inactive/unavailable until monitoring starts"}. Scores are uncalibrated. Doorbell means a sound, not a door opening.")
        }
        OutlinedTextField(value=cooldown,onValueChange={cooldown=it},label={Text("Cooldown ms (0–3600000)")},modifier=Modifier.fillMaxWidth())
        Row { Text("Notify for this rule",Modifier.weight(1f)); Switch(checked=notify,onCheckedChange={notify=it}) }
        Button(enabled=!MonitorService.active.value,onClick={
            try {
                val condition=if(eventType=="rms_above") Condition.Rms(value.toDouble(),(editing?.condition as? Condition.Rms)?.legacyRepeat ?: false) else Condition.Audio(eventType,value.toFloat(),release.toFloat(),persistence.toLong(),gap.toLong(),alpha.toFloat(),classId=classId)
                val rule=LocalRule(id=editing?.id ?: UUID.randomUUID().toString(),enabled=editing?.enabled ?: true,source=editing?.source ?: Source(Store.localDeviceId(this@MainActivity)),condition=condition,policy=Policy(cooldown.toLong(),notify),remote=editing?.remote ?: false).validate()
                Store.saveRules(this@MainActivity,rules.filter { it.id!=rule.id }+rule);editing=null;onMessage("Rule saved; microphone activation remains manual")
            } catch(_: Exception) { onMessage("Invalid rule. Check finite scores, release < activation, duration/gap/cooldown ranges and selected capability.") }
        }) { Text(if(editing==null) "Save rule" else "Save changes") }
        if(editing!=null) TextButton(onClick={editing=null}) { Text("Cancel edit") }
        Text("Legacy RMS language input")
        OutlinedTextField(value=natural,onValueChange={natural=it},label={Text("Natural language RMS rule")},modifier=Modifier.fillMaxWidth())
        Button(enabled=!MonitorService.active.value,onClick={
            val threshold=Signal.parseRule(natural)
            if(threshold==null) onMessage("Supported: Notify me when sound level is above 0.15")
            else try { Store.saveRules(this@MainActivity,rules+LocalRule(id=UUID.randomUUID().toString(),source=Source(Store.localDeviceId(this@MainActivity)),condition=Condition.Rms(threshold,true),text=natural));onMessage("RMS rule saved") } catch(e: Exception) { onMessage(e.message ?: "Could not save") }
        }) { Text("Create local RMS rule") }
        rules.forEach { rule -> Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
            Text(rule.label,style=MaterialTheme.typography.titleMedium)
            Text("Schema ${rule.schemaVersion} · ${if(rule.remote) "API download" else "local"}",style=MaterialTheme.typography.labelSmall)
            rule.unavailableReason(MonitorService.modelLoaded.value)?.let { Text(it,color=MaterialTheme.colorScheme.secondary) }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Switch(checked=rule.enabled,onCheckedChange={enabled->Store.saveRules(this@MainActivity,rules.map { if(it.id==rule.id) it.copy(enabled=enabled) else it })})
                TextButton(enabled=!MonitorService.active.value,onClick={
                    editing=rule;cooldown=rule.policy.cooldownMs.toString();notify=rule.policy.notify
                    when(val c=rule.condition) {
                        is Condition.Rms -> { eventType="rms_above";value=c.threshold.toString() }
                        is Condition.Audio -> { eventType=c.eventType;classId=c.classId;value=c.minScore.toString();release=c.releaseScore.toString();persistence=c.minDurationMs.toString();gap=c.maxGapMs.toString();alpha=c.emaAlpha.toString() }
                    }
                }) { Text("Edit") }
                TextButton(onClick={Store.saveRules(this@MainActivity,rules.filter { it.id!=rule.id })}) { Text("Delete") }
            }
        } } }
        Text("Stop monitoring to edit or create rules. Changing a rule resets its detector. Unsupported model/mapping versions are displayed and never evaluated.")
    }
    private fun date(ms: Long)=DateFormat.getDateTimeInstance().format(Date(ms))
}

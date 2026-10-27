package systems.kaula.aura

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date
import java.util.UUID

class MainActivity : ComponentActivity() {
    companion object {
        internal var rulePlannerFactory: (String,String,String,String)->RulePlanner = { base,token,device,provider -> BackendRulePlanner(base,token,device,provider) }
    }
    private val systemAlertsAllowed=mutableStateOf(true)
    private val fileBusy=mutableStateOf(false)
    private val backupMessage=mutableStateOf("")
    private val pendingRestore=mutableStateOf<LocalBackup?>(null)
    private val exportBackup=registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        fileBusy.value=uri!=null
        if(uri==null) fileBusy.value=false else lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes=BackupCodec.encode(Store.backup(this@MainActivity))
                    requireNotNull(contentResolver.openOutputStream(uri,"wt")) { "Cannot open selected file" }.use { it.write(bytes) }
                }
                backupMessage.value="Metadata backup saved. Keep it private; no audio or credentials included."
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { backupMessage.value="Backup not completed. Check storage and remove any incomplete file." }
            finally { fileBusy.value=false }
        }
    }
    private val importBackup=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileBusy.value=uri!=null
        if(uri==null) fileBusy.value=false else lifecycleScope.launch {
            try {
                val candidate=withContext(Dispatchers.IO) {
                    requireNotNull(contentResolver.openInputStream(uri)) { "Cannot open selected file" }.use { BackupCodec.read(it) }
                }
                check(!MonitorService.active.value && !MonitorService.finishing.value) { "Stop monitoring before restore" }
                pendingRestore.value=candidate
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { backupMessage.value="Backup could not be read or validated. Existing data kept. Use an AURA metadata backup up to 1 MiB." }
            finally { fileBusy.value=false }
        }
    }
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        MonitorService.error.value=if(results.values.all { it }) "Permissions granted. Tap Start monitoring to activate the microphone." else "Microphone and notification permission required. Restore denied permissions in Android settings."
    }
    private fun startMonitoring() {
        if(fileBusy.value || pendingRestore.value!=null) return
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
        setContent { AuraTheme { App() } }
    }
    override fun onResume() {
        super.onResume()
        val manager=getSystemService(NotificationManager::class.java)
        systemAlertsAllowed.value=manager.areNotificationsEnabled() && manager.getNotificationChannel(MonitorService.ALERTS)?.importance!=NotificationManager.IMPORTANCE_NONE
    }
    @Composable private fun App() {
        var tab by rememberSaveable { mutableStateOf("Dashboard") }
        var selectedEvent by rememberSaveable { mutableStateOf<String?>(null) }
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
        BackHandler(enabled=selectedEvent!=null || tab !in listOf("Dashboard","Live","Rules","Events","Settings")) {
            if(selectedEvent!=null) selectedEvent=null else tab="Settings"
        }
        BoxWithConstraints(Modifier.fillMaxSize().testTag("aura-app")) {
        val tablet=maxWidth>=600.dp
        Scaffold(topBar={
            Row(Modifier.fillMaxWidth().background(AuraColors.Background).statusBarsPadding().padding(horizontal=20.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AURA",style=MaterialTheme.typography.headlineLarge,color=AuraColors.Cyan)
                    Text("by kaula.systems",style=MaterialTheme.typography.labelSmall,color=AuraColors.Muted)
                }
                IconButton(onClick={tab="Settings";selectedEvent=null},modifier=Modifier.semantics { contentDescription="Open settings" }) { AuraIcon("Settings") }
            }
        },bottomBar={if(!tablet) AuraNavigation(tab,false) { tab=it;selectedEvent=null }}) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if(tablet) AuraNavigation(tab,true) { tab=it;selectedEvent=null }
            Box(Modifier.weight(1f).fillMaxHeight(),contentAlignment=Alignment.TopCenter) {
            key(tab,selectedEvent) {
            Column(Modifier.widthIn(max=1000.dp).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                if(tablet || tab!="Dashboard") Text(if(tab=="Events" && selectedEvent!=null) "Event Details" else if(tab=="Live") "Live Sound Intelligence" else tab,style=MaterialTheme.typography.headlineSmall)
                if(tab!="Dashboard" && MonitorService.active.value) {
                    Text("${MonitorService.status.value} · Microphone in use · on-device processing",style=MaterialTheme.typography.labelMedium,color=AuraColors.Cyan)
                    TextButton(onClick={stopService(Intent(this@MainActivity,MonitorService::class.java))}) { Text("Stop monitoring") }
                }
                if(MonitorService.error.value.isNotBlank()) Text(MonitorService.error.value,color=MaterialTheme.colorScheme.secondary)
                if(Store.error.value.isNotBlank()) Text(Store.error.value,color=MaterialTheme.colorScheme.error)
                if(message.isNotBlank()) Text(message,color=MaterialTheme.colorScheme.secondary)
                if(backupMessage.value.isNotBlank()) Text(backupMessage.value,color=MaterialTheme.colorScheme.secondary)
                when(tab) {
                    "Dashboard" -> {
                        val evidence=MonitorService.live.value.filterKeys { id->rules.any { it.id==id && it.condition is Condition.Audio } }
                        val best=evidence.filterValues { it.phase==Phase.ACTIVE }.maxByOrNull { it.value.smoothed } ?: evidence.maxByOrNull { it.value.smoothed }
                        val active=best?.value?.phase==Phase.ACTIVE
                        val hero: @Composable ()->Unit = {
                            MonitoringHero(MonitorService.status.value,MonitorService.active.value,MonitorService.modelLoaded.value,
                                !syncing && !fileBusy.value && !MonitorService.finishing.value,compact=!tablet) {
                                if(MonitorService.active.value) stopService(Intent(this@MainActivity,MonitorService::class.java)) else startMonitoring()
                            }
                        }
                        val summary: @Composable ()->Unit = {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                                Box(Modifier.weight(1f)) { AuraPanel {
                                    Text("RMS",style=MaterialTheme.typography.labelLarge,color=AuraColors.Muted)
                                    Text("%.3f".format(MonitorService.level.doubleValue),style=MaterialTheme.typography.headlineMedium)
                                    Text("Uncalibrated amplitude",style=MaterialTheme.typography.bodySmall,color=AuraColors.Muted)
                                } }
                                Box(Modifier.weight(1f)) { AuraPanel {
                                    Text("Current event",style=MaterialTheme.typography.labelLarge,color=AuraColors.Muted)
                                    Text(if(active && best!=null) "Possible ${rules.find { it.id==best.key }?.label}" else "Unknown / unconfirmed",style=MaterialTheme.typography.bodyMedium)
                                    best?.value?.let { Text("Raw %.3f · smoothed %.3f".format(it.raw,it.smoothed),style=MaterialTheme.typography.bodySmall,color=AuraColors.Cyan) }
                                } }
                            }
                            Text(if(MonitorService.modelLoaded.value) "YAMNet · on-device inference" else "Classifier inactive/unavailable · RMS independent",style=MaterialTheme.typography.bodySmall,color=AuraColors.Muted)
                        }
                        if(tablet) Row(horizontalArrangement=Arrangement.spacedBy(20.dp)) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)) { hero() }
                            Column(Modifier.weight(1f)) { summary() }
                        } else { hero();summary() }
                        AuraPanel {
                            Text("Last rule-confirmed episode",style=MaterialTheme.typography.titleMedium)
                            Text(events.firstOrNull()?.let { it.label+" · "+date(it.startMs) } ?: "No events yet",color=AuraColors.Muted)
                            Text("${rules.count { it.enabled }} enabled rules · ${events.size} local events",color=AuraColors.Cyan)
                            TextButton(onClick={tab="Events"}) { Text("View event history →") }
                        }
                        Text("Model scores are uncalibrated. RMS is amplitude, not sound identification or physical decibels. Rule episodes are not proof of real-world events.",style=MaterialTheme.typography.bodySmall,color=AuraColors.Muted)
                        Text("Grant permissions, then tap Start again. No automatic activation, audio storage/upload or emergency calls.",style=MaterialTheme.typography.bodySmall,color=AuraColors.Muted)
                    }
                    "Live" -> {
                        Text("${ModelSpec.VERSION} · ${ModelSpec.MAPPING} · ${ModelSpec.THRESHOLDS}",style=MaterialTheme.typography.labelSmall)
                        Text("Top model outputs (uncalibrated scores)")
                        Text("Live classifications alone do not alert. A matching enabled rule must satisfy its smoothed threshold, persistence and cooldown; notification settings then apply.")
                        if(MonitorService.top.value.isEmpty()) Text("Unknown / unconfirmed. Start monitoring to run local inference.")
                        AuraPanel {
                            Text("Top sound classes",style=MaterialTheme.typography.titleMedium)
                            MonitorService.top.value.forEach { score ->
                                Row(horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                                    AuraIcon(SoundCatalog.events.find { score.classId in it.classIds }?.id ?: "sound")
                                    Column(Modifier.weight(1f)) {
                                        Row { Text(score.label,Modifier.weight(1f));Text("%.3f".format(score.score),color=AuraColors.Cyan) }
                                        LinearProgressIndicator(progress={score.score.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().padding(top=6.dp))
                                    }
                                }
                                Text(score.classId,style=MaterialTheme.typography.labelSmall,color=AuraColors.Muted)
                                Text(RuleFeedback.coverage(score.classId,rules).message,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                        LiveScoreChart(MonitorService.top.value,MonitorService.active.value)
                        if(rules.isEmpty()) Text("No rules yet. Stop monitoring, then create a sound rule in Rules.")
                        Text("Rule evaluation · last processed window",style=MaterialTheme.typography.titleMedium)
                        rules.forEach { rule ->
                            val live=MonitorService.live.value[rule.id]
                            val explanation=RuleFeedback.explain(rule,live,MonitorService.active.value,MonitorService.modelLoaded.value,starting=MonitorService.status.value=="Starting")
                            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                                Text(rule.label);Text(RuleFeedback.classes(rule),style=MaterialTheme.typography.labelSmall)
                                Text(explanation.message)
                                live?.let { Text("${it.phase} · Raw %.3f · EMA %.3f".format(it.raw,it.smoothed)) }
                                explanation.progress?.let { progress -> LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth()) }
                                Text("Next new episode: "+RuleFeedback.notificationSetting(rule,alerts,systemAlertsAllowed.value),style=MaterialTheme.typography.bodySmall)
                                events.firstOrNull { it.ruleId==rule.id }?.let { event ->
                                    Text("Last episode notification: ${event.notificationStatus}. ${RuleFeedback.notificationHistory(event.notificationStatus)}",style=MaterialTheme.typography.labelSmall)
                                }
                            } }
                        }
                    }
                    "Rules" -> {
                        var naturalBuilder by rememberSaveable { mutableStateOf(false) }
                        BackHandler(enabled=naturalBuilder) { naturalBuilder=false }
                        Text("Manage when AURA should notify you",color=AuraColors.Muted)
                        OutlinedButton(onClick={naturalBuilder=!naturalBuilder}) { Text(if(naturalBuilder) "Back to rule management" else "Natural-language builder") }
                        if(naturalBuilder) NaturalRuleBuilder(base,token,onConfig={b,t->base=b;token=t},onMessage={message=it})
                        else RuleEditor(rules,onMessage={message=it})
                    }
                    "Events" -> {
                        val detail=events.find { it.id==selectedEvent }
                        if(detail!=null) EventDetails(detail) { selectedEvent=null }
                        else {
                            Text("Event history · latest 200",color=AuraColors.Muted)
                            if(events.isEmpty()) AuraPanel {
                                AuraIcon("Events",Modifier.size(40.dp))
                                Text("Nothing to report yet",style=MaterialTheme.typography.titleLarge)
                                Text("Create a rule, then explicitly start monitoring. Only event metadata is stored.")
                            }
                            events.forEach { event->EventSummary(event) { selectedEvent=event.id } }
                        }
                    }
                    "Settings" -> {
                        Text("Your AURA",style=MaterialTheme.typography.titleMedium,color=AuraColors.Muted)
                        AuraPanel {
                            listOf("Alerts" to "Notification preferences and Android channels","Diagnostics" to "Model, audio capture and inference cadence",
                                "Devices" to "Device-controlled monitoring · manual HTTPS sync","Backup" to "Export or restore local metadata","About" to "AURA, model and privacy information").forEach { (page,description) ->
                                TextButton(onClick={tab=page},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)) {
                                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) { Text(page,style=MaterialTheme.typography.titleMedium);Text(description,style=MaterialTheme.typography.bodySmall,color=AuraColors.Muted) }
                                    Text("›",style=MaterialTheme.typography.headlineSmall)
                                }
                            }
                        }
                        AuraPanel { Text("Privacy and data",style=MaterialTheme.typography.titleMedium);Text("No audio is recorded or uploaded. Optional LLM previews share only consented rule text with your selected provider. Keys stay on your backend. Saved rules run locally.") }
                    }
                    "About" -> {
                        AuraPanel {
                            Text("AURA",style=MaterialTheme.typography.displaySmall,color=AuraColors.Cyan)
                            Text("by kaula.systems · v0.4.2 (8)")
                            Text("Local-first ambient intelligence. Experimental on-device sound classification with explicit user control.")
                            DetailLine("Model",ModelSpec.VERSION)
                            DetailLine("Classes","521 AudioSet classes · curated rule mapping")
                            Text("Not a security, fire, medical or emergency system. Scores are uncalibrated. No audio recording or autonomous calls.")
                            TextButton(onClick={startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/BSFEAL/aura")))}) { Text("Open source & documentation ↗") }
                        }
                    }
                    "Diagnostics" -> {
                        AuraPanel {
                            Text("Model",style=MaterialTheme.typography.titleMedium)
                            DetailLine("Status",if(MonitorService.modelLoaded.value) "Loaded" else "Inactive / unavailable")
                            DetailLine("Version",ModelSpec.VERSION)
                            DetailLine("Asset size","4,126,810 bytes")
                            DetailLine("Window / classes","975 ms · 15,600 samples · 521 classes")
                        }
                        AuraPanel {
                            Text("Inference performance",style=MaterialTheme.typography.titleMedium)
                            Text(MonitorService.stats.value.ifBlank { "No session measurements yet." })
                        }
                        AuraPanel { Text("Audio capture",style=MaterialTheme.typography.titleMedium);DetailLine("Sample rate","16,000 Hz");DetailLine("Channel / format","Mono · signed PCM16");DetailLine("Current RMS","%.3f".format(MonitorService.level.doubleValue));DetailLine("Engine","CPU · two native threads") }
                        OutlinedTextField(value=cadence,onValueChange={cadence=it},label={Text("Inference interval ms (250–2000)")},modifier=Modifier.fillMaxWidth())
                        Button(enabled=!MonitorService.active.value && !MonitorService.finishing.value,onClick={
                            val ms=cadence.toIntOrNull()
                            if(ms==null || ms !in 250..2000) message="Cadence must be 250–2000 ms"
                            else { Store.setting(this@MainActivity,"cadence",ms.toString()); message="Cadence saved for the next session" }
                        }) { Text("Save cadence") }
                        Text("500 ms default uses overlapping 975 ms windows. Faster cadence uses more processing; slower cadence may miss short sounds. No RMS noise gate is used, so quiet events are not discarded by an amplitude threshold. Battery and thermal measurements require a physical phone.")
                    }
                    "Backup" -> {
                        Text("Local metadata backup",style=MaterialTheme.typography.titleLarge)
                        Text("Save rules and the latest 200 events before changing or reinstalling the app. This feature cannot recover data already removed by an uninstall.")
                        Text("The JSON file contains labels, device IDs, timestamps and uncalibrated scores. It contains no microphone audio, API token, server URL or sync credentials. It is not encrypted; choose a trusted location. A cloud file provider may upload it if you choose one.")
                        Text("Restore merges without replacing existing IDs. Conflicts or capacity overflow reject the entire restore. Newly restored rules are disabled and assigned to this phone; review and enable them yourself. Model and threshold versions stay pinned. History keeps its original device and provenance, labeled as restored and unverified. No microphone activation or alert replay.")
                        val available=!syncing && !fileBusy.value && !MonitorService.active.value && !MonitorService.finishing.value
                        Button(enabled=available,onClick={fileBusy.value=true;backupMessage.value="";exportBackup.launch("aura-metadata-${System.currentTimeMillis()}.json")}) { Text("Export metadata backup") }
                        OutlinedButton(enabled=available,onClick={fileBusy.value=true;backupMessage.value="";importBackup.launch(arrayOf("application/json","text/plain","application/octet-stream"))}) { Text("Restore from backup") }
                        if(fileBusy.value) Text("Working with the selected file…")
                        Text("Stop monitoring before backup or restore. Only AURA metadata format 1 is accepted (up to 1 MiB, 128 rules and 200 events). Android's file picker grants access only to your chosen file; no broad storage permission is requested.")
                    }
                    "Devices" -> {
                        Text("Local phone ${Store.localDeviceId(this@MainActivity)}")
                        Text("Optional explicit HTTPS sync sends event metadata and scores, never audio. Stop monitoring before syncing. Tokens stay in memory.")
                        OutlinedTextField(value=base,onValueChange={base=it},label={Text("HTTPS backend URL")},modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(value=token,onValueChange={token=it},label={Text("API token (not saved)")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true,modifier=Modifier.fillMaxWidth())
                        OutlinedTextField(value=deviceId,onValueChange={deviceId=it},label={Text("Device ID; blank registers phone")},modifier=Modifier.fillMaxWidth())
                        Button(enabled=!syncing && !fileBusy.value && !MonitorService.active.value && !MonitorService.finishing.value,onClick={
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
                        Row { Text("Threshold and possible-sound alerts",Modifier.weight(1f)); Switch(modifier=Modifier.semantics { contentDescription="Threshold and possible-sound alerts" },checked=alerts,onCheckedChange={alerts=it;Store.notifications(this@MainActivity,it)}) }
                        Text(if(systemAlertsAllowed.value) "Android alert requests available; audible sound and banners depend on channel settings, volume and Do Not Disturb." else "Android notifications or the experimental sound-event alert channel are blocked.")
                        OutlinedButton(onClick={
                            val manager=getSystemService(NotificationManager::class.java)
                            val intent=if(manager.getNotificationChannel(MonitorService.ALERTS)!=null)
                                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName).putExtra(Settings.EXTRA_CHANNEL_ID,MonitorService.ALERTS)
                            else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName)
                            try { startActivity(intent) } catch(_: android.content.ActivityNotFoundException) { message="Open Android Settings → Apps → AURA → Notifications" }
                        }) { Text("Android alert settings") }
                        Text("Changing settings does not replay alerts for an already active episode. Check Live for rule evaluation and Events for the historical notification request status. Posted means a request was submitted, not proof that a sound played.")
                        Text("Alerts are experimental and never describe an uncertain model output as a confirmed real-world event. The active microphone notification stays visible. Disabling its channel stops monitoring. No emergency calls.")
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
            }
            }
            }
        }
        }
        pendingRestore.value?.let { backup ->
            AlertDialog(onDismissRequest={pendingRestore.value=null},title={Text("Restore metadata?")},
                text={Text("File contains ${backup.rules.size} rules and ${backup.events.size} events. Existing data is kept. New rules will be disabled. Restored history is unverified. No microphone or notifications will start.")},
                dismissButton={TextButton(onClick={pendingRestore.value=null}) { Text("Cancel") }},
                confirmButton={TextButton(onClick={
                    pendingRestore.value=null;fileBusy.value=true
                    lifecycleScope.launch {
                        try {
                            val plan=withContext(Dispatchers.IO) { Store.restore(this@MainActivity,backup) }
                            backupMessage.value="Restored ${plan.addedRules} disabled rules and ${plan.addedEvents} historical events. Review rules before enabling."
                        } catch(e: CancellationException) { throw e }
                        catch(e: IllegalArgumentException) { backupMessage.value=e.message ?: "Restore rejected; existing data kept" }
                        catch(_: Exception) { backupMessage.value="Restore could not finish. Check storage and local history before retrying." }
                        finally { fileBusy.value=false }
                    }
                }) { Text("Restore disabled rules & history") }})
        }
    }
    @Composable private fun NaturalRuleBuilder(base: String, token: String, onConfig: (String,String)->Unit, onMessage: (String)->Unit) {
        var request by remember { mutableStateOf("") }
        var result by remember { mutableStateOf<RulePlan?>(null) }
        var planning by remember { mutableStateOf(false) }
        var consent by remember { mutableStateOf(false) }
        var provider by remember { mutableStateOf("openai") }
        val providerName=if(provider=="anthropic") "Anthropic" else "OpenAI"
        val focus=LocalFocusManager.current
        val keyboard=LocalSoftwareKeyboardController.current
        val scope=rememberCoroutineScope()
        val stopped=!MonitorService.active.value && !MonitorService.finishing.value
        Text("Describe a monitoring rule",style=MaterialTheme.typography.titleLarge)
        Text("$providerName rule preview via your local/private backend. Only rule text and the supported catalog are sent to $providerName; microphone audio stays on-device. Provider keys belong in the backend environment, never this app. Provider retention policies apply.")
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("openai" to "OpenAI","anthropic" to "Anthropic").forEach { (id,label) ->
                OutlinedButton(enabled=stopped && !planning,onClick={provider=id;result=null;consent=false}) { Text(if(provider==id) "$label ✓" else label) }
            }
        }
        OutlinedTextField(value=base,onValueChange={onConfig(it,token);result=null;consent=false},enabled=stopped && !planning,label={Text("Private HTTPS backend URL")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=token,onValueChange={onConfig(base,it);result=null;consent=false},enabled=stopped && !planning,label={Text("AURA API token (not a provider key)")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password),singleLine=true,modifier=Modifier.fillMaxWidth())
        OutlinedTextField(value=request,onValueChange={request=it;result=null},enabled=stopped && !planning,
            label={Text("What should AURA monitor?")},supportingText={Text("${request.length}/240 characters · preview before saving")},modifier=Modifier.fillMaxWidth())
        Row(Modifier.testTag("natural-rule-examples").horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            listOf("Barking example" to "Notify me when my dog barks for 2 seconds, with a cooldown of 30 seconds",
                "Faucet example" to "Notify me when the tap is running for 3 seconds",
                "Doorbell example" to "Notify me when the doorbell rings").forEach { (label,example) ->
                OutlinedButton(enabled=stopped && !planning,onClick={focus.clearFocus();keyboard?.hide();request=example;result=null}) { Text(label) }
            }
        }
        Row { Text("Allow sending this rule text to $providerName through my backend",Modifier.weight(1f)); Checkbox(modifier=Modifier.semantics { contentDescription="Consent to $providerName rule text sharing" },checked=consent,onCheckedChange={consent=it;result=null},enabled=stopped && !planning) }
        Button(enabled=stopped && !planning && consent && base.isNotBlank() && token.length>=24 && request.isNotBlank() && request.length<=240,onClick={
            focus.clearFocus();keyboard?.hide()
            val submitted=request;planning=true;result=null
            scope.launch {
                try {
                    val planner=rulePlannerFactory(base,token,Store.localDeviceId(this@MainActivity),provider)
                    val proposal=planner.propose(submitted)
                    if(request==submitted) result=proposal
                } catch(e: CancellationException) { throw e }
                catch(_: Exception) { onMessage("Rule preview failed. Check private HTTPS URL, AURA token and backend provider setup. No rule saved.") }
                finally { planning=false }
            }
        }) { Text(if(planning) "Preparing preview…" else "Send text to $providerName & preview") }
        when(val proposal=result) {
            is RulePlan.Clarify -> Text("Clarification needed: ${proposal.message}",color=MaterialTheme.colorScheme.secondary)
            is RulePlan.Ready -> {
                val draft=proposal.draft
                val preview=draft.asDisabledRule("preview","preview-device")
                Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text("Review proposed rule",style=MaterialTheme.typography.titleMedium)
                    Text("${providerName} planner: ${draft.plannerModel} · interpretation unverified",style=MaterialTheme.typography.labelSmall)
                    Text(preview.label);Text(RuleFeedback.classes(preview))
                    when(val c=draft.condition) {
                        is Condition.Audio -> {
                            Text("Activation ${c.minScore} · release ${c.releaseScore} · EMA ${c.emaAlpha}")
                            Text("Persistence ${c.minDurationMs} ms · max gap ${c.maxGapMs} ms")
                            Text("${c.modelVersion} · ${c.mappingVersion} · ${c.thresholdVersion}",style=MaterialTheme.typography.labelSmall)
                        }
                        is Condition.Rms -> Text("RMS strictly above ${c.threshold}; legacy repeat ${c.legacyRepeat}")
                    }
                    Text("Cooldown ${draft.policy.cooldownMs} ms · notify ${draft.policy.notify} · saved disabled")
                    draft.assumptions.forEach { Text(it,style=MaterialTheme.typography.bodySmall) }
                    Button(enabled=stopped && !planning && request==draft.request,onClick={
                        try {
                            check(request==draft.request && !MonitorService.active.value && !MonitorService.finishing.value) { "Preview again while monitoring is stopped" }
                            val rule=draft.asDisabledRule(UUID.randomUUID().toString(),Store.localDeviceId(this@MainActivity))
                            Store.saveRules(this@MainActivity,Store.rules(this@MainActivity)+rule)
                            result=null;request="";onMessage("Disabled rule saved. Review and enable it in Rules, then Start monitoring explicitly.")
                        } catch(_: Exception) { onMessage("Rule could not be saved. Check local capacity and preview again while stopped.") }
                    }) { Text("Save disabled rule") }
                    TextButton(onClick={result=null}) { Text("Discard preview") }
                } }
            }
            null -> Unit
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
        var showForm by remember { mutableStateOf(false) }
        BackHandler(enabled=showForm) { editing=null;showForm=false }
        if(!showForm) {
            Button(onClick={showForm=true;editing=null},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text("+ Create rule") }
            if(rules.isEmpty()) AuraPanel { Text("No rules yet",style=MaterialTheme.typography.titleLarge);Text("Create a sound or RMS rule, or use the natural-language builder. Creating a rule never activates the microphone.") }
        rules.forEach { rule -> AuraPanel {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                AuraIcon((rule.condition as? Condition.Audio)?.eventType ?: "rms_above",Modifier.size(32.dp))
                Text(rule.label,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                Switch(modifier=Modifier.semantics { contentDescription="Enable ${rule.label}" },checked=rule.enabled,onCheckedChange={enabled->Store.saveRules(this@MainActivity,rules.map { if(it.id==rule.id) it.copy(enabled=enabled) else it })})
            }
            Text(when(val c=rule.condition) {
                is Condition.Audio -> "Score ≥ ${c.minScore} · ${c.minDurationMs} ms duration · ${rule.policy.cooldownMs} ms cooldown"
                is Condition.Rms -> "RMS > ${c.threshold} · ${rule.policy.cooldownMs} ms cooldown"
            },style=MaterialTheme.typography.bodySmall,color=AuraColors.Cyan)
            Text(RuleFeedback.classes(rule),style=MaterialTheme.typography.labelSmall)
            Text("Notifications: "+RuleFeedback.notificationSetting(rule,Store.notifications(this@MainActivity),systemAlertsAllowed.value),style=MaterialTheme.typography.bodySmall)
            Text("Schema ${rule.schemaVersion} · ${if(rule.remote) "API download" else "local"}",style=MaterialTheme.typography.labelSmall)
            rule.unavailableReason(MonitorService.modelLoaded.value)?.let { Text(it,color=MaterialTheme.colorScheme.secondary) }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TextButton(enabled=!MonitorService.active.value && !MonitorService.finishing.value,onClick={
                    showForm=true;editing=rule;cooldown=rule.policy.cooldownMs.toString();notify=rule.policy.notify
                    when(val c=rule.condition) {
                        is Condition.Rms -> { eventType="rms_above";value=c.threshold.toString() }
                        is Condition.Audio -> { eventType=c.eventType;classId=c.classId;value=c.minScore.toString();release=c.releaseScore.toString();persistence=c.minDurationMs.toString();gap=c.maxGapMs.toString();alpha=c.emaAlpha.toString() }
                    }
                }) { Text("Edit") }
                TextButton(onClick={Store.saveRules(this@MainActivity,rules.filter { it.id!=rule.id })}) { Text("Delete") }
            }
        } }
            Text("Stop monitoring to edit or create rules. Unsupported model/mapping versions are displayed and never evaluated.",style=MaterialTheme.typography.bodySmall,color=AuraColors.Muted)
            return
        }
        TextButton(onClick={editing=null;showForm=false}) { Text("← Back to rules") }
        Text(if(editing==null) "Create a typed rule" else "Edit ${editing?.label}",style=MaterialTheme.typography.titleMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            (listOf("rms_above" to "RMS")+SoundCatalog.events.map { it.id to it.label }).forEach { (id,label) -> FilterChip(selected=eventType==id,onClick={eventType=id;classId=null;value=if(id=="rms_above") "0.15" else "0.7"},label={Text(label)}) }
        }
        OutlinedTextField(value=value,onValueChange={value=it},label={Text(if(eventType=="rms_above") "RMS threshold" else "Activation score (provisional)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth())
        Slider(value=(value.toFloatOrNull()?.takeIf { it.isFinite() } ?: .7f).coerceIn(.01f,1f),onValueChange={value="%.2f".format(java.util.Locale.US,it)},valueRange=.01f..1f,modifier=Modifier.semantics { contentDescription="Activation threshold" })
        if(eventType!="rms_above") {
            Text("Optional presets · uncalibrated starting points. Applying one only changes this form; Save is required. Existing saved rules do not change automatically.")
            RulePresets.all.forEach { preset -> OutlinedButton(enabled=!MonitorService.active.value && !MonitorService.finishing.value,onClick={
                val c=preset.apply(Condition.Audio(eventType,classId=classId))
                value=c.minScore.toString();release=c.releaseScore.toString();persistence=c.minDurationMs.toString();gap=c.maxGapMs.toString();alpha=c.emaAlpha.toString()
                onMessage("${preset.label}: score $value / release $release, persistence $persistence ms, gap $gap ms, EMA $alpha. Cooldown, notify and pinned versions are unchanged. Review then Save.")
            }) { Text(preset.label) } }
            Text("Mapped classes: "+(classId?.let { setOf(it) } ?: requireNotNull(SoundCatalog.event(eventType)).classIds).sorted().joinToString { "${SoundCatalog.classLabels[it]} ($it)" })
            if(eventType=="dog_bark") Text("This rule uses Bark, not the broader Dog class. A high Dog score alone does not satisfy it.")
            Text("Per-class threshold scope (all mapped classes use max score by default)")
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(selected=classId==null,onClick={classId=null},label={Text("All mapped classes")})
                SoundCatalog.event(eventType)?.classIds?.sorted()?.forEach { id -> FilterChip(selected=classId==id,onClick={classId=id},label={Text(SoundCatalog.classLabels[id] ?: id)}) }
            }
            OutlinedTextField(value=release,onValueChange={release=it},label={Text("Release score (< activation)")},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=persistence,onValueChange={persistence=it},label={Text("Persistence ms (0–60000)")},modifier=Modifier.fillMaxWidth())
            Slider(value=(persistence.toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f).coerceIn(0f,60000f),onValueChange={persistence=it.toLong().toString()},valueRange=0f..60000f,modifier=Modifier.semantics { contentDescription="Minimum duration" })
            OutlinedTextField(value=gap,onValueChange={gap=it},label={Text("Max gap ms (0–10000)")},modifier=Modifier.fillMaxWidth())
            Slider(value=(gap.toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f).coerceIn(0f,10000f),onValueChange={gap=it.toLong().toString()},valueRange=0f..10000f,modifier=Modifier.semantics { contentDescription="Maximum gap" })
            OutlinedTextField(value=alpha,onValueChange={alpha=it},label={Text("EMA alpha (0.01–1)")},modifier=Modifier.fillMaxWidth())
            Text("${if(MonitorService.modelLoaded.value) "Classifier available" else "Classifier inactive/unavailable until monitoring starts"}. Scores are uncalibrated. Doorbell means a sound, not a door opening.")
        }
        OutlinedTextField(value=cooldown,onValueChange={cooldown=it},label={Text("Cooldown ms (0–3600000)")},modifier=Modifier.fillMaxWidth())
        Slider(value=(cooldown.toFloatOrNull()?.takeIf { it.isFinite() } ?: 0f).coerceIn(0f,3600000f),onValueChange={cooldown=it.toLong().toString()},valueRange=0f..3600000f,modifier=Modifier.semantics { contentDescription="Cooldown period" })
        Row { Text("Notify for this rule",Modifier.weight(1f)); Switch(modifier=Modifier.semantics { contentDescription="Notify for this rule" },checked=notify,onCheckedChange={notify=it}) }
        Button(enabled=!MonitorService.active.value && !MonitorService.finishing.value,onClick={
            try {
                val condition=if(eventType=="rms_above") Condition.Rms(value.toDouble(),(editing?.condition as? Condition.Rms)?.legacyRepeat ?: false) else Condition.Audio(eventType,value.toFloat(),release.toFloat(),persistence.toLong(),gap.toLong(),alpha.toFloat(),
                    modelVersion=(editing?.condition as? Condition.Audio)?.modelVersion ?: ModelSpec.VERSION,
                    mappingVersion=(editing?.condition as? Condition.Audio)?.mappingVersion ?: ModelSpec.MAPPING,
                    thresholdVersion=(editing?.condition as? Condition.Audio)?.thresholdVersion ?: ModelSpec.THRESHOLDS,classId=classId)
                val rule=LocalRule(id=editing?.id ?: UUID.randomUUID().toString(),enabled=editing?.enabled ?: true,source=editing?.source ?: Source(Store.localDeviceId(this@MainActivity)),condition=condition,policy=Policy(cooldown.toLong(),notify),text=editing?.text ?: "",remote=editing?.remote ?: false).validate()
                Store.saveRules(this@MainActivity,rules.filter { it.id!=rule.id }+rule);editing=null;showForm=false;onMessage("Rule saved; microphone activation remains manual")
            } catch(_: Exception) { onMessage("Invalid rule. Check finite scores, release < activation, duration/gap/cooldown ranges and selected capability.") }
        }) { Text(if(editing==null) "Save rule" else "Save changes") }
        if(editing!=null) TextButton(onClick={editing=null}) { Text("Cancel edit") }
        Text("Legacy RMS language input")
        OutlinedTextField(value=natural,onValueChange={natural=it},label={Text("Natural language RMS rule")},modifier=Modifier.fillMaxWidth())
        Button(enabled=!MonitorService.active.value && !MonitorService.finishing.value,onClick={
            val threshold=Signal.parseRule(natural)
            if(threshold==null) onMessage("Supported: Notify me when sound level is above 0.15")
            else try { Store.saveRules(this@MainActivity,rules+LocalRule(id=UUID.randomUUID().toString(),source=Source(Store.localDeviceId(this@MainActivity)),condition=Condition.Rms(threshold,true),text=natural));onMessage("RMS rule saved") } catch(e: Exception) { onMessage(e.message ?: "Could not save") }
        }) { Text("Create local RMS rule") }
        Text("Stop monitoring to edit or create rules. Changing a rule resets its detector. Unsupported model/mapping versions are displayed and never evaluated.")
    }
    private fun date(ms: Long)=DateFormat.getDateTimeInstance().format(Date(ms))
}

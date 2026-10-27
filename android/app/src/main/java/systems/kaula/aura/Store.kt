package systems.kaula.aura

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.UUID

@Serializable data class LocalEvent(
    @SerialName("schema_version") val schemaVersion: Int = 2,
    val id: String,
    @SerialName("rule_id") val ruleId: String,
    @SerialName("device_id") val deviceId: String = "local-phone",
    @SerialName("event_type") val eventType: String = "rms_above",
    @SerialName("class_id") val classId: String? = null,
    val label: String = "RMS threshold exceeded",
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long? = null,
    @SerialName("duration_ms") val durationMs: Long = 0,
    @SerialName("peak_score") val peakScore: Float? = null,
    @SerialName("mean_score") val meanScore: Float? = null,
    @SerialName("model_version") val modelVersion: String? = null,
    @SerialName("threshold_version") val thresholdVersion: String? = null,
    @SerialName("mapping_version") val mappingVersion: String? = null,
    val provenance: String = "device_reported",
    @SerialName("notification_status") val notificationStatus: String = "disabled",
    val state: String = "ended",
    @SerialName("restored_from_backup") val restoredFromBackup: Boolean = false,
    val rms: Double = 0.0
)
fun Episode.toLocalEvent(rule: LocalRule?, deviceId: String, notificationStatus: String, fallbackLabel: String?=null): LocalEvent =
    LocalEvent(id=id,ruleId=ruleId,deviceId=deviceId,eventType=eventType,classId=classId,
        label=rule?.label ?: fallbackLabel ?: eventType,startMs=startMs,endMs=endMs,durationMs=durationMs,
        peakScore=peak.takeIf { modelVersion!=null },meanScore=mean.takeIf { modelVersion!=null },modelVersion=modelVersion,
        thresholdVersion=thresholdVersion,mappingVersion=ModelSpec.MAPPING.takeIf { modelVersion!=null },
        provenance=if(modelVersion==null) "device_reported" else "model_detected",notificationStatus=notificationStatus,state=state,rms=rms)

object EventCodec {
    fun encode(event: LocalEvent) = RuleCodec.json.encodeToString(event)
    fun decode(text: String): LocalEvent {
        val r=RuleCodec.json.parseToJsonElement(text).jsonObject
        if("schema_version" in r) return RuleCodec.json.decodeFromString<LocalEvent>(text).also { require(it.schemaVersion==2) }
        return LocalEvent(id=r.getValue("id").jsonPrimitive.content,ruleId=r.getValue("ruleId").jsonPrimitive.content,
            startMs=r.getValue("time").jsonPrimitive.long,endMs=r.getValue("time").jsonPrimitive.long,rms=r.getValue("rms").jsonPrimitive.double,
            notificationStatus="legacy_unknown")
    }
}
object Store {
    val revision=mutableIntStateOf(0)
    val error=mutableStateOf("")
    private fun prefs(context: Context)=context.getSharedPreferences("aura",Context.MODE_PRIVATE)
    private fun entries(context: Context, key: String): List<JsonElement> = try {
        RuleCodec.json.parseToJsonElement(prefs(context).getString(key,"[]") ?: "[]").jsonArray.toList()
    } catch(e: Exception) { error.value="Unreadable $key retained in app storage: ${e.message}"; emptyList() }
    @Synchronized fun rules(context: Context): List<LocalRule> {
        val raw=entries(context,"rules")
        val decoded=raw.mapNotNull { runCatching { RuleCodec.decode(it.toString()) }.getOrElse { e ->
            prefs(context).edit().putString("rules_quarantine",raw.toString()).apply()
            error.value="Unsupported/corrupt rules retained in quarantine: ${e.message}"; null
        } }
        if(decoded.size>128) {
            prefs(context).edit().putString("rules_quarantine",raw.toString()).apply()
            error.value="Rule limit exceeded: evaluating first 128 only; all originals retained in quarantine"
            return decoded.take(128)
        }
        if(raw.any { it is JsonObject && "schema_version" !in it } && decoded.size==raw.size) {
            prefs(context).edit().putString("rules_v02_backup",raw.toString()).apply()
            saveRules(context,decoded)
        }
        return decoded
    }
    @Synchronized fun saveRules(context: Context, rules: List<LocalRule>) {
        require(rules.size<=128) { "At most 128 rules per phone" }
        require(rules.map { it.id }.toSet().size==rules.size) { "Duplicate rule IDs" }
        val text=rules.joinToString(prefix="[",postfix="]") { RuleCodec.encode(it) }
        prefs(context).edit().putString("rules",text).apply(); revision.intValue++
    }
    @Synchronized fun replaceRemoteRules(context: Context, remote: List<LocalRule>) = saveRules(context,rules(context).filter { !it.remote }+remote)
    @Synchronized fun events(context: Context): List<LocalEvent> {
        val raw=entries(context,"events")
        return raw.mapNotNull { runCatching { EventCodec.decode(it.toString()) }.getOrElse { e ->
            prefs(context).edit().putString("events_quarantine",raw.toString()).apply(); error.value="Unreadable events retained: ${e.message}"; null
        } }
    }
    @Synchronized fun saveEvent(context: Context, event: LocalEvent) {
        val old=events(context)
        if(!prefs(context).contains("events_v02_backup")) prefs(context).edit().putString("events_v02_backup",prefs(context).getString("events","[]")).apply()
        val updated=(listOf(event)+old.filter { it.id!=event.id }).sortedByDescending { it.startMs }.take(200)
        prefs(context).edit().putString("events",updated.joinToString(prefix="[",postfix="]") { EventCodec.encode(it) }).apply()
        revision.intValue++
    }
    @Synchronized fun recoverInterruptedEvents(context: Context) {
        // A process kill cannot provide a true end time; use the last persisted evidence duration, never invent a wall-clock end.
        events(context).filter { it.endMs==null }.forEach {
            saveEvent(context,it.copy(endMs=it.startMs+it.durationMs,state="process_terminated"))
        }
    }
    @Synchronized fun backup(context: Context): LocalBackup {
        check(!MonitorService.active.value && !MonitorService.finishing.value) { "Stop monitoring before backup" }
        // Decode directly: a backup must fail rather than silently omit quarantined/corrupt entries.
        fun raw(key: String) = RuleCodec.json.parseToJsonElement(prefs(context).getString(key,"[]") ?: "[]").jsonArray
        return BackupCodec.validate(LocalBackup(createdAtMs=System.currentTimeMillis(), deviceId=localDeviceId(context),
            rules=raw("rules").map { RuleCodec.decode(it.toString()) }, events=raw("events").map { EventCodec.decode(it.toString()) }))
    }
    @Synchronized fun restore(context: Context, incoming: LocalBackup): RestorePlan {
        check(!MonitorService.active.value && !MonitorService.finishing.value) { "Stop monitoring before restore" }
        val current=backup(context)
        val plan=BackupCodec.merge(incoming,current.rules,current.events,current.deviceId)
        // One durable write for both collections. Never import settings, credentials, identities or sync markers.
        val saved=prefs(context).edit()
            .putString("rules",plan.rules.joinToString(prefix="[",postfix="]") { RuleCodec.encode(it) })
            .putString("events",plan.events.joinToString(prefix="[",postfix="]") { EventCodec.encode(it) }).commit()
        check(saved) { "Restore could not be saved; restart the app and check storage" }
        revision.intValue++
        return plan
    }
    fun setting(context: Context,key: String)=prefs(context).getString(key,"") ?: ""
    fun setting(context: Context,key: String,value: String) { prefs(context).edit().putString(key,value).apply() }
    fun notifications(context: Context)=prefs(context).getBoolean("alerts",true)
    fun notifications(context: Context,enabled: Boolean) { prefs(context).edit().putBoolean("alerts",enabled).apply() }
    fun cadence(context: Context)=setting(context,"cadence").toIntOrNull()?.coerceIn(250,2000) ?: 500
    fun localDeviceId(context: Context): String {
        val existing=setting(context,"localDeviceId")
        return existing.ifBlank { UUID.randomUUID().toString().also { setting(context,"localDeviceId",it) } }
    }
}

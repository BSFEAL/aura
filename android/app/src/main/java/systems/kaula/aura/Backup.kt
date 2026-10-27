package systems.kaula.aura

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable data class LocalBackup(
    val format: String = "aura-local-metadata",
    @SerialName("format_version") val formatVersion: Int = 1,
    @SerialName("created_at_ms") val createdAtMs: Long,
    @SerialName("device_id") val deviceId: String,
    val rules: List<LocalRule>,
    val events: List<LocalEvent>
)
data class RestorePlan(val rules: List<LocalRule>, val events: List<LocalEvent>, val addedRules: Int, val addedEvents: Int)

/** Portable metadata only. No credentials, preferences, microphone samples or networking. */
object BackupCodec {
    const val MAX_BYTES = 1024 * 1024
    const val MAX_RULES = 128
    const val MAX_EVENTS = 200
    private const val MAX_TIME = 4102444800000L // 2100-01-01, bounds arithmetic as well as timestamps.
    private fun id(value: String) = require(value.isNotBlank() && value.length <= 80 && value.none { it.isISOControl() }) { "Invalid metadata identifier" }
    fun validate(backup: LocalBackup): LocalBackup {
        require(backup.format == "aura-local-metadata" && backup.formatVersion == 1) { "Unsupported AURA backup format/version" }
        require(backup.createdAtMs in 0..MAX_TIME) { "Invalid backup timestamp" }
        id(backup.deviceId)
        require(backup.rules.size <= MAX_RULES && backup.events.size <= MAX_EVENTS) { "Backup exceeds local rule/event limits" }
        require(backup.rules.map { it.id }.toSet().size == backup.rules.size) { "Duplicate rule IDs in backup" }
        require(backup.events.map { it.id }.toSet().size == backup.events.size) { "Duplicate event IDs in backup" }
        backup.rules.forEach { it.validate(); id(it.id); id(it.source.deviceId) }
        backup.events.forEach { validateEvent(it) }
        return backup
    }
    private fun validateEvent(e: LocalEvent) {
        require(e.schemaVersion == 2) { "Unsupported event schema" }
        id(e.id); id(e.ruleId); id(e.deviceId)
        require(e.label.length <= 240 && e.state.length in 1..80) { "Invalid event label/state" }
        require(e.startMs in 0..MAX_TIME && e.durationMs in 0..(MAX_TIME-e.startMs)) { "Invalid event time/duration" }
        require(e.endMs == null || (e.endMs in e.startMs..MAX_TIME && e.endMs-e.startMs == e.durationMs)) { "Event duration does not match timestamps" }
        require(e.rms.isFinite() && e.rms in 0.0..1.0) { "Invalid RMS amplitude" }
        require(e.provenance in setOf("device_reported", "model_detected", "simulated")) { "Invalid event provenance" }
        require(e.notificationStatus in setOf("disabled", "posted", "permission_denied", "legacy_unknown")) { "Invalid notification status" }
        if (e.eventType == "rms_above") {
            require(e.classId == null && e.peakScore == null && e.meanScore == null && e.modelVersion == null && e.mappingVersion == null && e.thresholdVersion == null && e.provenance != "model_detected") { "RMS events cannot claim classifier evidence" }
        } else {
            val supported = requireNotNull(SoundCatalog.event(e.eventType)) { "Unsupported sound event" }
            require(e.classId in supported.classIds) { "Class ID does not match sound event" }
            require(e.peakScore != null && e.peakScore.isFinite() && e.peakScore in 0f..1f &&
                e.meanScore != null && e.meanScore.isFinite() && e.meanScore in 0f..e.peakScore) { "Invalid event scores" }
            listOf(e.modelVersion, e.mappingVersion, e.thresholdVersion).forEach { require(it != null); id(it) }
        }
    }
    fun encode(backup: LocalBackup): ByteArray = RuleCodec.json.encodeToString(validate(backup)).toByteArray(Charsets.UTF_8).also {
        require(it.size <= MAX_BYTES) { "Backup exceeds 1 MiB" }
    }
    fun read(input: InputStream): LocalBackup {
        val bytes = input.readBytesBounded()
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        // Bound nesting before the serializer sees an externally supplied file.
        var depth = 0; var quoted = false; var escaped = false
        text.forEach { c ->
            if (quoted) { if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false }
            else when(c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 32) { "Backup nesting limit exceeded" } }
                '}', ']' -> { depth--; require(depth >= 0) { "Invalid backup JSON" } }
            }
        }
        require(!quoted && depth == 0) { "Invalid backup JSON" }
        return validate(RuleCodec.json.decodeFromString<LocalBackup>(text))
    }
    private fun InputStream.readBytesBounded(): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val block = ByteArray(8192)
        while (true) {
            val count = read(block)
            if (count == -1) break
            require(count > 0) { "Backup provider returned no data" }
            require(output.size() + count <= MAX_BYTES) { "Backup exceeds 1 MiB" }
            output.write(block, 0, count)
        }
        return output.toByteArray()
    }
    fun merge(backup: LocalBackup, rules: List<LocalRule>, events: List<LocalEvent>, deviceId: String): RestorePlan {
        validate(backup); id(deviceId)
        fun local(rule: LocalRule) = rule.copy(enabled=false, remote=false, source=Source(deviceId))
        fun historical(event: LocalEvent) = event.copy(restoredFromBackup=true,
            endMs=event.endMs ?: event.startMs+event.durationMs,
            state=if(event.endMs == null) "restored_interrupted" else event.state)
        val oldRules = rules.associateBy { it.id }
        val oldEvents = events.associateBy { it.id }
        val incomingRules = backup.rules.map(::local)
        val incomingEvents = backup.events.map(::historical)
        incomingRules.forEach { incoming ->
            val existing = oldRules[incoming.id]
            require(existing == null || local(existing) == incoming) { "Rule ID conflict; existing data kept" }
        }
        incomingEvents.forEach { incoming ->
            val existing = oldEvents[incoming.id]
            require(existing == null || historical(existing) == incoming) { "Event ID conflict; existing data kept" }
        }
        val addRules = incomingRules.filter { it.id !in oldRules }
        val addEvents = incomingEvents.filter { it.id !in oldEvents }
        require(rules.size+addRules.size <= MAX_RULES && events.size+addEvents.size <= MAX_EVENTS) { "Restore would exceed local limits; existing data kept" }
        return RestorePlan(rules+addRules, (events+addEvents).sortedByDescending { it.startMs }, addRules.size, addEvents.size)
    }
}

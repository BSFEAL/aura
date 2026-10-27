package systems.kaula.aura

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

@Serializable data class Source(@SerialName("device_id") val deviceId: String = "local-phone", val modality: String = "audio")
@Serializable sealed class Condition {
    @Serializable @SerialName("rms_above") data class Rms(val threshold: Double, @SerialName("legacy_repeat") val legacyRepeat: Boolean = false) : Condition()
    @Serializable @SerialName("audio_event") data class Audio(
        @SerialName("event_type") val eventType: String,
        @SerialName("min_score") val minScore: Float = .7f,
        @SerialName("release_score") val releaseScore: Float = .5f,
        @SerialName("min_duration_ms") val minDurationMs: Long = 3000,
        @SerialName("max_gap_ms") val maxGapMs: Long = 1000,
        @SerialName("ema_alpha") val emaAlpha: Float = .5f,
        @SerialName("model_version") val modelVersion: String = ModelSpec.VERSION,
        @SerialName("mapping_version") val mappingVersion: String = ModelSpec.MAPPING,
        @SerialName("threshold_version") val thresholdVersion: String = ModelSpec.THRESHOLDS,
        @SerialName("class_id") val classId: String? = null
    ) : Condition()
}
@Serializable data class Policy(@SerialName("cooldown_ms") val cooldownMs: Long = 30000, val notify: Boolean = true)
@Serializable data class LocalRule(
    @SerialName("schema_version") val schemaVersion: Int = 2,
    val id: String,
    val enabled: Boolean = true,
    val source: Source = Source(),
    val condition: Condition,
    val policy: Policy = Policy(),
    val text: String = "",
    val remote: Boolean = false
) {
    fun validate(): LocalRule {
        require(schemaVersion == 2 && id.isNotBlank() && id.length <= 80)
        require(source.modality == "audio" && source.deviceId.isNotBlank() && source.deviceId.length<=80)
        require(text.length<=240 && policy.cooldownMs in 0..3600000)
        when(val c=condition) {
            is Condition.Rms -> require(c.threshold.isFinite() && c.threshold>0 && c.threshold<=1)
            is Condition.Audio -> {
                require(SoundCatalog.event(c.eventType)!=null)
                require(c.classId==null || c.classId in requireNotNull(SoundCatalog.event(c.eventType)).classIds)
                require(c.minScore.isFinite() && c.minScore in .01f..1f && c.releaseScore.isFinite() && c.releaseScore>=0 && c.releaseScore<c.minScore)
                require(c.minDurationMs in 0..60000 && c.maxGapMs in 0..10000 && c.emaAlpha.isFinite() && c.emaAlpha in .01f..1f)
                require(c.modelVersion.length in 1..80 && c.mappingVersion.length in 1..80 && c.thresholdVersion.length in 1..80)
            }
        }
        return this
    }
    fun unavailableReason(modelAvailable: Boolean): String? = when(val c=condition) {
        is Condition.Rms -> null
        is Condition.Audio -> when {
            c.modelVersion!=ModelSpec.VERSION || c.mappingVersion!=ModelSpec.MAPPING || c.thresholdVersion!=ModelSpec.THRESHOLDS -> "Unsupported model/mapping/threshold version; rule is not evaluated"
            !modelAvailable -> "Classifier unavailable; RMS rules still work"
            else -> null
        }
    }
    val label: String get() = when(val c=condition) { is Condition.Rms -> "RMS above ${c.threshold}"; is Condition.Audio -> SoundCatalog.event(c.eventType)?.label ?: "Unsupported event" }
}
object RuleCodec {
    val json=Json { encodeDefaults=true; ignoreUnknownKeys=false; classDiscriminator="type" }
    fun encode(rule: LocalRule) = json.encodeToString(rule.validate())
    fun decode(text: String): LocalRule {
        val root=json.parseToJsonElement(text).jsonObject
        if("schema_version" in root) return json.decodeFromString<LocalRule>(text).validate()
        // v0.2 semantics: immediate RMS triggers repeating every 30 s during a continuous signal.
        require(root.keys.all { it in setOf("id","text","threshold","enabled","remote") })
        return LocalRule(id=root.getValue("id").jsonPrimitive.content,
            enabled=root.getValue("enabled").jsonPrimitive.boolean,
            text=root.getValue("text").jsonPrimitive.content,
            remote=root["remote"]?.jsonPrimitive?.boolean ?: false,
            condition=Condition.Rms(root.getValue("threshold").jsonPrimitive.double, legacyRepeat=true)).validate()
    }
}

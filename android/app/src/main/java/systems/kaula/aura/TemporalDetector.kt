package systems.kaula.aura

import java.util.UUID

enum class Phase { IDLE, CANDIDATE, ACTIVE, COOLDOWN }
data class Episode(
    val id: String, val ruleId: String, val startMs: Long, val endMs: Long?,
    val durationMs: Long, val peak: Float, val mean: Float, val state: String,
    val eventType: String, val classId: String?, val modelVersion: String?,
    val thresholdVersion: String?, val rms: Double
)
data class DetectorUpdate(val phase: Phase, val raw: Float, val smoothed: Float, val persistenceMs: Long, val started: Episode?=null, val ended: Episode?=null)

/** One instance per rule; bounded scalar state. Wall timestamps anchored at episode start. */
class TemporalDetector(private val rule: LocalRule, private val newId: ()->String = { UUID.randomUUID().toString() }) {
    private val c=rule.condition as? Condition.Audio
    private val on=c?.minScore ?: (rule.condition as Condition.Rms).threshold.toFloat()
    private val off=c?.releaseScore ?: on
    private val persistence=c?.minDurationMs ?: 0L
    private val gap=c?.maxGapMs ?: 0L
    private val alpha=c?.emaAlpha ?: 1f
    var phase=Phase.IDLE; private set
    private var smooth: Float?=null
    private var previous: Long?=null
    private var candidate=0L
    private var anchorWall=0L
    private var lastEvidence=0L
    private var activated=0L
    private var cooldownUntil=0L
    private var id=""
    private var peak=0f
    private var sum=0.0
    private var count=0L
    private var lastRms=0.0
    private var belowEvidence=false
    private var winningClass: String?=null
    private fun episode(end: Long?, reason: String): Episode {
        val event=c?.eventType ?: "rms_above"
        return Episode(id,rule.id,anchorWall,end?.let { anchorWall+(it-candidate).coerceAtLeast(0) },
            ((end ?: lastEvidence)-candidate).coerceAtLeast(0),peak,if(count==0L) 0f else (sum/count).toFloat(),reason,event,
            winningClass ?: c?.let { SoundCatalog.event(event)?.classIds?.sorted()?.first() },c?.modelVersion,c?.thresholdVersion,lastRms)
    }
    fun reset(now: Long, reason: String="interrupted"): Episode? {
        val ended=if(phase==Phase.ACTIVE) episode(previous ?: now,reason) else null
        phase=Phase.IDLE; smooth=null; previous=null; cooldownUntil=0; id=""; sum=0.0; count=0; belowEvidence=false; winningClass=null
        return ended
    }
    fun update(raw: Float, mono: Long, wall: Long, rms: Double=0.0, classId: String?=null): DetectorUpdate {
        require(raw.isFinite() && raw in 0f..1f && rms.isFinite() && rms in 0.0..1.0)
        val discontinuity=previous?.let { mono<it || mono-it>10000 } ?: false
        val interrupted=if(discontinuity) reset(mono,"clock_or_capture_gap") else null
        previous=mono; lastRms=rms
        // A return after an observed low gap cannot bridge an expired candidate/episode.
        if(belowEvidence && mono-lastEvidence>gap) {
            if(phase==Phase.CANDIDATE) phase=Phase.IDLE
            else if(phase==Phase.ACTIVE) {
                val ended=episode(lastEvidence,"ended")
                phase=if(mono<cooldownUntil) Phase.COOLDOWN else Phase.IDLE
                smooth=null; belowEvidence=false
                return DetectorUpdate(phase,raw,raw,0,ended=ended)
            }
        }
        val smoothed=(smooth?.let { alpha*raw+(1-alpha)*it } ?: raw).also { smooth=it }
        if(phase==Phase.COOLDOWN && mono>=cooldownUntil) phase=Phase.IDLE
        if(phase==Phase.IDLE && smoothed>=on && (c!=null || raw>on)) {
            phase=Phase.CANDIDATE; candidate=mono; anchorWall=wall; lastEvidence=mono
            peak=raw; sum=0.0; count=0; winningClass=classId; belowEvidence=false
        }
        if(phase==Phase.CANDIDATE) {
            belowEvidence=!(smoothed>=on && (c!=null || raw>on))
            if(!belowEvidence) lastEvidence=mono
            if(mono-lastEvidence>gap) { phase=Phase.IDLE; candidate=mono }
            else {
                if(raw>=peak && classId!=null) winningClass=classId
                peak=maxOf(peak,raw); sum+=raw; count++
                if(smoothed>=on && mono-candidate>=persistence && mono>=cooldownUntil) {
                    phase=Phase.ACTIVE; activated=mono; cooldownUntil=mono+rule.policy.cooldownMs; id=newId()
                    return DetectorUpdate(phase,raw,smoothed,mono-candidate,started=episode(null,"active"),ended=interrupted)
                }
            }
        } else if(phase==Phase.ACTIVE) {
            if(raw>=peak && classId!=null) winningClass=classId
            peak=maxOf(peak,raw); sum+=raw; count++
            belowEvidence=!(smoothed>=off && (c!=null || raw>on))
            if(!belowEvidence) lastEvidence=mono
            val legacy=(rule.condition as? Condition.Rms)?.legacyRepeat==true
            if(mono-lastEvidence>gap || (legacy && mono-activated>=rule.policy.cooldownMs)) {
                val ended=episode(if(legacy) mono else lastEvidence,"ended")
                phase=if(mono<cooldownUntil) Phase.COOLDOWN else Phase.IDLE
                return DetectorUpdate(phase,raw,smoothed,0,ended=ended)
            }
        }
        return DetectorUpdate(phase,raw,smoothed,if(phase==Phase.CANDIDATE || phase==Phase.ACTIVE) mono-candidate else 0,ended=interrupted)
    }
}

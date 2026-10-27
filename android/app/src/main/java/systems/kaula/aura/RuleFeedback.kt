package systems.kaula.aura

import java.util.Locale

enum class RuleReason { DISABLED, STOPPED, UNAVAILABLE, WAITING, SCORE, PERSISTENCE, GAP, ACTIVE, COOLDOWN }
data class RuleExplanation(val reason: RuleReason, val message: String, val progress: Float?=null)
enum class Coverage { UNMAPPED, NO_RULE, DISABLED, UNAVAILABLE, ENABLED }
data class ClassCoverage(val state: Coverage, val message: String)

/** Explanations use the detector's actual snapshot; they do not evaluate or change a rule. */
object RuleFeedback {
    private fun score(value: Float) = String.format(Locale.ROOT,"%.3f",value)
    fun explain(rule: LocalRule, update: DetectorUpdate?, monitoring: Boolean, modelAvailable: Boolean, starting: Boolean=false): RuleExplanation {
        if(!rule.enabled) return RuleExplanation(RuleReason.DISABLED,"Rule disabled; no episodes or alerts")
        if(!monitoring) return RuleExplanation(RuleReason.STOPPED,"Monitoring stopped; start explicitly to evaluate this rule")
        rule.unavailableReason(true)?.let { return RuleExplanation(RuleReason.UNAVAILABLE,it) }
        if(starting) return RuleExplanation(RuleReason.WAITING,"Monitoring starting; waiting for audio/model initialization")
        rule.unavailableReason(modelAvailable)?.let { return RuleExplanation(RuleReason.UNAVAILABLE,it) }
        if(update==null) return RuleExplanation(RuleReason.WAITING,"Waiting for the first evaluation")
        val audio=rule.condition as? Condition.Audio
        return when(update.phase) {
            Phase.IDLE -> if(update.ended!=null) RuleExplanation(RuleReason.WAITING,"Previous episode ended; waiting for the next qualifying evaluation") else RuleExplanation(RuleReason.SCORE,if(audio!=null)
                "Below activation threshold: smoothed ${score(update.smoothed)} / required ${score(audio.minScore)} (display rounded)"
                else "RMS ${update.rms}; waiting for strictly above ${(rule.condition as Condition.Rms).threshold}")
            Phase.CANDIDATE -> {
                val required=audio?.minDurationMs ?: 0L
                val progress=if(required==0L) 1f else (update.persistenceMs.toFloat()/required).coerceIn(0f,1f)
                if(update.belowEvidence) RuleExplanation(RuleReason.GAP,"Score dipped below activation; ${update.gapRemainingMs} ms tolerated gap remaining. Evidence must recover before activation.",progress)
                else RuleExplanation(RuleReason.PERSISTENCE,"Candidate ${update.persistenceMs} / $required ms; waiting for persistence",progress)
            }
            Phase.ACTIVE -> if(update.belowEvidence) RuleExplanation(RuleReason.GAP,"Episode remains active during a score dip; ${update.gapRemainingMs} ms tolerated gap remaining. No duplicate alert.") else RuleExplanation(RuleReason.ACTIVE,if((rule.condition as? Condition.Rms)?.legacyRepeat==true)
                "RMS episode active; legacy repeat policy may create another episode after cooldown"
                else "Episode active; no duplicate alert for this continuous sound")
            Phase.COOLDOWN -> RuleExplanation(RuleReason.COOLDOWN,"Cooldown: ${update.cooldownRemainingMs} ms remaining at last evaluation; returning sounds cannot start another episode yet")
        }
    }
    fun classes(rule: LocalRule): String = when(val c=rule.condition) {
        is Condition.Rms -> "RMS amplitude only; no YAMNet class"
        is Condition.Audio -> (c.classId?.let { setOf(it) } ?: requireNotNull(SoundCatalog.event(c.eventType)).classIds)
            .sorted().joinToString(" · ") { "${SoundCatalog.classLabels[it] ?: it} ($it)" }
    }
    fun coverage(classId: String, rules: List<LocalRule>): ClassCoverage {
        val events=SoundCatalog.events.filter { classId in it.classIds }
        if(events.isEmpty()) return ClassCoverage(Coverage.UNMAPPED,if(classId=="/m/0bt9lr") "Dog is a broad class. Dog barking rules use Bark (/m/05tny_), not Dog." else "No curated alert mapping for this class; Live classification alone does not trigger an alert.")
        val matching=rules.filter { rule -> (rule.condition as? Condition.Audio)?.let { c -> events.any { it.id==c.eventType } && (c.classId==null || c.classId==classId) } == true }
        if(matching.isEmpty()) return ClassCoverage(Coverage.NO_RULE,"No matching rule. Create ${events.joinToString { it.label }} in Rules; Live classifications alone do not alert.")
        if(matching.none { it.enabled }) return ClassCoverage(Coverage.DISABLED,"Matching rules are disabled; review them in Rules")
        if(matching.none { it.enabled && it.unavailableReason(true)==null }) return ClassCoverage(Coverage.UNAVAILABLE,"Matching enabled rules have unavailable pinned profiles; review rule details")
        return ClassCoverage(Coverage.ENABLED,"Enabled matching rule; its smoothed score, persistence and cooldown still apply")
    }
    fun notificationSetting(rule: LocalRule, master: Boolean, systemAllowed: Boolean): String = when {
        !rule.enabled -> "Rule disabled; no new episodes or alerts"
        !master -> "Master alerts off; episodes are still stored"
        !rule.policy.notify -> "Notify for this rule is off; episodes are still stored"
        !systemAllowed -> "Android notifications or the sound-event alert channel are blocked"
        else -> "Notification requests enabled; sound, banners and Do Not Disturb are controlled by Android"
    }
    fun notificationHistory(status: String): String = when(status) {
        "posted" -> "Request submitted to Android; this does not prove audible delivery"
        "permission_denied" -> "Blocked by Android notification permission/channel settings"
        "disabled" -> "Master alerts or rule notifications were off at episode start"
        else -> "Legacy/unknown delivery status"
    }
}

/** Explicit form shortcuts, not calibrated profiles or replacements for saved rules. */
data class RulePreset(val id: String, val label: String, val persistenceMs: Long) {
    fun apply(condition: Condition.Audio) = condition.copy(minScore=.7f,releaseScore=.5f,minDurationMs=persistenceMs,maxGapMs=1000,emaAlpha=.5f)
}
object RulePresets {
    val all=listOf(RulePreset("short-v1","Short sound · provisional",500),RulePreset("sustained-v1","Sustained sound · provisional",3000))
}

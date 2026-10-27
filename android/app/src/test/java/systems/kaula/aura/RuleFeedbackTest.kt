package systems.kaula.aura

import org.junit.Assert.*
import org.junit.Test

class RuleFeedbackTest {
    private fun rule() = LocalRule(id="bark",condition=Condition.Audio("dog_bark",minDurationMs=1000,maxGapMs=500,emaAlpha=1f),policy=Policy(3000))
    @Test fun topModelClassDoesNotImplyAnAlertRule() {
        assertEquals(Coverage.UNMAPPED,RuleFeedback.coverage("/m/0bt9lr",listOf(rule())).state)
        assertTrue(RuleFeedback.coverage("/m/0bt9lr",listOf(rule())).message.contains("Bark (/m/05tny_)"))
        assertEquals(Coverage.NO_RULE,RuleFeedback.coverage("/m/02jz0l",listOf(rule())).state)
        assertEquals(Coverage.ENABLED,RuleFeedback.coverage("/m/05tny_",listOf(rule())).state)
        assertEquals(Coverage.DISABLED,RuleFeedback.coverage("/m/05tny_",listOf(rule().copy(enabled=false))).state)
        val unsupported=rule().copy(condition=Condition.Audio("dog_bark",modelVersion="older-pinned-model"))
        assertEquals(Coverage.UNAVAILABLE,RuleFeedback.coverage("/m/05tny_",listOf(unsupported)).state)
    }
    @Test fun explicitClassFilterMustMatchTheScoredClass() {
        val alarm=rule().copy(condition=Condition.Audio("alarm_siren",classId="/m/07pp_mv"))
        assertEquals(Coverage.ENABLED,RuleFeedback.coverage("/m/07pp_mv",listOf(alarm)).state)
        assertEquals(Coverage.NO_RULE,RuleFeedback.coverage("/m/03kmc9",listOf(alarm)).state)
        assertTrue(RuleFeedback.classes(alarm).contains("Alarm (/m/07pp_mv)"))
        assertFalse(RuleFeedback.classes(alarm).contains("Siren"))
        assertEquals(SoundCatalog.events.flatMap { it.classIds }.toSet(),SoundCatalog.classLabels.keys)
    }
    @Test fun actualDetectorSnapshotsExplainPersistenceGapsAndCooldown() {
        val r=rule();val d=TemporalDetector(r)
        fun feedback(u: DetectorUpdate)=RuleFeedback.explain(r,u,true,true)
        assertEquals(RuleReason.SCORE,feedback(d.update(.2f,0,10000)).reason)
        assertEquals(RuleReason.PERSISTENCE,feedback(d.update(.8f,500,10500)).reason)
        val halfway=feedback(d.update(.8f,1000,11000))
        assertEquals(.5f,halfway.progress!!,0f)
        val active=d.update(.8f,1500,11500)
        assertNotNull(active.started);assertEquals(RuleReason.ACTIVE,feedback(active).reason)
        val dip=d.update(.1f,1800,11800)
        assertTrue(dip.belowEvidence);assertEquals(200L,dip.gapRemainingMs)
        assertEquals(RuleReason.GAP,feedback(dip).reason)
        val end=d.update(.1f,2100,12100)
        assertNotNull(end.ended);assertEquals(2400L,end.cooldownRemainingMs)
        assertEquals(RuleReason.COOLDOWN,feedback(end).reason)
        val returned=d.update(.9f,2500,12500)
        assertNull(returned.started);assertEquals(2000L,returned.cooldownRemainingMs)
        assertEquals(RuleReason.COOLDOWN,feedback(returned).reason)
        assertEquals(RuleReason.PERSISTENCE,feedback(d.update(.9f,4500,14500)).reason)
    }
    @Test fun candidateDipsAndClockResetsCannotShowStaleProgress() {
        val r=rule();val d=TemporalDetector(r)
        d.update(.8f,1000,10000)
        val dip=d.update(.1f,1250,10250)
        assertEquals(RuleReason.GAP,RuleFeedback.explain(r,dip,true,true).reason)
        assertEquals(250L,dip.gapRemainingMs)
        val reset=d.update(.1f,10,1)
        assertEquals(0L,reset.persistenceMs);assertEquals(0L,reset.cooldownRemainingMs)
        assertEquals(0L,reset.gapRemainingMs);assertFalse(reset.belowEvidence)
        assertEquals(RuleReason.SCORE,RuleFeedback.explain(r,reset,true,true).reason)
    }
    @Test fun disabledStoppedUnavailableAndWaitingRemainDistinct() {
        val r=rule()
        assertEquals(RuleReason.DISABLED,RuleFeedback.explain(r.copy(enabled=false),null,true,true).reason)
        assertEquals(RuleReason.STOPPED,RuleFeedback.explain(r,null,false,false).reason)
        assertEquals(RuleReason.UNAVAILABLE,RuleFeedback.explain(r,null,true,false).reason)
        assertEquals(RuleReason.WAITING,RuleFeedback.explain(r,null,true,true).reason)
        assertEquals(RuleReason.WAITING,RuleFeedback.explain(r,null,true,false,starting=true).reason)
        val rms=r.copy(condition=Condition.Rms(.15,true))
        assertEquals(RuleReason.WAITING,RuleFeedback.explain(rms,null,true,false).reason)
        val d=TemporalDetector(rms)
        val exact=d.update(.15f,0,0,rms=.150000001)
        assertNotNull(exact.started);assertEquals(.150000001,exact.rms,0.0)
        assertTrue(RuleFeedback.explain(rms,exact,true,false).message.contains("legacy repeat"))
    }
    @Test fun notificationSettingsAreSeparateFromDetectionAndNotDeliveryProof() {
        val r=rule()
        assertTrue(RuleFeedback.notificationSetting(r,false,true).contains("Master alerts off"))
        assertTrue(RuleFeedback.notificationSetting(r.copy(policy=Policy(notify=false)),true,true).contains("this rule is off"))
        assertTrue(RuleFeedback.notificationSetting(r,true,false).contains("blocked"))
        assertTrue(RuleFeedback.notificationSetting(r,true,true).contains("Do Not Disturb"))
        assertTrue(RuleFeedback.notificationHistory("posted").contains("does not prove"))
        assertTrue(RuleFeedback.notificationHistory("permission_denied").contains("Blocked"))
    }
    @Test fun presetsAreExplicitAndPreservePinnedMeaning() {
        val original=Condition.Audio("alarm_siren",.9f,.6f,900,50,.2f,"pinned-model","pinned-map","pinned-threshold","/m/03kmc9")
        RulePresets.all.forEach { preset ->
            val changed=preset.apply(original)
            LocalRule(id="preset",condition=changed).validate()
            assertEquals(original.eventType,changed.eventType);assertEquals(original.classId,changed.classId)
            assertEquals(original.modelVersion,changed.modelVersion);assertEquals(original.mappingVersion,changed.mappingVersion)
            assertEquals(original.thresholdVersion,changed.thresholdVersion);assertEquals(preset.persistenceMs,changed.minDurationMs)
        }
        assertEquals(900L,original.minDurationMs);assertEquals(.9f,original.minScore,0f)
        assertEquals(listOf(500L,3000L),RulePresets.all.map { it.persistenceMs })
    }
}

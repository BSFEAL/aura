package systems.kaula.aura

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    private val rule=LocalRule(id="rule",source=Source("old-phone"),condition=Condition.Audio("dog_bark",modelVersion="pinned-older-model"),remote=true)
    private val event=LocalEvent(id="event",ruleId="rule",deviceId="old-phone",eventType="dog_bark",classId="/m/05tny_",label="Possible barking",startMs=1000,endMs=2000,durationMs=1000,
        peakScore=.9f,meanScore=.8f,modelVersion=ModelSpec.VERSION,mappingVersion=ModelSpec.MAPPING,thresholdVersion=ModelSpec.THRESHOLDS,provenance="model_detected",notificationStatus="posted")
    private fun backup(rules: List<LocalRule> = listOf(rule), events: List<LocalEvent> = listOf(event)) = LocalBackup(createdAtMs=3000,deviceId="old-phone",rules=rules,events=events)
    private fun read(text: String) = BackupCodec.read(ByteArrayInputStream(text.toByteArray()))
    @Test fun roundTripPreservesEvidenceAndVersionPins() {
        val original=backup()
        val bytes=BackupCodec.encode(original)
        assertEquals(original,BackupCodec.read(ByteArrayInputStream(bytes)))
        val plan=BackupCodec.merge(original,emptyList(),emptyList(),"new-phone")
        assertEquals(1,plan.addedRules);assertEquals(1,plan.addedEvents)
        assertFalse(plan.rules.single().enabled);assertFalse(plan.rules.single().remote)
        assertEquals("new-phone",plan.rules.single().source.deviceId)
        assertEquals("pinned-older-model",(plan.rules.single().condition as Condition.Audio).modelVersion)
        assertNotNull(plan.rules.single().unavailableReason(true))
        assertEquals(event.copy(restoredFromBackup=true),plan.events.single())
        assertEquals("old-phone",plan.events.single().deviceId)
    }
    @Test fun mergeIsIdempotentAndKeepsExistingEnabledRules() {
        val existing=rule.copy(remote=false,source=Source("new-phone"))
        val plan=BackupCodec.merge(backup(),listOf(existing),listOf(event),"new-phone")
        assertEquals(0,plan.addedRules);assertEquals(0,plan.addedEvents)
        assertEquals(existing,plan.rules.single());assertTrue(plan.rules.single().enabled)
        val restored=BackupCodec.merge(backup(),emptyList(),emptyList(),"new-phone")
        assertEquals(0,BackupCodec.merge(backup(),restored.rules,restored.events,"new-phone").addedEvents)
    }
    @Test fun conflictsAndCapacityNeverReplaceExistingData() {
        val oldRules=listOf(rule.copy(policy=Policy(123)))
        val oldEvents=listOf(event)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.merge(backup(),oldRules,oldEvents,"new-phone") }
        assertEquals(123L,oldRules.single().policy.cooldownMs)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.merge(backup(),emptyList(),listOf(event.copy(label="different")),"new-phone") }
        val full=(1..200).map { event.copy(id="existing-$it") }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.merge(backup(),emptyList(),full,"new-phone") }
        assertEquals(200,full.size)
    }
    @Test fun interruptedHistoryDoesNotBecomeLiveEpisode() {
        val interrupted=event.copy(endMs=null,state="active")
        val restored=BackupCodec.merge(backup(events=listOf(interrupted)),emptyList(),emptyList(),"new-phone").events.single()
        assertEquals(2000L,restored.endMs);assertEquals("restored_interrupted",restored.state)
        assertTrue(restored.restoredFromBackup)
        assertEquals(interrupted.notificationStatus,restored.notificationStatus) // historical status, never replayed
    }
    @Test fun malformedOversizedDeepAndUnknownInputsAreRejected() {
        val json=String(BackupCodec.encode(backup()))
        assertThrows(IllegalArgumentException::class.java) { read(json.replace("\"format_version\":1","\"format_version\":2")) }
        assertThrows(IllegalArgumentException::class.java) { read(json.dropLast(1)+",\"api_token\":\"must-not-import\"}") }
        assertThrows(IllegalArgumentException::class.java) { read("[".repeat(33)+"]".repeat(33)) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.read(ByteArrayInputStream(ByteArray(BackupCodec.MAX_BYTES+1))) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { BackupCodec.read(ByteArrayInputStream(byteArrayOf(-1))) }
        assertThrows(IllegalArgumentException::class.java) { read(json.dropLast(2)) }
        val escaped=backup(rules=listOf(rule.copy(text="Quotes \" and brackets [[{{\\ are text")))
        assertEquals(escaped,BackupCodec.read(ByteArrayInputStream(BackupCodec.encode(escaped))))
    }
    @Test fun invalidScoresProvenanceTimesAndDuplicateIdsAreRejected() {
        listOf(event.copy(peakScore=Float.NaN),event.copy(meanScore=.95f),event.copy(classId="wrong"),
            event.copy(durationMs=999),event.copy(startMs=-1),event.copy(endMs=Long.MAX_VALUE),
            event.copy(provenance="verified"),event.copy(rms=Double.NaN),event.copy(notificationStatus="replay"),
            event.copy(id="bad\nidentifier"),event.copy(schemaVersion=3)).forEach {
            assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(backup(events=listOf(it))) }
        }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(backup(rules=listOf(rule,rule))) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(backup(events=listOf(event,event))) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(backup(events=(1..201).map { event.copy(id="$it") })) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(backup(rules=(1..129).map { rule.copy(id="$it") })) }
    }
    @Test fun legacyRmsRulesAndHistoryRemainPortable() {
        val legacy=RuleCodec.decode("""{"id":"old","text":"RMS","threshold":0.15,"enabled":true,"remote":false}""")
        val history=EventCodec.decode("""{"id":"rms","ruleId":"old","rms":0.2,"time":1000}""")
        val plan=BackupCodec.merge(backup(listOf(legacy),listOf(history)),emptyList(),emptyList(),"new-phone")
        assertTrue((plan.rules.single().condition as Condition.Rms).legacyRepeat)
        assertEquals("legacy_unknown",plan.events.single().notificationStatus)
        assertEquals("device_reported",plan.events.single().provenance)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.validate(backup(events=listOf(history.copy(provenance="model_detected")))) }
    }
}

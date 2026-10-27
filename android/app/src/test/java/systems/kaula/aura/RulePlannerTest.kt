package systems.kaula.aura

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RulePlannerTest {
    private val request="Notify me when my dog barks for 2 seconds"
    private fun fixture() = requireNotNull(javaClass.classLoader?.getResourceAsStream("rule_plan_ready.json")).bufferedReader().use { it.readText() }
    @Test fun validatedBackendProtocolProducesOnlyDisabledTypedDraft() {
        val draft=(RulePlanCodec.decode(fixture(),request,"phone") as RulePlan.Ready).draft
        assertEquals("gpt-4.1-mini-2025-04-14",draft.plannerModel)
        assertEquals(2000L,(draft.condition as Condition.Audio).minDurationMs)
        assertEquals("dog_bark",(draft.condition as Condition.Audio).eventType)
        val saved=draft.asDisabledRule("new-id","phone")
        assertFalse(saved.enabled);assertFalse(saved.remote);assertEquals(request,saved.text)
        assertEquals("phone",saved.source.deviceId);assertEquals(saved,RuleCodec.decode(RuleCodec.encode(saved)))
    }
    @Test fun unsafeOrMismatchedDraftsAreRejectedBeforeSave() {
        val json=fixture()
        listOf(json.replace("\"enabled\":false","\"enabled\":true"),
            json.replace("\"device_id\":\"phone\"","\"device_id\":\"other\""),
            json.replace("\"min_score\":0.7","\"min_score\":1.1"),json.replace("\"min_score\":0.7","\"min_score\":0.2"),
            json.replace("\"label\":\"Bark\"","\"label\":\"Dog\""),json.replace("/m/05tny_","/m/0bt9lr"),
            json.replace("yamnet-tflite-1-10c95ea3","unsupported-model"),json.replace("\"mode\":\"openai\"","\"mode\":\"fake\""),
            json.replace("\"schema_version\":2","\"schema_version\":3"),json.trim().dropLast(1)+",\"activate_microphone\":true}").forEach {
            assertThrows(IllegalArgumentException::class.java) { RulePlanCodec.decode(it,request,"phone") }
        }
        assertThrows(IllegalArgumentException::class.java) { RulePlanCodec.decode(json,"changed request","phone") }
    }
    @Test fun clarificationIsNotASavableRule() {
        val json="""{"schema_version":2,"status":"clarification","mode":"openai","planner_version":"aura-openai-rule-planner-v1","planner_model":"gpt-4.1-mini-2025-04-14","message":"Do you mean Bark?"}"""
        assertEquals("Do you mean Bark?",(RulePlanCodec.decode(json,request,"phone") as RulePlan.Clarify).message)
    }
    @Test fun parserBoundsAndHttpsConsentAreEnforcedWithoutNetwork() {
        assertThrows(IllegalArgumentException::class.java) { RulePlanCodec.decode("x".repeat(RulePlanCodec.MAX_BYTES+1),request,"phone") }
        assertThrows(IllegalArgumentException::class.java) { RulePlanCodec.decode("[".repeat(33)+"]".repeat(33),request,"phone") }
        assertThrows(IllegalArgumentException::class.java) { Api.previewRule("https://private.invalid","a".repeat(24),request,"phone",false) }
        assertThrows(IllegalArgumentException::class.java) { Api.previewRule("http://127.0.0.1:8000","a".repeat(24),request,"phone",true) }
        assertThrows(IllegalArgumentException::class.java) { Api.previewRule("https://user:secret@private.invalid","a".repeat(24),request,"phone",true) }
    }
    @Test fun fakePlannerErrorsNeverFallBackToInventedRules() = runBlocking {
        val fake=RulePlanner { throw java.io.IOException("Synthetic provider failure") }
        try { fake.propose(request);fail("Expected failure without fallback") } catch(_: java.io.IOException) {}
        val clarify=RulePlanner { RulePlan.Clarify("Choose one sound") }
        assertTrue(clarify.propose(request) is RulePlan.Clarify)
    }
}

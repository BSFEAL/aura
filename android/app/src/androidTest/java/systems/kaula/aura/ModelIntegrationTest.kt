package systems.kaula.aura

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelIntegrationTest {
    @Test fun actualInterpreterBarkFixtureToTypedEpisode()=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val classifier=YamnetClassifier(instrumentation.targetContext)
        try {
            val samples=instrumentation.context.assets.open("dog_bark_16k.pcm").use { Pcm.decode(it.readBytes()) }
            val result=classifier.classify(samples)
            assertEquals(521,result.scores.size)
            assertEquals("/m/05tny_",result.scores[70].classId)
            assertEquals("Bark",result.scores[70].label)
            assertTrue(result.scores.all { it.score.isFinite() && it.score in 0f..1f })
            val raw=SoundCatalog.score("dog_bark",result.scores)
            assertTrue("Fixture plumbing expectation, not accuracy",raw>.65f)
            val rule=LocalRule(id="inference-fixture",condition=Condition.Audio("dog_bark",.65f,.4f,0,500,1f))
            val detector=TemporalDetector(rule)
            val event=detector.update(raw,0,10000).started!!
            assertEquals("dog_bark",event.eventType);assertEquals(ModelSpec.VERSION,event.modelVersion)
            assertTrue(event.peak.isFinite());assertNull(detector.update(raw,500,10500).started)
            val typed=requireNotNull(detector.reset(500)).toLocalEvent(rule,"fixture-phone","disabled")
            Store.saveEvent(instrumentation.targetContext,typed)
            assertTrue(Store.events(instrumentation.targetContext).any { it.id==typed.id && it.provenance=="model_detected" })
            assertEquals(typed,EventCodec.decode(EventCodec.encode(typed)))
            println("AURA_REAL_INFERENCE model=${result.modelVersion} latency_ms=${result.inferenceMs} bark_raw=$raw")
        } finally { classifier.close() }
    }
    @Test fun upgradeMigrationPreservesV02RulesAndHistory() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("aura",0)
        prefs.edit().clear().putString("rules","""[{"id":"old","text":"RMS","threshold":0.15,"enabled":true,"remote":false}]""")
            .putString("events","""[{"id":"event","ruleId":"old","rms":0.2,"time":1000}]""").commit()
        val rules=Store.rules(context)
        assertEquals("old",rules.single().id);assertTrue((rules.single().condition as Condition.Rms).legacyRepeat)
        assertEquals("event",Store.events(context).single().id)
        Store.saveEvent(context,LocalEvent(id="new",ruleId="old",startMs=2000))
        assertEquals(2,Store.events(context).size)
        assertNotNull(prefs.getString("rules_v02_backup",null))
        prefs.edit().clear().commit()
    }
}

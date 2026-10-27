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
        val initStart=android.os.SystemClock.elapsedRealtime()
        val classifier=YamnetClassifier(instrumentation.targetContext)
        val initMs=android.os.SystemClock.elapsedRealtime()-initStart
        try {
            val samples=instrumentation.context.assets.open("dog_bark_16k.pcm").use { Pcm.decode(it.readBytes()) }
            val result=classifier.classify(samples)
            val metrics=InferenceStats().apply { add(result.inferenceMs) }
            repeat(11) { metrics.add(classifier.classify(samples).inferenceMs) }
            assertEquals(521,result.scores.size)
            val catalog=instrumentation.targetContext.assets.open("model_manifest.json").bufferedReader().use { org.json.JSONObject(it.readText()).getJSONObject("events") }
            SoundCatalog.events.forEach { eventType ->
                val entries=catalog.getJSONArray(eventType.id)
                val expected=(0 until entries.length()).map { entries.getJSONObject(it).getString("class_id") }.toSet()
                assertEquals(expected,eventType.classIds)
                repeat(entries.length()) { index ->
                    val entry=entries.getJSONObject(index)
                    val score=result.scores[entry.getInt("index")]
                    assertEquals(entry.getString("class_id"),score.classId)
                    assertEquals(entry.getString("label"),score.label)
                    assertEquals(score.label,SoundCatalog.classLabels[score.classId])
                }
            }
            assertEquals("/m/05tny_",result.scores[70].classId)
            assertEquals("Bark",result.scores[70].label)
            assertTrue(result.scores.all { it.score.isFinite() && it.score in 0f..1f })
            val raw=SoundCatalog.score("dog_bark",result.scores)
            val rms=kotlin.math.sqrt(samples.sumOf { it.toDouble()*it }/samples.size)
            assertTrue("Fixture plumbing expectation, not accuracy",raw>.65f)
            val rule=LocalRule(id="inference-fixture",condition=Condition.Audio("dog_bark",.65f,.4f,0,500,1f))
            val detector=TemporalDetector(rule)
            val event=detector.update(raw,0,10000,rms).started!!
            assertEquals("dog_bark",event.eventType);assertEquals(ModelSpec.VERSION,event.modelVersion)
            assertTrue(event.peak.isFinite());assertNull(detector.update(raw,500,10500,rms).started)
            val typed=requireNotNull(detector.reset(500)).toLocalEvent(rule,"fixture-phone","disabled")
            Store.saveEvent(instrumentation.targetContext,typed)
            assertTrue(Store.events(instrumentation.targetContext).any { it.id==typed.id && it.provenance=="model_detected" })
            assertEquals(typed,EventCodec.decode(EventCodec.encode(typed)))
            println("AURA_REAL_INFERENCE model=${result.modelVersion} latency_ms=${result.inferenceMs} bark_raw=$raw")
            println("AURA_MODEL_METRICS "+org.json.JSONObject().put("platform","Android API ${android.os.Build.VERSION.SDK_INT} ${android.os.Build.SUPPORTED_ABIS.joinToString()} ${android.os.Build.MODEL}")
                .put("measurement_kind","instrumentation fixture smoke, not acoustic calibration or battery measurement")
                .put("model_version",result.modelVersion).put("init_ms",initMs).put("windows",metrics.count)
                .put("average_inference_ms",metrics.averageMs).put("p95_inference_ms",metrics.p95Ms)
                .put("process_pss_kib",android.os.Debug.getPss()).put("calibrated",false).toString())
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

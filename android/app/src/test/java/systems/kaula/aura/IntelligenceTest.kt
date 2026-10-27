package systems.kaula.aura

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class IntelligenceTest {
    private fun rule(duration: Long=1000,gap: Long=500,cooldown: Long=3000,alpha: Float=1f)=LocalRule(id="test",condition=Condition.Audio("dog_bark",.7f,.4f,duration,gap,alpha),policy=Policy(cooldown))
    @Test fun pcmAndOverlappingWindowsAreCorrectAndBounded() {
        assertEquals(-1f,Pcm.normalize(Short.MIN_VALUE),0f)
        assertEquals(32767/32768f,Pcm.normalize(Short.MAX_VALUE),0f)
        assertArrayEquals(floatArrayOf(-1f,0f),Pcm.decode(byteArrayOf(0,-128,0,0)),0f)
        val buffer=AudioWindowBuffer(500)
        val ready=mutableListOf<FloatArray>()
        repeat(20) { ready+=buffer.append(ShortArray(1000) { (it+1000).toShort() }) }
        assertEquals(1,ready.size);assertEquals(15600,ready[0].size)
        repeat(4) { ready+=buffer.append(ShortArray(1000) { 3000 }) }
        assertEquals(2,ready.size);assertEquals(3000/32768f,ready.last().last(),0f)
        buffer.clear();assertTrue(buffer.append(ShortArray(1000)).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { AudioWindowBuffer(50) }
        assertThrows(IllegalArgumentException::class.java) { AudioWindowBuffer(sampleRate=44100) }
        assertThrows(IllegalArgumentException::class.java) { buffer.append(ShortArray(5000)) }
    }
    @Test fun tensorAndCatalogValidation() {
        ModelSpec.validate(16000,intArrayOf(15600),intArrayOf(1,521))
        assertThrows(IllegalArgumentException::class.java) { ModelSpec.validate(44100,intArrayOf(15600),intArrayOf(1,521)) }
        assertThrows(IllegalArgumentException::class.java) { ModelSpec.validate(16000,intArrayOf(1,15600),intArrayOf(1,521)) }
        assertThrows(IllegalArgumentException::class.java) { ModelSpec.validate(16000,intArrayOf(15600),intArrayOf(1,527)) }
        assertEquals(.8f,SoundCatalog.score("dog_bark",listOf(ClassScore("/m/05tny_","Bark",.8f),ClassScore("speech","Speech",.99f))),0f)
        assertEquals(.9f,SoundCatalog.score("alarm_siren",listOf(ClassScore("/m/07pp_mv","Alarm",.8f),ClassScore("/m/03kmc9","Siren",.9f))),0f)
        assertEquals(.8f,SoundCatalog.score("alarm_siren",listOf(ClassScore("/m/07pp_mv","Alarm",.8f),ClassScore("/m/03kmc9","Siren",.9f)),"/m/07pp_mv"),0f)
        assertThrows(IllegalArgumentException::class.java) { SoundCatalog.score("rain",emptyList(),"/m/05tny_") }
        assertNull(SoundCatalog.event("door_open"))
        assertThrows(IllegalArgumentException::class.java) { SoundCatalog.score("rain",listOf(ClassScore("x","x",Float.NaN))) }
    }
    @Test fun candidatesCancelAndPersistenceActivates() {
        val d=TemporalDetector(rule()) { "episode" }
        assertEquals(Phase.CANDIDATE,d.update(.8f,0,10000).phase)
        assertNull(d.update(.8f,500,10500).started)
        val start=d.update(.8f,1000,11000).started!!
        assertEquals("episode",start.id);assertEquals(10000,start.startMs)
        assertNull(d.update(.9f,1500,11500).started)
        val cancelled=TemporalDetector(rule())
        cancelled.update(.8f,0,0);assertEquals(Phase.IDLE,cancelled.update(0f,501,501).phase)
    }
    @Test fun hysteresisGapsCooldownAndDuplicates() {
        val d=TemporalDetector(rule())
        d.update(.8f,0,10000);d.update(.8f,1000,11000)
        assertEquals(Phase.ACTIVE,d.update(.5f,1500,11500).phase)
        assertEquals(Phase.ACTIVE,d.update(.1f,1900,11900).phase)
        assertEquals(Phase.ACTIVE,d.update(.8f,2000,12000).phase)
        val end=d.update(.1f,2600,12600).ended!!
        assertEquals(12000L,end.endMs);assertEquals(2000,end.durationMs)
        assertEquals(Phase.COOLDOWN,d.update(.9f,3000,13000).phase)
        assertEquals(Phase.CANDIDATE,d.update(.9f,4000,14000).phase)
        assertNotNull(d.update(.9f,5000,15000).started)
        repeat(20) { assertNull(d.update(.9f,5500+it*500L,15500+it*500L).started) }
    }
    @Test fun smootherAndClockReset() {
        val d=TemporalDetector(rule(alpha=.5f))
        assertEquals(.8f,d.update(.8f,0,10000).smoothed,0f)
        assertEquals(.4f,d.update(0f,500,10500).smoothed,.0001f)
        val e=TemporalDetector(rule(duration=0))
        e.update(.9f,1000,20000)
        val reset=e.update(.1f,10,1000)
        assertEquals("clock_or_capture_gap",reset.ended!!.state)
        assertEquals(.1f,reset.smoothed,0f)
        val gap=TemporalDetector(rule(duration=0));gap.update(.9f,0,0)
        assertNotNull(gap.update(0f,11000,11000).ended)
        val clock=TemporalDetector(rule(duration=0));clock.update(.9f,0,5000);clock.update(.8f,100,1)
        assertEquals(5100L,clock.reset(100)!!.endMs)
    }
    @Test fun rulesMigrateSerializeValidateAndPinMeaning() {
        val old="""{"id":"legacy","text":"RMS","threshold":0.15,"enabled":true,"remote":false}"""
        val migrated=RuleCodec.decode(old)
        assertTrue((migrated.condition as Condition.Rms).legacyRepeat)
        assertEquals(migrated,RuleCodec.decode(RuleCodec.encode(migrated)))
        assertEquals(rule(),RuleCodec.decode(RuleCodec.encode(rule())))
        assertThrows(IllegalArgumentException::class.java) { rule().copy(condition=Condition.Audio("dog_bark",.2f,.5f)).validate() }
        assertThrows(IllegalArgumentException::class.java) { rule().copy(condition=Condition.Audio("unsupported")).validate() }
        assertThrows(IllegalArgumentException::class.java) { rule().copy(schemaVersion=3).validate() }
        assertNotNull(rule().copy(condition=Condition.Audio("rain",modelVersion="future")).unavailableReason(true))
        val event=EventCodec.decode("""{"id":"old","ruleId":"legacy","rms":0.2,"time":1000}""")
        assertEquals("old",event.id);assertEquals(1000,event.startMs);assertEquals("device_reported",event.provenance)
        assertEquals(event,EventCodec.decode(EventCodec.encode(event)))
    }
    @Test fun legacyRmsAndStateCleanup() {
        val rule=LocalRule(id="legacy",condition=Condition.Rms(.15,true))
        val d=TemporalDetector(rule)
        assertNull(d.update(.15f,0,0).started)
        assertNotNull(d.update(.3f,100,100).started)
        var now=100L
        while(now<30100) { now+=1000;d.update(.3f,now,now) }
        assertNotNull(d.update(.3f,30200,30200).started)
        val session=SoundSession(null)
        session.evaluate(rule,.3f,0,0,.3)
        assertEquals(1,session.trackedRules)
        assertEquals(1,session.prune(emptyList(),10).size)
        assertEquals(0,session.trackedRules)
    }
    @Test fun fakeClassifierErrorsDegradeWithoutFabricatedScores()=runBlocking {
        var closed=false
        val fake=object: AudioEventClassifier {
            override suspend fun classify(samples: FloatArray): ClassificationResult=error("test native failure")
            override fun close() { closed=true }
        }
        val session=SoundSession(fake)
        assertNull(session.classify(FloatArray(15600)))
        assertEquals(1,session.failures);assertNotNull(session.unavailable)
        assertNull(session.classify(FloatArray(15600)));assertEquals(1,session.failures)
        session.close();assertTrue(closed)
    }
    @Test fun returningAfterExpiredLowGapCannotBridgeAnEpisode() {
        val candidate=TemporalDetector(rule(duration=1000))
        candidate.update(.9f,0,0);candidate.update(.1f,200,200)
        assertNull(candidate.update(.9f,800,800).started)
        assertEquals(Phase.CANDIDATE,candidate.phase)
        val active=TemporalDetector(rule(duration=0))
        active.update(.9f,0,10000);active.update(.1f,200,10200)
        val returned=active.update(.9f,800,10800)
        assertNotNull(returned.ended);assertNull(returned.started)
        assertEquals(10000L,returned.ended!!.endMs)
    }
    @Test fun invalidClassifierTensorsAndStatisticsAreHandled()=runBlocking {
        val fake=object: AudioEventClassifier {
            override suspend fun classify(samples: FloatArray)=ClassificationResult(listOf(ClassScore("bad","bad",Float.NaN)),0,ModelSpec.VERSION)
            override fun close() {}
        }
        val session=SoundSession(fake)
        assertNull(session.classify(FloatArray(15600)));assertEquals(1L,session.failures)
        val stats=InferenceStats()
        repeat(200) { stats.add(it.toLong()) }
        assertEquals(200L,stats.count);assertEquals(99.5,stats.averageMs,0.0);assertEquals(193L,stats.p95Ms)
    }

}

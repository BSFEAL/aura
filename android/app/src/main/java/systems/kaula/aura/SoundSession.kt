package systems.kaula.aura

/** Pure decision coordinator. No Android objects or IO. Fake classifiers exercise the same error path. */
class SoundSession(private val classifier: AudioEventClassifier?) : AutoCloseable {
    private val detectors=mutableMapOf<String,Pair<LocalRule,TemporalDetector>>()
    var failures=0L; private set
    var unavailable: String?=if(classifier==null) "Model unavailable" else null; private set
    val diagnostics=InferenceStats()
    suspend fun classify(samples: FloatArray): ClassificationResult? {
        if(unavailable!=null) return null
        return try {
            requireNotNull(classifier).classify(samples).also {
                require(it.modelVersion==ModelSpec.VERSION && it.scores.size==ModelSpec.CLASSES)
                require(it.scores.map { s->s.classId }.toSet().size==ModelSpec.CLASSES)
                require(it.scores.all { s->s.score.isFinite() && s.score in 0f..1f })
                diagnostics.add(it.inferenceMs)
            }
        } catch(e: kotlinx.coroutines.CancellationException) { throw e }
        catch(e: Exception) { failures++; unavailable="Classifier failed: ${e.message}"; null }
    }
    fun evaluate(rule: LocalRule, raw: Float, mono: Long, wall: Long, rms: Double,classId: String?=null): DetectorUpdate {
        rule.validate()
        val pair=detectors[rule.id]
        require(pair==null || pair.first==rule) { "Prune changed rules before evaluation" }
        val detector=pair?.second ?: TemporalDetector(rule).also { detectors[rule.id]=rule to it }
        return detector.update(raw,mono,wall,rms,classId)
    }
    fun prune(rules: List<LocalRule>,mono: Long, audioOnly: Boolean=false): List<Episode> {
        val valid=rules.filter { it.enabled }.associateBy { it.id }
        val removed=detectors.filter { (id,pair)-> valid[id]!=pair.first || (audioOnly && pair.first.condition is Condition.Audio) }.keys
        return removed.mapNotNull { detectors.remove(it)?.second?.reset(mono,"rule_changed_or_unavailable") }
    }
    fun finish(mono: Long): List<Episode> = detectors.values.mapNotNull { it.second.reset(mono) }.also { detectors.clear() }
    val trackedRules: Int get()=detectors.size
    override fun close() { classifier?.close() }
}

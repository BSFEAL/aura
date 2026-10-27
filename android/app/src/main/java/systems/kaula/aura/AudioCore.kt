package systems.kaula.aura

import kotlin.math.ceil

interface AudioEventClassifier : AutoCloseable {
    suspend fun classify(samples: FloatArray): ClassificationResult
    override fun close()
}
data class ClassScore(val classId: String, val label: String, val score: Float)
data class ClassificationResult(val scores: List<ClassScore>, val inferenceMs: Long, val modelVersion: String)

object ModelSpec {
    const val VERSION = "yamnet-tflite-1-10c95ea3"
    const val MAPPING = "aura-audioset-v1"
    const val THRESHOLDS = "provisional-v1"
    const val SAMPLE_RATE = 16000
    const val WINDOW = 15600
    const val CLASSES = 521
    const val SHA256 = "10c95ea3eb9a7bb4cb8bddf6feb023250381008177ac162ce169694d05c317de"
    fun validate(rate: Int, input: IntArray, output: IntArray) {
        require(rate == SAMPLE_RATE) { "Expected 16 kHz mono audio" }
        require(input.contentEquals(intArrayOf(WINDOW))) { "Invalid YAMNet input tensor" }
        require(output.contentEquals(intArrayOf(1, CLASSES))) { "Invalid YAMNet output tensor" }
    }
}

object Pcm {
    fun normalize(sample: Short): Float = sample.toInt() / 32768f
    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % 2 == 0)
        return FloatArray(bytes.size / 2) { i -> ((bytes[i*2].toInt() and 255) or (bytes[i*2+1].toInt() shl 8)).toShort().let(::normalize) }
    }
}

/** Exactly one audio window in memory; cadence is measured in captured samples, not wall time. */
class AudioWindowBuffer(val intervalMs: Int = 500, sampleRate: Int = ModelSpec.SAMPLE_RATE) {
    init { require(sampleRate == ModelSpec.SAMPLE_RATE); require(intervalMs in 250..2000) }
    private val buffer = FloatArray(ModelSpec.WINDOW)
    private var cursor = 0
    private var total = 0L
    private var nextWindow = ModelSpec.WINDOW.toLong()
    private val hop = intervalMs * ModelSpec.SAMPLE_RATE / 1000
    fun append(samples: ShortArray, count: Int = samples.size): List<FloatArray> {
        require(count in 0..samples.size && count <= 4096) { "Capture blocks must be bounded" }
        val ready = mutableListOf<FloatArray>()
        for (i in 0 until count) {
            buffer[cursor] = Pcm.normalize(samples[i]); cursor = (cursor+1) % buffer.size; total++
            if (total >= nextWindow) {
                ready += FloatArray(buffer.size) { buffer[(cursor+it) % buffer.size] }
                nextWindow += hop
            }
        }
        return ready
    }
    fun clear() { buffer.fill(0f); cursor=0; total=0; nextWindow=ModelSpec.WINDOW.toLong() }
}

data class EventClass(val id: String, val label: String, val classIds: Set<String>)
object SoundCatalog {
    val events = listOf(
        EventClass("dog_bark", "Dog barking", setOf("/m/05tny_")),
        EventClass("alarm_siren", "Alarm or siren", setOf("/m/07pp_mv", "/m/03kmc9")),
        EventClass("rain", "Rain", setOf("/m/06mb1", "/m/07r10fb", "/t/dd00038")),
        EventClass("running_water", "Running water / faucet", setOf("/m/02jz0l")),
        EventClass("knocking", "Knocking", setOf("/m/07r4wb8")),
        EventClass("doorbell", "Doorbell sound", setOf("/m/03wwcy"))
    )
    fun event(id: String) = events.firstOrNull { it.id == id }
    fun score(id: String, scores: List<ClassScore>, classId: String?=null): Float {
        val event = requireNotNull(event(id)) { "Unsupported sound event" }
        require(classId==null || classId in event.classIds)
        require(scores.all { it.score.isFinite() && it.score in 0f..1f }) { "Invalid model score" }
        return scores.filter { it.classId in event.classIds && (classId==null || it.classId==classId) }.maxOfOrNull { it.score } ?: 0f
    }
}

/** Bounded tail (128 windows) and session aggregates; timings measured around Interpreter.run. */
class InferenceStats {
    private val tail = ArrayDeque<Long>()
    var count=0L; private set
    private var sum=0L
    fun add(ms: Long) { require(ms>=0); count++; sum+=ms; tail.addLast(ms); if(tail.size>128) tail.removeFirst() }
    val averageMs: Double get() = if(count==0L) 0.0 else sum.toDouble()/count
    val p95Ms: Long get() = if(tail.isEmpty()) 0 else tail.sorted()[ceil(tail.size*.95).toInt()-1]
}

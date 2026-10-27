package systems.kaula.aura

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class YamnetClassifier(context: Context) : AudioEventClassifier {
    private val labels=context.assets.open("yamnet_labels.txt").bufferedReader().use { it.readLines() }
    private val ids=context.assets.open("yamnet_class_map.csv").bufferedReader().use { it.readLines().drop(1).map { line -> line.split(',',limit=3)[1] } }
    private val mutex=Mutex()
    private var closed=false
    private val model: ByteBuffer
    private val interpreter: Interpreter
    private val input=ByteBuffer.allocateDirect(ModelSpec.WINDOW*4).order(ByteOrder.nativeOrder())
    private val output=arrayOf(FloatArray(ModelSpec.CLASSES))
    init {
        val bytes=context.assets.open("yamnet.tflite").use { it.readBytes() }
        val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        require(sha==ModelSpec.SHA256) { "Model checksum mismatch" }
        require(labels.size==ModelSpec.CLASSES && ids.size==labels.size && ids.toSet().size==ids.size) { "Invalid model label map" }
        model=ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }
        val instance=Interpreter(model,Interpreter.Options().setNumThreads(2))
        try {
            instance.allocateTensors()
            ModelSpec.validate(ModelSpec.SAMPLE_RATE,instance.getInputTensor(0).shape(),instance.getOutputTensor(0).shape())
            require(instance.inputTensorCount==1 && instance.outputTensorCount==1)
            require(instance.getInputTensor(0).dataType()==DataType.FLOAT32 && instance.getOutputTensor(0).dataType()==DataType.FLOAT32)
            interpreter=instance
        } catch(e: Exception) { instance.close(); throw e }
    }
    override suspend fun classify(samples: FloatArray): ClassificationResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            check(!closed) { "Classifier closed" }
            require(samples.size==ModelSpec.WINDOW && samples.all { it.isFinite() && it>=-1 && it<=1 }) { "Invalid normalized audio window" }
            input.rewind(); samples.forEach { input.putFloat(it) }; input.rewind()
            val start=System.nanoTime(); interpreter.run(input,output)
            val scores=output[0]
            require(scores.all { it.isFinite() && it in 0f..1f }) { "Invalid classifier output" }
            ClassificationResult(scores.mapIndexed { index, score -> ClassScore(ids[index],labels[index],score) },(System.nanoTime()-start)/1000000,ModelSpec.VERSION)
        }
    }
    // Session owner must join its inference worker before close; no concurrent native close/run.
    override fun close() { if(!closed) { closed=true; interpreter.close() } }
}

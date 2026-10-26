package systems.kaula.aura

import kotlin.math.sqrt

object Signal {
    fun rms(samples: ShortArray, count: Int = samples.size): Double {
        require(count in 0..samples.size)
        if (count == 0) return 0.0
        return sqrt((0 until count).sumOf { val value = samples[it] / 32768.0; value * value } / count)
    }

    fun parseRule(text: String): Double? {
        val match = Regex("notify me when (?:sound level|rms) (?:is )?above (0(?:\\.\\d+)?|1(?:\\.0+)?)", RegexOption.IGNORE_CASE).matchEntire(text.trim()) ?: return null
        return match.groupValues[1].toDoubleOrNull()?.takeIf { it > 0 && it <= 1 }
    }
}

class ThresholdGate(private val cooldownMs: Long = 30_000) {
    private val lastEvent = mutableMapOf<String, Long>()
    fun shouldNotify(id: String, rms: Double, threshold: Double, now: Long): Boolean {
        if (rms <= threshold || lastEvent[id]?.let { now - it < cooldownMs } == true) return false
        lastEvent[id] = now
        return true
    }
}

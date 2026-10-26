package systems.kaula.aura

import org.junit.Assert.*
import org.junit.Test

class SignalTest {
    @Test fun rmsHandlesSilenceAndFullScale() {
        assertEquals(0.0, Signal.rms(shortArrayOf(0, 0)), 0.00001)
        assertEquals(1.0, Signal.rms(shortArrayOf(Short.MIN_VALUE)), 0.00001)
        assertEquals(0.5, Signal.rms(shortArrayOf(16384, -16384)), 0.00001)
        assertEquals(0.0, Signal.rms(shortArrayOf()), 0.00001)
        assertEquals(0.5, Signal.rms(shortArrayOf(16384, 0), 1), 0.00001)
    }
    @Test fun rulesAreConstrainedToAmplitude() {
        assertEquals(0.15, Signal.parseRule("Notify me when sound level is above 0.15")!!, 0.00001)
        assertEquals(1.0, Signal.parseRule("Notify me when RMS above 1")!!, 0.00001)
        listOf("Detect rain", "Call emergency services", "Notify me when RMS above 0", "Notify me when RMS above 1.1").forEach { assertNull(Signal.parseRule(it)) }
    }
    @Test fun cooldownIsPerRuleAndRequiresStrictThreshold() {
        val gate = ThresholdGate()
        assertFalse(gate.shouldNotify("a", 0.2, 0.2, 0))
        assertTrue(gate.shouldNotify("a", 0.3, 0.2, 0))
        assertFalse(gate.shouldNotify("a", 0.3, 0.2, 29999))
        assertTrue(gate.shouldNotify("b", 0.3, 0.2, 1))
        assertTrue(gate.shouldNotify("a", 0.3, 0.2, 30000))
    }
}

package com.openclawchip.jarvis.audio

import org.junit.Assert.*
import org.junit.Test

class SherpaWakeWordTest {
    @Test fun adapterNormalizesPcmAndClosesOnceWithoutRequiringAKey() {
        var received = floatArrayOf()
        var closes = 0
        val wake = SherpaWakeWord(object : SherpaWakeWord.Engine {
            override fun process(samples: FloatArray): Boolean { received = samples; return true }
            override fun close() { closes++ }
        })
        val frame = ShortArray(512).also { it[0] = Short.MIN_VALUE; it[1] = 16384 }
        assertTrue(wake.process(frame))
        assertEquals(-1f, received[0], 0f)
        assertEquals(0.5f, received[1], 0f)
        wake.close(); wake.close()
        assertEquals(1, closes)
        assertFalse(wake.process(frame))
    }
}

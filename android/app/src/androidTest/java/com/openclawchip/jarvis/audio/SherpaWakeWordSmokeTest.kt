package com.openclawchip.jarvis.audio

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SherpaWakeWordSmokeTest {
    @Test fun bundledJarvisModelRejectsSilenceAndDetectsSyntheticWake() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        SherpaWakeWord(instrumentation.targetContext, 0.6f).use { wake ->
            repeat(30) { assertFalse(wake.process(ShortArray(512))) }
            // Locally generated 16 kHz PCM16 Microsoft system-TTS fixture, no recorded user audio.
            val wav = instrumentation.context.assets.open("jarvis.wav").use { it.readBytes() }
            var offset = 12
            while (offset + 8 < wav.size && String(wav, offset, 4, Charsets.US_ASCII) != "data") {
                val size = (0..3).sumOf { (wav[offset + 4 + it].toInt() and 255) shl (it * 8) }
                offset += 8 + size + (size and 1)
            }
            assertTrue("missing WAV data", offset + 8 < wav.size)
            val pcm = wav.copyOfRange(offset + 8, wav.size)
            val samples = ShortArray(pcm.size / 2) {
                ((pcm[it * 2].toInt() and 255) or (pcm[it * 2 + 1].toInt() shl 8)).toShort()
            }
            var detected = false
            val padded = samples + ShortArray(16000)
            for (start in padded.indices step 512) {
                val frame = padded.copyOfRange(start, minOf(start + 512, padded.size)).copyOf(512)
                detected = wake.process(frame) || detected
            }
            assertTrue("JARVIS was not detected in the controlled speech fixture", detected)
        }
    }
}

package com.openclawchip.jarvis.audio

import ai.picovoice.porcupine.Porcupine
import android.content.Context

class PorcupineWakeWord(context: Context, accessKey: String, sensitivity: Float) : WakeWord {
    private val porcupine: Porcupine = try {
        Porcupine.Builder()
            .setAccessKey(accessKey)
            .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
            .setSensitivity(sensitivity)
            .build(context.applicationContext)
    } catch (e: Exception) {
        // Deliberately drop the cause and its message: they could echo the key.
        throw WakeWordUnavailable("Wake word engine unavailable (${e.javaClass.simpleName})")
    }

    init {
        if (porcupine.frameLength != FRAME_LENGTH || porcupine.sampleRate != SAMPLE_RATE) {
            porcupine.delete()
            throw WakeWordUnavailable("Unexpected wake word frame length or sample rate")
        }
    }

    @Volatile private var closed = false

    override fun process(frame: ShortArray): Boolean {
        if (closed) return false
        return try {
            porcupine.process(frame) >= 0
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            porcupine.delete()
        }
    }

    private companion object {
        const val FRAME_LENGTH = 512
        const val SAMPLE_RATE = 16000
    }
}

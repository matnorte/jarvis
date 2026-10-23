package com.openclawchip.jarvis.audio

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper

/** Short feedback tones from the platform ToneGenerator (no assets, independent of AudioOut). */
object Tones {
    fun chime() = play(ToneGenerator.TONE_PROP_ACK, 200)
    fun error() = play(ToneGenerator.TONE_PROP_NACK, 400)

    private fun play(tone: Int, ms: Int) {
        val gen = try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80)
        } catch (_: RuntimeException) {
            return
        }
        gen.startTone(tone, ms)
        Handler(Looper.getMainLooper()).postDelayed({ gen.release() }, ms + 200L)
    }
}

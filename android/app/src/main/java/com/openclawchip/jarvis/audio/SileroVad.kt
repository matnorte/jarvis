package com.openclawchip.jarvis.audio

import android.content.Context
import com.konovalov.vad.silero.Vad as SileroLib
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate

/** Silero VAD at 16 kHz / 512-sample frames. Duration smoothing is left to Conversation. */
class SileroVad(context: Context) : Vad {
    private val vad: VadSilero = SileroLib.builder()
        .setContext(context.applicationContext)
        .setSampleRate(SampleRate.SAMPLE_RATE_16K)
        .setFrameSize(FrameSize.FRAME_SIZE_512)
        .setMode(Mode.NORMAL)
        .setSpeechDurationMs(0)
        .setSilenceDurationMs(0)
        .build()

    @Volatile private var closed = false

    @Synchronized
    override fun isSpeech(frame: ShortArray): Boolean = !closed && vad.isSpeech(frame)

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            vad.close()
        }
    }
}

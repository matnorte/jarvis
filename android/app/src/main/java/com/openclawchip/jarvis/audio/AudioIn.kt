package com.openclawchip.jarvis.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

/** Mic capture: 16 kHz mono PCM16, delivered in 512-sample frames on a dedicated thread. */
class AudioIn(private val onFrame: (ShortArray) -> Unit) {
    private var record: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /** Requires RECORD_AUDIO. Throws IllegalStateException if the mic cannot be opened. */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME * 2 * 8)
            )
        } catch (e: Exception) {
            throw IllegalStateException("Cannot open microphone", e)
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Microphone not initialized")
        }
        if (AcousticEchoCanceler.isAvailable()) {
            aec = AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
        }
        if (NoiseSuppressor.isAvailable()) {
            ns = NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true }
        }
        record = rec
        running = true
        rec.startRecording()
        thread = Thread({ readLoop(rec) }, "jarvis-audio-in").also { it.start() }
    }

    private fun readLoop(rec: AudioRecord) {
        val buf = ShortArray(FRAME)
        while (running) {
            var off = 0
            while (running && off < FRAME) {
                val n = rec.read(buf, off, FRAME - off)
                if (n < 0) { running = false; return }
                off += n
            }
            if (running && off == FRAME) onFrame(buf.copyOf())
        }
    }

    @Synchronized
    fun stop() {
        if (!running && record == null) return
        running = false
        val t = thread
        thread = null
        try { record?.stop() } catch (_: IllegalStateException) {}
        if (t != null && t !== Thread.currentThread()) t.join(1000)
        aec?.release(); aec = null
        ns?.release(); ns = null
        record?.release(); record = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME = 512
    }
}

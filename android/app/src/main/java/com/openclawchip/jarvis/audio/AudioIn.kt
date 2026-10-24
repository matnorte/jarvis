package com.openclawchip.jarvis.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor

/** 16 kHz mono PCM16 capture. Errors are reported after native resources have been released. */
class AudioIn internal constructor(
    private val onFrame: (ShortArray) -> Unit,
    private val onError: (String) -> Unit,
    private val factory: () -> Mic,
) {
    constructor(onError: (String) -> Unit = {}, onFrame: (ShortArray) -> Unit) :
        this(onFrame, onError, { PlatformMic() })

    internal interface Mic : AutoCloseable {
        val recording: Boolean
        fun start()
        fun read(frame: ShortArray, offset: Int, count: Int): Int
        fun stop()
    }

    private var record: Mic? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    @Synchronized
    fun start() {
        if (record != null) return
        val mic = try { factory() } catch (_: Exception) {
            throw IllegalStateException("Cannot open microphone")
        }
        record = mic
        try {
            mic.start()
            check(mic.recording) { "Microphone is not recording" }
            running = true
            thread = Thread({ readLoop(mic) }, "jarvis-audio-in").also { it.start() }
        } catch (_: Exception) {
            cleanup(mic)
            throw IllegalStateException("Cannot start microphone")
        }
    }

    private fun readLoop(mic: Mic) {
        var failure: String? = null
        try {
            val buf = ShortArray(FRAME)
            while (running) {
                var offset = 0
                while (running && offset < FRAME) {
                    val n = mic.read(buf, offset, FRAME - offset)
                    check(n > 0 && mic.recording) { "Microphone read failed" }
                    offset += n
                }
                if (running && offset == FRAME) onFrame(buf.copyOf())
            }
        } catch (_: Exception) {
            if (running) failure = "Microphone capture failed"
        } finally {
            cleanup(mic)
        }
        failure?.let { onError(it) }
    }

    fun stop() {
        val mic: Mic
        val reader: Thread?
        synchronized(this) {
            mic = record ?: return
            running = false
            reader = thread
            try { mic.stop() } catch (_: Exception) {}
        }
        // Never join while holding the monitor needed by the reader's finally block.
        if (reader != null && reader !== Thread.currentThread()) reader.join(1000)
        if (reader == null || !reader.isAlive) cleanup(mic)
    }

    @Synchronized
    private fun cleanup(mic: Mic) {
        if (record !== mic) return
        running = false
        try { mic.stop() } catch (_: Exception) {}
        try { mic.close() } finally { record = null; thread = null }
    }

    @SuppressLint("MissingPermission")
    private class PlatformMic : Mic {
        private val record: AudioRecord
        private var aec: AcousticEchoCanceler? = null
        private var ns: NoiseSuppressor? = null
        init {
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 16))
            try {
                check(record.state == AudioRecord.STATE_INITIALIZED)
                if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(record.audioSessionId)?.also { it.enabled = true }
                if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(record.audioSessionId)?.also { it.enabled = true }
            } catch (e: Exception) { close(); throw e }
        }
        override val recording get() = record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        override fun start() = record.startRecording()
        override fun read(frame: ShortArray, offset: Int, count: Int) = record.read(frame, offset, count)
        override fun stop() = record.stop()
        override fun close() {
            aec?.release(); aec = null
            ns?.release(); ns = null
            record.release()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME = 512
    }
}

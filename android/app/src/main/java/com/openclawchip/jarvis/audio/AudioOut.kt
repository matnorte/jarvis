package com.openclawchip.jarvis.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/** Reply audio: 24 kHz mono PCM16 streamed through an AudioTrack on a writer thread. */
class AudioOut(context: Context) : AutoCloseable {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val track: AudioTrack
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val lock = Any()

    // Guarded by lock.
    private var pending = 0          // chunks queued or being written
    private var framesWritten = 0L   // frames accepted by the track (relative to head baseline)
    private var generation = 0       // bumped by flush() so a stale in-flight write is not counted
    private var dirty = false        // audio written since the last drain notification
    private var focusRequest: AudioFocusRequest? = null

    @Volatile private var lastLevel = 0f
    @Volatile private var onDrained: (() -> Unit)? = null
    @Volatile private var closed = false
    private val writer: Thread

    init {
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf * 2, SAMPLE_RATE / 5 * 2))
            .build()
        track.play()
        writer = Thread({ writeLoop() }, "jarvis-audio-out").also { it.start() }
    }

    /** Invoked on the writer thread each time everything queued has finished playing. */
    fun setOnDrained(cb: () -> Unit) { onDrained = cb }

    /** True when nothing is queued or being written and the track has played every written frame. */
    fun isDrained(): Boolean = synchronized(lock) {
        pending == 0 && track.playbackHeadPosition.toLong() >= framesWritten
    }

    /** Non-blocking: queues PCM16 LE 24 kHz mono for the writer thread. */
    fun write(pcm: ByteArray) {
        if (closed || pcm.isEmpty()) return
        synchronized(lock) {
            pending++
            dirty = true
            requestFocusLocked()
        }
        lastLevel = rms(pcm)
        queue.add(pcm)
    }

    /** Drops queued audio and whatever the track has buffered. */
    fun flush() {
        if (closed) return
        synchronized(lock) {
            val cleared = ArrayList<ByteArray>()
            queue.drainTo(cleared)
            pending -= cleared.size
            generation++
            track.pause()
            track.flush()
            track.play()
            framesWritten = track.playbackHeadPosition.toLong()
            lastLevel = 0f
        }
    }

    /** RMS of the last written block x4, clipped to 0..1. */
    fun level(): Float = lastLevel

    private fun writeLoop() {
        while (!closed) {
            val chunk = try {
                queue.poll(20, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            }
            if (chunk == null) {
                notifyIfDrained()
                continue
            }
            val gen = synchronized(lock) { generation }
            var off = 0
            while (off < chunk.size && !closed) {
                val n = track.write(chunk, off, chunk.size - off)
                if (n <= 0) break
                off += n
                val stale = synchronized(lock) {
                    if (gen == generation) { framesWritten += n / 2; false } else true
                }
                if (stale) break
            }
            synchronized(lock) { pending-- }
        }
    }

    private fun notifyIfDrained() {
        val fire = synchronized(lock) {
            if (dirty && pending == 0 && track.playbackHeadPosition.toLong() >= framesWritten) {
                dirty = false
                abandonFocusLocked()
                true
            } else false
        }
        if (fire) onDrained?.invoke()
    }

    private fun requestFocusLocked() {
        if (focusRequest != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { }
            .build()
        audioManager.requestAudioFocus(req)
        focusRequest = req
    }

    private fun abandonFocusLocked() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    override fun close() {
        if (closed) return
        closed = true
        queue.clear()
        try { track.pause(); track.flush() } catch (_: IllegalStateException) {}
        if (Thread.currentThread() !== writer) writer.join(1000)
        track.release()
        synchronized(lock) { abandonFocusLocked() }
    }

    private fun rms(pcm: ByteArray): Float {
        val n = pcm.size / 2
        var sum = 0.0
        for (i in 0 until n) {
            val s = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort() / 32768.0
            sum += s * s
        }
        return if (n == 0) 0f else (sqrt(sum / n) * 4).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val SAMPLE_RATE = 24000
    }
}

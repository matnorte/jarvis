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
class AudioOut(context: Context, private val onFocusChange: (Int) -> Unit = {}) : AutoCloseable {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val track: AudioTrack
    private data class Chunk(val pcm: ByteArray, val generation: Int)
    private val queue = LinkedBlockingQueue<Chunk>()
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
        // The default threshold can exceed an entire short reply (e.g. 100 ms).
        track.setStartThresholdInFrames(SAMPLE_RATE / 100)
        track.play()
        writer = Thread({ writeLoop() }, "jarvis-audio-out").also { it.start() }
    }

    /** Invoked on the writer thread each time everything queued has finished playing. */
    fun setOnDrained(cb: () -> Unit) { onDrained = cb }

    /** True when nothing is queued or being written and the track has played every written frame. */
    fun isDrained(): Boolean = synchronized(lock) {
        pending == 0 && head() >= framesWritten
    }

    /** Non-blocking: queues PCM16 LE 24 kHz mono for the writer thread. */
    fun write(pcm: ByteArray) {
        if (closed || pcm.isEmpty()) return
        synchronized(lock) {
            if (closed) return
            pending++
            dirty = true
            requestFocusLocked()
            lastLevel = rms(pcm)
            queue.add(Chunk(pcm.copyOf(), generation))
        }
    }

    /** Drops queued audio and whatever the track has buffered. */
    fun flush() {
        if (closed) return
        synchronized(lock) {
            if (closed) return
            val cleared = ArrayList<Chunk>()
            queue.drainTo(cleared)
            pending -= cleared.size
            generation++
            track.pause()
            track.flush()
            track.play()
            framesWritten = head()
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
            var off = 0
            while (off < chunk.pcm.size && !closed) {
                // Nonblocking 10 ms slices: generation check + native write are atomic with flush.
                val n = synchronized(lock) {
                    if (closed || chunk.generation != generation) -1
                    else track.write(chunk.pcm, off, minOf(480, chunk.pcm.size - off), AudioTrack.WRITE_NON_BLOCKING)
                        .also { if (it > 0) framesWritten += it / 2 }
                }
                if (n < 0) break
                if (n == 0) { Thread.sleep(5); continue }
                off += n
            }
            synchronized(lock) { pending-- }
        }
    }

    private fun notifyIfDrained() {
        val fire = synchronized(lock) {
            if (dirty && pending == 0 && head() >= framesWritten) {
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
            .setOnAudioFocusChangeListener { onFocusChange(it) }
            .build()
        audioManager.requestAudioFocus(req)
        focusRequest = req
    }

    private fun abandonFocusLocked() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            queue.clear()
            track.pause(); track.flush()
        }
        if (Thread.currentThread() !== writer) writer.join(1000)
        track.release()
        synchronized(lock) { abandonFocusLocked() }
    }

    private fun head(): Long = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL

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

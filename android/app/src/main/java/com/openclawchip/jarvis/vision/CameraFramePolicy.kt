package com.openclawchip.jarvis.vision

/** Keep camera traffic recent and bounded; rejected frames never consume a send slot. */
class CameraFramePolicy(private val now: () -> Long) {
    private var lastSentAt: Long? = null
    fun allow(byteCount: Int, connected: Boolean, queuedBytes: Long): Boolean {
        if (!connected || byteCount !in 1..MAX_JPEG_BYTES || queuedBytes >= MAX_QUEUED_BYTES) return false
        val time = now()
        if (lastSentAt?.let { time - it < MIN_FRAME_MS } == true) return false
        lastSentAt = time
        return true
    }
    companion object {
        const val MAX_JPEG_BYTES = 128 * 1024
        const val MAX_QUEUED_BYTES = 64 * 1024L
        const val MIN_FRAME_MS = 500L
    }
}

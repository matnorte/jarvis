package com.openclawchip.jarvis.audio

/** Fixed-capacity ring of the most recent mic frames; oldest dropped on overflow. */
class PreRoll(val capacityFrames: Int) {
    private val frames = ArrayDeque<ShortArray>()

    @Synchronized
    fun push(frame: ShortArray) {
        if (capacityFrames <= 0) return
        while (frames.size >= capacityFrames) frames.removeFirst()
        frames.addLast(frame.copyOf())
    }

    /** Returns buffered frames oldest first and empties the buffer. */
    @Synchronized
    fun drain(): List<ShortArray> {
        val out = frames.toList()
        frames.clear()
        return out
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SAMPLES = 512

        fun forMillis(ms: Int): PreRoll {
            val samples = ms * SAMPLE_RATE / 1000
            return PreRoll((samples + FRAME_SAMPLES - 1) / FRAME_SAMPLES)
        }
    }
}

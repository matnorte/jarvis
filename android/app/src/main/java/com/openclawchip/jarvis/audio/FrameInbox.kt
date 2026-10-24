package com.openclawchip.jarvis.audio

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel

/** Bound capture backlog to 256 ms by default; inference must consume recent audio. */
internal class FrameInbox(capacity: Int = 8) : AutoCloseable {
    data class Frame(val epoch: Int, val samples: ShortArray)
    private val frames = Channel<Frame>(capacity, BufferOverflow.DROP_OLDEST)
    fun offer(epoch: Int, samples: ShortArray) { frames.trySend(Frame(epoch, samples)) }
    suspend fun receive(): Frame? = frames.receiveCatching().getOrNull()
    override fun close() { frames.close() }
}

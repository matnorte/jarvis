package com.openclawchip.jarvis.audio

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import org.junit.Assert.*
import org.junit.Test

class FrameInboxTest {
    @Test fun closeReleasesSuspendedConsumerWithoutAnException() = runTest {
        val inbox = FrameInbox()
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { inbox.receive() }
        inbox.close()
        assertNull(waiting.await())
    }
    @Test fun slowConsumerKeepsOnlyRecentFramesWithTheirCaptureGeneration() = runTest {
        val inbox = FrameInbox(capacity = 3)
        repeat(100) { inbox.offer(7, shortArrayOf(it.toShort())) }
        inbox.offer(8, shortArrayOf(100))
        val recent = List(3) { inbox.receive()!! }
        assertEquals(listOf(98, 99, 100), recent.map { it.samples[0].toInt() })
        assertEquals(listOf(7, 7, 8), recent.map { it.epoch })
        inbox.offer(9, shortArrayOf(101))
        assertEquals(9, inbox.receive()!!.epoch)
        inbox.close()
    }
}

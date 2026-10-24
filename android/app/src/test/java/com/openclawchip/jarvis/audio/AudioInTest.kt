package com.openclawchip.jarvis.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class AudioInTest {
    private class Mic(private val busy: Boolean = false) : AudioIn.Mic {
        override val recording get() = !busy
        val released = CountDownLatch(1)
        val readGate = CountDownLatch(1)
        var frames = false
        override fun start() {}
        override fun read(frame: ShortArray, offset: Int, count: Int): Int {
            readGate.await(2, TimeUnit.SECONDS)
            return if (frames) count else -6 // ERROR_DEAD_OBJECT
        }
        override fun stop() { readGate.countDown() }
        override fun close() { released.countDown() }
    }

    @Test fun deadMicReportsErrorReleasesAndCanRestart() {
        val errors = CountDownLatch(1)
        val mic = Mic()
        val created = AtomicInteger()
        val input = AudioIn({}, { errors.countDown() }) { created.incrementAndGet(); mic }
        input.start(); input.start()
        assertEquals(1, created.get())
        mic.readGate.countDown()
        assertTrue(errors.await(2, TimeUnit.SECONDS))
        assertTrue(mic.released.await(2, TimeUnit.SECONDS))
        input.start()
        input.stop()
        assertEquals(2, created.get())
    }

    @Test fun startThatDoesNotRecordThrowsAndReleases() {
        val mic = Mic(busy = true)
        val input = AudioIn({}, {}) { mic }
        assertThrows(IllegalStateException::class.java) { input.start() }
        assertEquals(0L, mic.released.count)
        input.stop()
    }

    @Test fun frameCallbackFailureReportsAndCleansUp() {
        val mic = Mic().also { it.frames = true; it.readGate.countDown() }
        val errors = CountDownLatch(1)
        val input = AudioIn({ throw IllegalStateException("callback failed") }, { errors.countDown() }) { mic }
        input.start()
        assertTrue(errors.await(2, TimeUnit.SECONDS))
        assertTrue(mic.released.await(2, TimeUnit.SECONDS))
        input.stop()
    }

    @Test fun intentionalStopDoesNotReportFailure() {
        val errors = AtomicInteger()
        val mic = Mic()
        val input = AudioIn({}, { errors.incrementAndGet() }) { mic }
        input.start(); input.stop()
        assertEquals(0, errors.get())
        assertEquals(0L, mic.released.count)
    }
}

package com.openclawchip.jarvis.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioAdaptersSmokeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun audioOut_flushCannotRefillTrackWithStaleAudio() {
        AudioOut(context).use { out ->
            val drained = CountDownLatch(1)
            out.setOnDrained { drained.countDown() }
            out.write(ByteArray(24000 * 2 * 2))
            Thread.sleep(100)
            out.flush()
            assertTrue("flush drain exceeded one buffer period", drained.await(250, TimeUnit.MILLISECONDS))
            // Observe the native head too: a premature callback can hide a stale blocked write.
            val field = AudioOut::class.java.getDeclaredField("track").also { it.isAccessible = true }
            val track = field.get(out) as android.media.AudioTrack
            val head = track.playbackHeadPosition.toLong() and 0xffffffffL
            Thread.sleep(250)
            val later = track.playbackHeadPosition.toLong() and 0xffffffffL
            assertTrue("stale audio still playing after flush", later - head < 480)
            assertTrue(out.isDrained())
        }
    }

    @Test fun audioOut_shortReplyStartsAndDrains() {
        AudioOut(context).use { out ->
            val drained = CountDownLatch(1)
            out.setOnDrained { drained.countDown() }
            out.write(ByteArray(2400 * 2)) // 100 ms, smaller than the track buffer.
            assertTrue("short reply never started", drained.await(1, TimeUnit.SECONDS))
            assertTrue(out.isDrained())
        }
    }

    @Test
    fun sileroVad_zeros_isNotSpeech() {
        SileroVad(context).use { vad ->
            repeat(20) { assertFalse(vad.isSpeech(ShortArray(512))) }
        }
    }

    @Test
    fun audioOut_writeSilenceAndFlush_returns() {
        val out = AudioOut(context)
        try {
            out.write(ByteArray(24000 * 2)) // 1 s of silence at 24 kHz
            out.flush()
            assertTrue(out.level() in 0f..1f)
        } finally {
            out.close()
        }
    }

    @Test
    fun audioOut_drainCallbackAndIsDrained() {
        val out = AudioOut(context)
        try {
            val fired = AtomicInteger(0)
            var latch = CountDownLatch(1)
            out.setOnDrained { fired.incrementAndGet(); latch.countDown() }
            assertTrue(out.isDrained())

            out.write(ByteArray((24000 * 0.3).toInt() * 2))
            assertFalse(out.isDrained())
            assertTrue("onDrained did not fire", latch.await(2, TimeUnit.SECONDS))
            Thread.sleep(300)
            assertEquals(1, fired.get())
            assertTrue(out.isDrained())

            latch = CountDownLatch(1)
            out.write(ByteArray((24000 * 0.3).toInt() * 2))
            assertFalse(out.isDrained())
            assertTrue("second onDrained did not fire", latch.await(2, TimeUnit.SECONDS))
            assertTrue(out.isDrained())
        } finally {
            out.close()
        }
    }

    @Test
    fun audioIn_constructs() {
        AudioIn { }
    }
}

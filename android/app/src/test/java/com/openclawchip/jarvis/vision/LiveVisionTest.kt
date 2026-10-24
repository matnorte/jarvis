package com.openclawchip.jarvis.vision

import org.junit.Assert.*
import org.junit.Test

class LiveVisionTest {
    @Test fun oldCameraCannotSendFramesOrStopAReplacementAndReconnectReannounces() {
        val controls = mutableListOf<Boolean>()
        val frames = mutableListOf<ByteArray>()
        val live = LiveVision({ controls.add(it) }, { frames.add(it); true }, { 42L })
        live.start(1); live.frame(1, byteArrayOf(1))
        assertEquals(42L, live.state.value.lastSentAt)
        live.start(2)
        live.frame(1, byteArrayOf(2)); live.stop(1)
        assertTrue(live.state.value.enabled)
        assertEquals(1, frames.size)
        live.connected()
        assertEquals(listOf(true, true, true), controls)
        live.frame(2, byteArrayOf(3)); live.stop(2)
        live.frame(2, byteArrayOf(4))
        assertFalse(live.state.value.enabled)
        assertEquals(2, frames.size)
        assertEquals(false, controls.last())
        assertEquals(0L, live.state.value.lastSentAt)
    }

    @Test fun droppedFramesDoNotClaimFreshVisualContext() {
        val live = LiveVision({}, { false }, { 42L })
        live.start(1); live.frame(1, byteArrayOf(1))
        assertEquals(0L, live.state.value.lastSentAt)
        assertEquals(0L, live.state.value.framesSent)
    }
}

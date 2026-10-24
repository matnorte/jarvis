package com.openclawchip.jarvis.vision

import org.junit.Assert.*
import org.junit.Test

class CameraFramePolicyTest {
    @Test fun limitsFrameRateAndNeverQueuesWhileOfflineOrBackedUp() {
        var time = 0L
        val policy = CameraFramePolicy { time }
        assertFalse(policy.allow(100, connected = false, queuedBytes = 0))
        assertTrue(policy.allow(100, connected = true, queuedBytes = 0))
        time = 499; assertFalse(policy.allow(100, true, 0))
        time = 500; assertTrue(policy.allow(100, true, 0))
        time = 1000; assertFalse(policy.allow(100, true, 65536))
        assertFalse(policy.allow(128 * 1024 + 1, true, 0))
        assertFalse(policy.allow(0, true, 0))
        assertTrue(policy.allow(100, true, 0))
    }
}

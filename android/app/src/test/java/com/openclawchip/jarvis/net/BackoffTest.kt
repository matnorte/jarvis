package com.openclawchip.jarvis.net

import org.junit.Assert.assertEquals
import org.junit.Test

class BackoffTest {
    @Test fun backoffSequence() {
        val b = Backoff()
        assertEquals(
            listOf(1000L, 2000L, 4000L, 8000L, 16000L, 30000L, 30000L),
            List(7) { b.next() },
        )
    }

    @Test fun resetStartsOver() {
        val b = Backoff()
        repeat(4) { b.next() }
        b.reset()
        assertEquals(1000L, b.next())
    }
}

package com.openclawchip.jarvis.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreRollTest {
    private fun frame(n: Int) = ShortArray(4) { n.toShort() }

    @Test fun capacityFor300msIs10() = assertEquals(10, PreRoll.forMillis(300).capacityFrames)

    @Test fun drainReturnsOldestFirstAndEmpties() {
        val p = PreRoll(10)
        (1..3).forEach { p.push(frame(it)) }
        val d = p.drain()
        assertEquals(listOf(1, 2, 3), d.map { it[0].toInt() })
        assertTrue(p.drain().isEmpty())
    }

    @Test fun overflowDropsOldest() {
        val p = PreRoll(10)
        (1..12).forEach { p.push(frame(it)) }
        assertEquals((3..12).toList(), p.drain().map { it[0].toInt() })
    }

    @Test fun pushCopiesInput() {
        val p = PreRoll(2)
        val src = shortArrayOf(1, 2)
        p.push(src)
        src[0] = 99
        assertArrayEquals(shortArrayOf(1, 2), p.drain().single())
    }

    @Test fun pcmIsLittleEndian() =
        assertArrayEquals(byteArrayOf(0x02, 0x01), shortArrayOf(0x0102).toPcm16Le())
}

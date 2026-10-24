package com.openclawchip.jarvis.settings

import org.junit.Assert.*
import org.junit.Test

class SecretBoxTest {
    @Test fun keystoreRoundTripUsesRandomIvAndRejectsTampering() {
        val box = KeystoreSecretBox("jarvis-instrumentation")
        val first = box.encrypt("test-only-value")
        val second = box.encrypt("test-only-value")
        assertNotEquals(first, second)
        assertFalse(first.contains("test-only-value"))
        assertEquals("test-only-value", KeystoreSecretBox("jarvis-instrumentation").decrypt(first))
        val bytes = android.util.Base64.decode(first, android.util.Base64.NO_WRAP)
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { box.decrypt(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)) }
    }
}

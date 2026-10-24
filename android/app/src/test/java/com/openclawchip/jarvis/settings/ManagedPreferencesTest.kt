package com.openclawchip.jarvis.settings

import org.junit.Assert.*
import org.junit.Test

class ManagedPreferencesTest {
    @Test fun canonicalPreferencesRejectInvalidRangesAndUnsafeAddresses() {
        assertEquals(700L, ManagedPreferences().endSilenceMs)
        assertThrows(IllegalArgumentException::class.java) { ManagedPreferences(wakeSensitivity = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { ManagedPreferences(endSilenceMs = 99) }
        for (url in listOf("http://example.org", "wss://user:password@example.org/ws", "wss://example.org/ws?token=private", "wss://example.org:99999/ws")) {
            assertThrows(IllegalArgumentException::class.java) { ManagedPreferences(serverUrl = url) }
        }
    }
}

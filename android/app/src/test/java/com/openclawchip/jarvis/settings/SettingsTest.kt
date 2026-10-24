package com.openclawchip.jarvis.settings

import android.content.SharedPreferences
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SettingsTest {
    private val raw = mutableMapOf<String, Any?>()
    private val editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
        arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
        when {
            method.name.startsWith("put") -> { raw[args!![0] as String] = args[1]; proxy }
            method.name == "remove" -> { raw.remove(args!![0]); proxy }
            method.name == "commit" -> true
            else -> null
        }
    } as SharedPreferences.Editor
    private val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when {
            method.name == "edit" -> editor
            method.name == "contains" -> raw.containsKey(args!![0])
            method.name.startsWith("get") -> raw[args!![0]] ?: args[1]
            else -> null
        }
    } as SharedPreferences
    private val box = object : SecretBox {
        override fun encrypt(plain: String) = plain.reversed()
        override fun decrypt(cipher: String) = cipher.reversed()
    }
    private fun settings() = Settings(prefs, box)

    @Test fun defaultsMatchSpec() {
        val s = settings()
        assertEquals("wss://jarvis.openclawchip.tech/ws", s.serverUrl)
        assertEquals(0.6f, s.wakeSensitivity, 0f)
        assertEquals(700L, s.endSilenceMs)
        assertFalse(s.interruptBySpeech); assertFalse(s.startOnBoot)
        assertNull(s.deviceToken)
    }

    @Test fun secretsStoredEncryptedAndSurviveReload() {
        settings().apply { deviceToken = "test-device-value" }
        assertFalse(raw.values.contains("test-device-value"))
        assertEquals("test-device-value", settings().deviceToken)
        assertTrue(settings().hasSecret("deviceToken"))
    }

    @Test fun onlyDeviceTokenIsNeededAndBlankRemovesIt() {
        val s = settings()
        assertFalse(s.isComplete)
        s.deviceToken = "example"; assertTrue(s.isComplete)
        s.deviceToken = " "; assertFalse(s.isComplete)
        assertFalse(s.hasSecret("deviceToken"))
    }

    @Test fun toStringHidesSecrets() {
        val s = settings().apply { deviceToken = "test-device-value" }
        assertFalse(s.toString().contains("test-device-value"))
    }

    @Test fun rejectsNonWssAndCredentialBearingUrls() {
        listOf("ws://host/ws", "https://host/ws", "wss://", "wss://user:password@host/ws").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) { settings().serverUrl = url }
        }
        settings().serverUrl = "wss://example.org/ws"
        assertEquals("wss://example.org/ws", settings().serverUrl)
    }

    @Test fun corruptCipherIsIncompleteWithoutLeakingException() {
        val broken = object : SecretBox {
            override fun encrypt(plain: String) = plain
            override fun decrypt(cipher: String): String = error("sensitive cause")
        }
        settings().apply { deviceToken = "example" }
        assertFalse(Settings(prefs, broken).isComplete)
    }

    @Test fun legacyPicovoiceCredentialIsRemovedDuringMigration() {
        raw["picovoiceKey"] = "old-encrypted-value"
        settings()
        assertFalse(raw.containsKey("picovoiceKey"))
    }
}

package com.openclawchip.jarvis.settings

import android.content.Context
import android.content.SharedPreferences
import java.net.URI

class Settings(private val prefs: SharedPreferences, private val box: SecretBox) {
    init { if (prefs.contains("picovoiceKey")) prefs.edit().remove("picovoiceKey").apply() }
    var serverUrl: String
        get() = prefs.getString("serverUrl", DEFAULT_URL)!!
        set(value) {
            val uri = try { URI(value) } catch (_: Exception) { null }
            require(uri?.scheme == "wss" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) {
                "Enter a wss:// server URL without credentials"
            }
            prefs.edit().putString("serverUrl", value).apply()
        }
    var deviceToken: String?
        get() = secret("deviceToken")
        set(value) = saveSecret("deviceToken", value)
    var wakeSensitivity: Float
        get() = prefs.getFloat("wakeSensitivity", 0.6f)
        set(value) { require(value in 0f..1f); prefs.edit().putFloat("wakeSensitivity", value).apply() }
    var endSilenceMs: Long
        get() = prefs.getLong("endSilenceMs", 700)
        set(value) { require(value in 100L..5000L); prefs.edit().putLong("endSilenceMs", value).apply() }
    var interruptBySpeech: Boolean
        get() = prefs.getBoolean("interruptBySpeech", false)
        set(value) { prefs.edit().putBoolean("interruptBySpeech", value).apply() }
    var startOnBoot: Boolean
        get() = prefs.getBoolean("startOnBoot", false)
        set(value) { prefs.edit().putBoolean("startOnBoot", value).apply() }
    val isComplete get() = hasSecret("deviceToken")
    fun hasSecret(name: String) = name == "deviceToken" && !secret(name).isNullOrBlank()

    private fun secret(name: String): String? = try {
        prefs.getString(name, null)?.let(box::decrypt)?.takeUnless(String::isBlank)
    } catch (_: Exception) { null } // Keystore reset/tampering requires replacement; never echo the value/cause.

    private fun saveSecret(name: String, value: String?) {
        val plain = value?.trim()?.takeUnless(String::isBlank)
        val editor = prefs.edit()
        if (plain == null) editor.remove(name) else editor.putString(name, box.encrypt(plain))
        check(editor.commit()) { "Unable to save encrypted setting" }
    }
    override fun toString() = "Settings"
    companion object {
        const val DEFAULT_URL = "wss://jarvis.openclawchip.tech/ws"
        fun open(context: Context) = Settings(context.getSharedPreferences("jarvis", Context.MODE_PRIVATE), KeystoreSecretBox())
    }
}

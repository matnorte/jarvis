package com.openclawchip.jarvis.settings

import java.net.URI
import org.json.JSONObject

/** Canonical nonsecret phone preferences, cached locally for offline wake detection. */
data class ManagedPreferences(
    val serverUrl: String = Settings.DEFAULT_URL,
    val wakeSensitivity: Float = .6f,
    val endSilenceMs: Long = 700,
    val interruptBySpeech: Boolean = false,
    val startOnBoot: Boolean = false,
    val visionEnabled: Boolean = true,
    val frontCamera: Boolean = false,
) {
    init {
        val uri = runCatching { URI(serverUrl) }.getOrNull()
        require(uri?.scheme == "wss" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.port == -1 || uri.port in 1..65535)) { "Invalid gateway address" }
        require(wakeSensitivity.isFinite() && wakeSensitivity in 0f..1f && endSilenceMs in 100L..5000L) { "Invalid managed preferences" }
    }
    companion object {
        fun fromJson(value: JSONObject): ManagedPreferences {
            fun bool(key: String) = value.get(key) as? Boolean ?: error("Invalid preference")
            val sensitivity = value.get("wake_sensitivity") as? Number ?: error("Invalid preference")
            val silence = value.get("end_silence_ms")
            require(silence is Int || silence is Long)
            return ManagedPreferences(value.getString("server_url"), sensitivity.toFloat(), (silence as Number).toLong(),
                bool("interrupt_by_speech"), bool("start_on_boot"), bool("vision_enabled"), bool("front_camera"))
        }
    }
}

data class ManagedState(val revision: Long = 0, val preferences: ManagedPreferences = ManagedPreferences())

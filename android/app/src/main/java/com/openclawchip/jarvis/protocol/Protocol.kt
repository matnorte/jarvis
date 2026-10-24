package com.openclawchip.jarvis.protocol

import org.json.JSONObject
import java.util.Base64
import com.openclawchip.jarvis.settings.ManagedPreferences

/** Client -> server messages. Field names match server/protocol.py. */
object ClientMsg {
    fun camera(enabled: Boolean): String = JSONObject().put("type", "camera").put("enabled", enabled).toString()
    fun cameraFrame(jpeg: ByteArray): String = JSONObject().put("type", "camera_frame")
        .put("jpeg", Base64.getEncoder().encodeToString(jpeg)).toString()
    fun hello(token: String): String =
        JSONObject().put("type", "hello").put("token", token).put("device", "android")
            .put("client_vad", true).put("client_config", true).toString()

    fun configAck(revision: Long, idle: Boolean): String = JSONObject().put("type", "config_ack")
        .put("revision", revision).put("idle", idle).toString()

    fun endUtterance(): String = JSONObject().put("type", "end_utterance").toString()

    fun interrupt(): String = JSONObject().put("type", "interrupt").toString()

    fun text(text: String, lang: String = "en"): String =
        JSONObject().put("type", "text").put("text", text).put("lang", lang).toString()
}

sealed interface ServerMsg {
    data class ManagedConfig(val revision: Long, val settings: ManagedPreferences) : ServerMsg
    data class State(val state: String) : ServerMsg
    data class Transcript(val text: String, val lang: String, val final: Boolean) : ServerMsg
    data class ReplyText(val delta: String) : ServerMsg
    data class Level(val mic: Float, val out: Float) : ServerMsg
    data object SpeakingDone : ServerMsg
    data class Error(val code: String, val message: String) : ServerMsg
    data class Unknown(val raw: String) : ServerMsg
}

/** Parses a server text frame. Never throws; anything malformed becomes [ServerMsg.Unknown]. */
fun parseServer(text: String): ServerMsg = try {
    val o = JSONObject(text)
    when (o.getString("type")) {
        "config" -> {
            val revision = o.get("revision")
            require((revision is Int || revision is Long) && (revision as Number).toLong() > 0)
            ServerMsg.ManagedConfig((revision as Number).toLong(), ManagedPreferences.fromJson(o.getJSONObject("settings")))
        }
        "state" -> ServerMsg.State(o.getString("state"))
        "transcript" -> ServerMsg.Transcript(o.getString("text"), o.getString("lang"), o.getBoolean("final"))
        "reply_text" -> ServerMsg.ReplyText(o.getString("delta"))
        "level" -> ServerMsg.Level(o.getDouble("mic").toFloat(), o.getDouble("out").toFloat())
        "speaking_done" -> ServerMsg.SpeakingDone
        "error" -> ServerMsg.Error(o.getString("code"), o.getString("message"))
        else -> ServerMsg.Unknown(text)
    }
} catch (e: Exception) {
    ServerMsg.Unknown(text)
}

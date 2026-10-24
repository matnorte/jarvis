package com.openclawchip.jarvis.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun cameraMessagesPreserveAudioBinaryProtocol() {
        assertEquals(true, JSONObject(ClientMsg.camera(true)).getBoolean("enabled"))
        assertEquals("camera", JSONObject(ClientMsg.camera(false)).getString("type"))
        val frame = JSONObject(ClientMsg.cameraFrame(byteArrayOf(1, 2, 3)))
        assertEquals("camera_frame", frame.getString("type"))
        assertEquals("AQID", frame.getString("jpeg"))
    }
    @Test fun helloHasDeviceAndroid() {
        val o = JSONObject(ClientMsg.hello("t"))
        assertEquals("hello" to "android", o.getString("type") to o.getString("device"))
        assertEquals("t", o.getString("token"))
        assertTrue(o.getBoolean("client_vad"))
        assertTrue(o.getBoolean("client_config"))
    }

    @Test fun managedConfigurationIsStrictAndIdleAcknowledgementHasNoSecrets() {
        val prefs = """{"server_url":"wss://jarvis.openclawchip.tech/ws","wake_sensitivity":0.7,"end_silence_ms":900,"interrupt_by_speech":true,"start_on_boot":false,"vision_enabled":false,"front_camera":true}"""
        val parsed = parseServer("""{"type":"config","revision":2,"settings":$prefs}""") as ServerMsg.ManagedConfig
        assertEquals(2L, parsed.revision)
        assertEquals(.7f, parsed.settings.wakeSensitivity, 0f)
        assertTrue(parsed.settings.frontCamera)
        assertTrue(parseServer("""{"type":"config","revision":true,"settings":$prefs}""") is ServerMsg.Unknown)
        assertTrue(parseServer("""{"type":"config","revision":2,"settings":${prefs.replace(":true", ":\"true\"")}}""") is ServerMsg.Unknown)
        val ack = JSONObject(ClientMsg.configAck(2, false))
        assertEquals(3, ack.length())
        assertEquals("config_ack", ack.getString("type")); assertEquals(2L, ack.getLong("revision"))
        assertEquals(false, ack.getBoolean("idle"))
    }

    @Test fun simpleMessagesHaveOnlyType() {
        assertEquals("end_utterance", JSONObject(ClientMsg.endUtterance()).getString("type"))
        assertEquals("interrupt", JSONObject(ClientMsg.interrupt()).getString("type"))
        assertEquals(1, JSONObject(ClientMsg.interrupt()).length())
    }

    @Test fun parsesState() =
        assertEquals(ServerMsg.State("speaking"), parseServer("""{"type":"state","state":"speaking"}"""))

    @Test fun parsesTranscriptAndError() {
        assertEquals(
            ServerMsg.Transcript("oi", "pt", true),
            parseServer("""{"type":"transcript","text":"oi","lang":"pt","final":true}"""),
        )
        assertEquals(
            ServerMsg.Error("busy", "nope"),
            parseServer("""{"type":"error","code":"busy","message":"nope"}"""),
        )
    }

    @Test fun parsesReplyLevelAndDone() {
        assertEquals(ServerMsg.ReplyText("ab"), parseServer("""{"type":"reply_text","delta":"ab"}"""))
        assertEquals(ServerMsg.Level(0.5f, 0.25f), parseServer("""{"type":"level","mic":0.5,"out":0.25}"""))
        assertEquals(ServerMsg.SpeakingDone, parseServer("""{"type":"speaking_done"}"""))
    }

    @Test fun garbageIsUnknown() {
        assertTrue(parseServer("not json") is ServerMsg.Unknown)
        assertTrue(parseServer("""{"type":"nope"}""") is ServerMsg.Unknown)
        assertTrue(parseServer("""{"type":"state"}""") is ServerMsg.Unknown)
        assertEquals(ServerMsg.Unknown("not json"), parseServer("not json"))
    }

    @Test fun textCarriesLang() {
        assertEquals("pt", JSONObject(ClientMsg.text("oi", "pt")).getString("lang"))
        assertEquals("en", JSONObject(ClientMsg.text("hi")).getString("lang"))
        assertEquals("hi", JSONObject(ClientMsg.text("hi")).getString("text"))
    }
}

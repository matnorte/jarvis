package com.openclawchip.jarvis.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun helloHasDeviceAndroid() {
        val o = JSONObject(ClientMsg.hello("t"))
        assertEquals("hello" to "android", o.getString("type") to o.getString("device"))
        assertEquals("t", o.getString("token"))
        assertTrue(o.getBoolean("client_vad"))
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

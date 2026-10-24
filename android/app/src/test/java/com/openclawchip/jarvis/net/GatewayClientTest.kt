package com.openclawchip.jarvis.net

import com.openclawchip.jarvis.protocol.ServerMsg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GatewayClientTest {
    private val token = "s3cret-token-value"
    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope
    private var client: GatewayClient? = null

    /** One server-side connection: every frame the client sent, plus the socket. */
    private class ServerConn : WebSocketListener() {
        val frames = LinkedBlockingQueue<Any>()
        val opened = LinkedBlockingQueue<WebSocket>()
        override fun onOpen(webSocket: WebSocket, response: Response) { opened.add(webSocket) }
        override fun onMessage(webSocket: WebSocket, text: String) { frames.add(text) }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { frames.add(bytes) }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
        fun socket(): WebSocket = opened.poll(5, TimeUnit.SECONDS) ?: error("no server socket")
        fun frame(): Any? = frames.poll(5, TimeUnit.SECONDS)
    }

    private class Events : GatewayClient.Listener {
        val log = CopyOnWriteArrayList<String>()
        val messages = LinkedBlockingQueue<ServerMsg>()
        val audio = LinkedBlockingQueue<ByteArray>()
        val rejected = LinkedBlockingQueue<Int>()
        val connected = LinkedBlockingQueue<Unit>()
        val disconnected = LinkedBlockingQueue<Unit>()
        @Volatile var onConnectedHook: () -> Unit = {}
        override fun onConnected() { log.add("connected"); onConnectedHook(); connected.add(Unit) }
        override fun onDisconnected() { log.add("disconnected"); disconnected.add(Unit) }
        override fun onMessage(msg: ServerMsg) { messages.add(msg) }
        override fun onAudio(pcm: ByteArray) { audio.add(pcm) }
        override fun onRejected(code: Int) { log.add("rejected$code"); rejected.add(code) }
    }

    @Before fun setUp() {
        server = MockWebServer()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After fun tearDown() {
        client?.close()
        scope.cancel()
        try { server.close() } catch (_: Exception) {}
    }

    private fun accept(conn: ServerConn) {
        server.enqueue(MockResponse.Builder().webSocketUpgrade(conn).build())
    }

    private fun newClient(events: Events, backoff: Backoff = Backoff(listOf(10, 20))): GatewayClient {
        server.start()
        return GatewayClient(server.url("/ws").toString(), token, events, scope, backoff = backoff).also { client = it }
    }

    private fun assertHello(frame: Any?) {
        assertTrue("expected text hello, got $frame", frame is String)
        val s = frame as String
        assertTrue(s, s.contains("\"type\":\"hello\""))
        assertTrue(s, s.contains(token))
        assertTrue(org.json.JSONObject(s).getBoolean("client_vad"))
    }

    @Test fun helloIsFirstFrame() {
        val conn = ServerConn(); accept(conn)
        val ev = Events()
        // The listener tries to send inside onConnected: hello must still come first.
        ev.onConnectedHook = { client!!.sendText("{\"type\":\"interrupt\"}") }
        newClient(ev).connect()
        assertNotNull(ev.connected.poll(5, TimeUnit.SECONDS))
        assertHello(conn.frame())
        assertEquals("{\"type\":\"interrupt\"}", conn.frame())
    }

    @Test fun textAndBinaryAreRouted() {
        val conn = ServerConn(); accept(conn)
        val ev = Events()
        newClient(ev).connect()
        val ws = conn.socket()
        ws.send("{\"type\":\"state\",\"state\":\"thinking\"}")
        ws.send(byteArrayOf(1, 2, 3, 4).toByteString())
        assertEquals(ServerMsg.State("thinking"), ev.messages.poll(5, TimeUnit.SECONDS))
        assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(ev.audio.poll(5, TimeUnit.SECONDS)))
    }

    @Test fun sendsTextAndAudioWhenConnected() {
        val conn = ServerConn(); accept(conn)
        val ev = Events()
        val c = newClient(ev); c.connect()
        assertNotNull(ev.connected.poll(5, TimeUnit.SECONDS))
        assertTrue(c.sendAudio(byteArrayOf(9, 8)))
        assertTrue(c.sendText("{\"type\":\"end_utterance\"}"))
        assertHello(conn.frame())
        assertEquals(byteArrayOf(9, 8).toByteString(), conn.frame())
        assertEquals("{\"type\":\"end_utterance\"}", conn.frame())
    }

    @Test fun cameraFramesUseAuthenticatedSocketAndAreThrottled() {
        val conn = ServerConn(); accept(conn)
        val ev = Events()
        val c = newClient(ev)
        assertFalse(c.sendCameraFrame(byteArrayOf(1, 2, 3)))
        c.connect()
        assertNotNull(ev.connected.poll(5, TimeUnit.SECONDS))
        assertHello(conn.frame())
        assertTrue(c.sendCameraFrame(byteArrayOf(1, 2, 3)))
        assertFalse(c.sendCameraFrame(byteArrayOf(4, 5, 6)))
        val frame = org.json.JSONObject(conn.frame() as String)
        assertEquals("camera_frame", frame.getString("type"))
        assertEquals("AQID", frame.getString("jpeg"))
        assertNull(conn.frames.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test fun reconnectsAfterDropWithHello() {
        val first = ServerConn(); val second = ServerConn()
        accept(first); accept(second)
        val ev = Events()
        newClient(ev).connect()
        assertHello(first.frame())
        first.socket().close(1001, "bye")
        assertNotNull(ev.disconnected.poll(5, TimeUnit.SECONDS))
        assertHello(second.frame())
        assertNotNull(ev.connected.poll(5, TimeUnit.SECONDS))
        assertNotNull(ev.connected.poll(5, TimeUnit.SECONDS))
        assertEquals(listOf("connected", "disconnected", "connected"), ev.log.toList())
    }

    @Test fun failedConnectReportsDisconnectedAndRetries() {
        server.enqueue(MockResponse.Builder().code(500).build())
        val conn = ServerConn(); accept(conn)
        val ev = Events()
        newClient(ev).connect()
        assertNotNull(ev.disconnected.poll(5, TimeUnit.SECONDS))
        assertHello(conn.frame())
        assertNotNull(ev.connected.poll(5, TimeUnit.SECONDS))
        assertEquals(listOf("disconnected", "connected"), ev.log.toList())
    }

    @Test fun code4401StopsRetrying() {
        val conn = ServerConn(); accept(conn)
        val ev = Events()
        val c = newClient(ev); c.connect()
        assertHello(conn.frame())
        conn.socket().close(4401, "unauthorized")
        assertEquals(4401, ev.rejected.poll(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
        assertFalse(ev.log.contains("disconnected"))
        c.reconnectNow()           // still dead after a 4401
        Thread.sleep(200)
        assertEquals(1, server.requestCount)
        assertNull(ev.rejected.poll())
    }

    @Test fun code4409WaitsForReconnectNow() {
        val first = ServerConn(); val second = ServerConn()
        accept(first); accept(second)
        val ev = Events()
        val c = newClient(ev); c.connect()
        assertHello(first.frame())
        first.socket().close(4409, "replaced")
        assertEquals(4409, ev.rejected.poll(5, TimeUnit.SECONDS))
        Thread.sleep(300)
        assertEquals(1, server.requestCount)
        assertFalse(ev.log.contains("disconnected"))
        c.connect()                // connect() also stays idle while waiting
        Thread.sleep(100)
        assertEquals(1, server.requestCount)
        c.reconnectNow()
        assertHello(second.frame())
        assertNotNull(ev.connected.poll(5, TimeUnit.SECONDS))
        assertNotNull(ev.connected.poll(5, TimeUnit.SECONDS))
    }

    @Test fun reconnectNowSkipsPendingBackoff() {
        server.enqueue(MockResponse.Builder().code(500).build())
        val conn = ServerConn(); accept(conn)
        val ev = Events()
        val c = newClient(ev, Backoff(listOf(60_000))); c.connect()
        assertNotNull(ev.disconnected.poll(5, TimeUnit.SECONDS))
        c.reconnectNow()
        assertHello(conn.frame())
        assertNotNull(ev.connected.poll(5, TimeUnit.SECONDS))
    }

    @Test fun reconnectNowIsNoOpWhenOpen() {
        val conn = ServerConn(); accept(conn)
        val ev = Events()
        val c = newClient(ev); c.connect()
        assertNotNull(ev.connected.poll(5, TimeUnit.SECONDS))
        c.reconnectNow(); c.connect()
        Thread.sleep(200)
        assertEquals(1, server.requestCount)
        assertEquals(listOf("connected"), ev.log.toList())
    }

    @Test fun closeSilencesCallbacksAndStopsRetrying() {
        val conn = ServerConn(); accept(conn)
        val ev = Events()
        val c = newClient(ev); c.connect()
        assertNotNull(ev.connected.poll(5, TimeUnit.SECONDS))
        c.close()
        Thread.sleep(300)
        assertEquals(listOf("connected"), ev.log.toList())
        assertEquals(1, server.requestCount)
        assertFalse(c.sendText("x"))
        c.reconnectNow()
        Thread.sleep(100)
        assertEquals(1, server.requestCount)
    }

    @Test fun audioWhileDisconnectedIsDropped() {
        val conn = ServerConn(); accept(conn)
        val ev = Events()
        val c = newClient(ev)
        assertFalse(c.sendAudio(byteArrayOf(1)))       // never connected
        assertFalse(c.sendText("{}"))
        c.connect()
        assertNotNull(ev.connected.poll(5, TimeUnit.SECONDS))
        assertHello(conn.frame())
        conn.socket().close(1000, null)
        assertNotNull(ev.disconnected.poll(5, TimeUnit.SECONDS))
        assertFalse(c.sendAudio(byteArrayOf(2)))       // dropped, not queued
        Thread.sleep(100)
        assertNull(conn.frames.poll())                 // nothing delivered afterwards
    }

    @Test fun tokenNotInToString() {
        val ev = Events()
        server.start()
        val c = GatewayClient(server.url("/ws").toString(), token, ev, scope)
        assertFalse(c.toString().contains(token))
        try {
            GatewayClient("not a url $token", token, ev, scope)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message.orEmpty().contains(token))
        }
    }
}

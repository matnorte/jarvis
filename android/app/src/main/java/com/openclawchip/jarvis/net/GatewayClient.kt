package com.openclawchip.jarvis.net

import com.openclawchip.jarvis.protocol.ClientMsg
import com.openclawchip.jarvis.protocol.ServerMsg
import com.openclawchip.jarvis.protocol.parseServer
import com.openclawchip.jarvis.vision.CameraFramePolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * WebSocket client for the Jarvis gateway.
 *
 * Semantics:
 *  - `hello` is the first frame of every connection; [Listener.onConnected] fires only after it was sent.
 *  - Every failed attempt and every drop calls [Listener.onDisconnected] and schedules a retry via [Backoff]
 *    (reset on a successful open). Never called after [close].
 *  - Close code 4401 -> [Listener.onRejected] and no retry, ever (build a new client). Close code 4409 ->
 *    [Listener.onRejected] and no retry until [reconnectNow]. Neither also calls onDisconnected.
 *  - Audio is never queued: [sendAudio]/[sendText] return false when not connected.
 *
 * Threading: all methods are thread-safe. Listener callbacks run on OkHttp's threads (or the given scope for
 * none of them); the owner must hop them onto its own single conversation thread.
 *
 * The token is only ever placed in the hello frame; it is not logged and not part of toString or exceptions.
 */
class GatewayClient(
    private val url: String,
    private val token: String,
    private val listener: Listener,
    private val scope: CoroutineScope,
    private val http: OkHttpClient = defaultClient(),
    private val backoff: Backoff = Backoff(),
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onMessage(msg: ServerMsg)
        fun onAudio(pcm: ByteArray)
        fun onRejected(code: Int)
    }

    private val lock = Any()
    private var socket: WebSocket? = null      // current socket (connecting or open); null when idle
    private var connected = false              // hello sent on [socket]
    private var closed = false                 // close() called
    private var dead = false                   // 4401: never retry
    private var waiting = false                // 4409: wait for reconnectNow()
    private var retryJob: Job? = null
    private val cameraPolicy = CameraFramePolicy { System.nanoTime() / 1_000_000 }

    init {
        val httpForm = when {
            url.startsWith("wss://", ignoreCase = true) -> "https://" + url.substring(6)
            url.startsWith("ws://", ignoreCase = true) -> "http://" + url.substring(5)
            else -> url
        }
        require(httpForm.toHttpUrlOrNull() != null) { "invalid gateway url" }
    }

    /** Starts connecting (idempotent). Idle while closed, rejected (4401) or waiting after a 4409. */
    fun connect() {
        synchronized(lock) {
            if (closed || dead || waiting) return
            startLocked()
        }
    }

    /** Cancels any scheduled retry and connects now. No-op if open/connecting, closed or after a 4401. */
    fun reconnectNow() {
        synchronized(lock) {
            if (closed || dead) return
            waiting = false
            startLocked()
        }
    }

    fun sendText(json: String): Boolean {
        val ws = synchronized(lock) { if (connected) socket else null } ?: return false
        return ws.send(json)
    }

    /** Sends one binary PCM frame; returns false (dropping it) when not connected. Never queues. */
    fun sendAudio(pcm: ByteArray): Boolean {
        val ws = synchronized(lock) { if (connected) socket else null } ?: return false
        return ws.send(pcm.toByteString())
    }

    /** Optional live vision, authenticated on this socket; no offline or backed-up image queue. */
    fun sendCameraFrame(jpeg: ByteArray): Boolean = synchronized(lock) {
        val ws = socket ?: return false
        if (!cameraPolicy.allow(jpeg.size, connected, ws.queueSize())) return false
        ws.send(ClientMsg.cameraFrame(jpeg))
    }

    /** Permanently stops the client; no further callbacks. */
    fun close() {
        val ws: WebSocket?
        synchronized(lock) {
            if (closed) return
            closed = true
            connected = false
            retryJob?.cancel(); retryJob = null
            ws = socket; socket = null
        }
        ws?.close(1000, null)
    }

    override fun toString(): String = "GatewayClient"

    // Caller holds [lock].
    private fun startLocked() {
        if (socket != null) return
        retryJob?.cancel(); retryJob = null
        val request = Request.Builder().url(url).build()
        // Events carry their socket; those from replaced sockets are ignored. Callbacks that race
        // ahead of this assignment block on [lock] until it is done.
        socket = http.newWebSocket(request, Conn())
    }

    private fun scheduleRetryLocked() {
        if (closed || dead || waiting) return
        val wait = backoff.next()
        retryJob = scope.launch {
            delay(wait)
            synchronized(lock) {
                retryJob = null
                if (closed || dead || waiting) return@launch
                startLocked()
            }
        }
    }

    private inner class Conn() : WebSocketListener() {
        private fun isCurrent(ws: WebSocket) = synchronized(lock) { !closed && socket === ws }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent(webSocket)) return
            if (!webSocket.send(ClientMsg.hello(token))) {
                webSocket.cancel()
                ended(webSocket, null)
                return
            }
            synchronized(lock) {
                if (closed || socket !== webSocket) return
                connected = true
                backoff.reset()
            }
            listener.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (isCurrent(webSocket)) listener.onMessage(parseServer(text))
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            if (isCurrent(webSocket)) listener.onAudio(bytes.toByteArray())
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
            ended(webSocket, code)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            ended(webSocket, code)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            ended(webSocket, null)
        }
    }

    /** A socket ended (code = close code, or null for a failure). Handled once per socket. */
    private fun ended(ws: WebSocket, code: Int?) {
        val rejection: Int?
        synchronized(lock) {
            if (socket !== ws) return
            socket = null
            connected = false
            if (closed) return
            when (code) {
                4401 -> { dead = true; rejection = 4401 }
                4409 -> { waiting = true; rejection = 4409 }
                else -> { rejection = null; scheduleRetryLocked() }
            }
        }
        if (rejection != null) listener.onRejected(rejection) else listener.onDisconnected()
    }

    companion object {
        fun defaultClient(): OkHttpClient =
            OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).build()
    }
}

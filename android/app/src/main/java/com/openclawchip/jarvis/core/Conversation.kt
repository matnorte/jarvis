package com.openclawchip.jarvis.core

import com.openclawchip.jarvis.audio.PreRoll
import com.openclawchip.jarvis.audio.toPcm16Le
import com.openclawchip.jarvis.protocol.ClientMsg
import com.openclawchip.jarvis.protocol.ServerMsg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.sqrt

/** Side effects requested by [Conversation]; the service implements them (socket, AudioTrack, tones). */
interface Effects {
    fun sendText(json: String)
    fun sendAudio(pcm: ByteArray)
    fun playAudio(pcm24k: ByteArray)
    fun flushPlayback()
    fun chime()
    fun errorTone()
    fun reconnectNow()
}

/**
 * Client turn state machine (spec §3). Pure Kotlin; every input must come from ONE thread.
 * The client's own phase decides when mic audio leaves the phone; server messages only drive
 * display, reply playback and the end of a turn. Timeouts are checked on each mic frame.
 */
class Conversation(private val fx: Effects, private val config: Config, private val now: () -> Long) {

    data class Config(
        val endSilenceMs: Long = 700,
        val noSpeechMs: Long = 6000,
        val maxUtteranceMs: Long = 30000,
        val followUpMs: Long = 6000,
        val bargeInSpeechMs: Long = 300,
        val interruptBySpeech: Boolean = false,
    )

    /** Last 300 ms of mic audio not yet sent; fed in every phase except LISTENING and PAUSED. */
    private val preRoll = PreRoll.forMillis(300)
    private var phase = Phase.WAITING
    private var connected = false
    private var rejected = false     // 4401: wakes and taps no longer reconnect
    private var pendingWake = false  // woke while offline; LISTENING starts on onConnected
    private var dropAudio = false    // we sent interrupt: ignore the old turn until state:thinking
    private var deep = false
    private var gotAudio = false     // reply audio played in this turn
    private var errorPending = false // server error: the next state:idle ends the turn in FOLLOW_UP
    private var serverDone = false   // speaking_done (or idle after error) received for this turn
    private var playbackPending = false // reply audio queued but not yet played out
    private var phaseStart = 0L      // entry time of LISTENING / FOLLOW_UP
    private var speechSeen = false
    private var lastSpeechAt = 0L
    private var speechRun = 0        // consecutive VAD speech frames while SPEAKING
    private var micLevel = 0f
    private var outLevel = 0f
    private var transcript = ""
    private var reply = ""
    private var notice: String? = null

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** One 32 ms mic frame; [isWake]/[isSpeech] are computed by the caller. */
    fun onFrame(frame: ShortArray, isWake: Boolean, isSpeech: Boolean) {
        if (phase == Phase.PAUSED) return
        val t = now()
        micLevel = frameLevel(frame)
        if (phase == Phase.LISTENING) {
            fx.sendAudio(frame.toPcm16Le())
            listen(t, isSpeech)
        } else {
            preRoll.push(frame)
            when (phase) {
                Phase.WAITING, Phase.OFFLINE -> if (isWake) wake()
                Phase.THINKING -> if (isWake) bargeIn(speech = false)
                Phase.SPEAKING -> {
                    speechRun = if (isSpeech && config.interruptBySpeech) speechRun + 1 else 0
                    if (isWake) bargeIn(speech = false)
                    else if (speechRun > 0 && speechRun * FRAME_MS >= config.bargeInSpeechMs) bargeIn(speech = true)
                }
                Phase.FOLLOW_UP -> when {
                    isWake -> wake()
                    isSpeech -> startListening(speech = true)
                    t - phaseStart >= config.followUpMs -> phase = Phase.WAITING
                }
                else -> {}
            }
        }
        publish()
    }

    fun onTap() {
        when (phase) {
            Phase.WAITING, Phase.FOLLOW_UP, Phase.OFFLINE -> wake()
            Phase.THINKING, Phase.SPEAKING -> bargeIn(speech = false)
            Phase.LISTENING, Phase.PAUSED -> return
        }
        publish()
    }

    fun onServer(msg: ServerMsg) {
        if (phase == Phase.PAUSED) return
        if (msg is ServerMsg.State) serverState(msg.state)
        else if (!dropAudio) when (msg) { // while dropping, everything else belongs to the old turn
            is ServerMsg.Transcript -> if (msg.final) transcript = msg.text
            is ServerMsg.ReplyText -> reply += msg.delta
            is ServerMsg.Error -> { notice = msg.message; errorPending = true }
            ServerMsg.SpeakingDone -> if (inTurn()) serverFinished()
            else -> {}
        }
        publish()
    }

    /** Binary reply audio (PCM16 LE 24 kHz). */
    fun onServerAudio(pcm: ByteArray) {
        if (dropAudio || !inTurn()) return
        fx.playAudio(pcm)
        if (phase != Phase.SPEAKING) speechRun = 0
        phase = Phase.SPEAKING
        gotAudio = true
        playbackPending = true
        deep = false
        outLevel = chunkLevel(pcm)
        publish()
    }

    /** AudioTrack's queue has fully played out. Only a turn in progress cares; elsewhere a no-op. */
    fun onPlaybackDone() {
        if (!inTurn()) return
        playbackPending = false
        if (serverDone) enterFollowUp()
        publish()
    }

    fun onConnected() {
        connected = true
        rejected = false
        if (phase != Phase.PAUSED) {
            notice = null
            if (pendingWake) {
                pendingWake = false
                startListening(speech = false) // chime already played on the wake
            } else if (phase == Phase.OFFLINE) {
                phase = Phase.WAITING
            }
        }
        publish()
    }

    fun onDisconnected() = goOffline(null)

    /** Close codes: 4401 token rejected (stop retrying), 4409 replaced by another device. */
    fun onRejected(code: Int) {
        if (code == 4401) rejected = true
        goOffline(
            when (code) {
                4401 -> "Device token rejected"
                4409 -> "Another device took over"
                else -> null
            },
        )
    }

    /** Mic taken (call, audio focus): tell the server to drop the turn, then stay silent until [onResume]. */
    fun onPause() {
        if (phase == Phase.PAUSED) return
        if (phase == Phase.LISTENING || inTurn()) {
            fx.sendText(ClientMsg.interrupt())
            dropAudio = true
            if (inTurn()) fx.flushPlayback()
        }
        phase = Phase.PAUSED
        pendingWake = false
        micLevel = 0f
        outLevel = 0f
        publish()
    }

    fun onResume() {
        if (phase != Phase.PAUSED) return
        preRoll.drain() // nothing from before the pause may be streamed
        phase = if (connected) Phase.WAITING else Phase.OFFLINE
        publish()
    }

    // ----- transitions --------------------------------------------------

    private fun inTurn() = phase == Phase.THINKING || phase == Phase.SPEAKING

    /** Wake word or tap outside a turn (WAITING, FOLLOW_UP, OFFLINE). */
    private fun wake() {
        if (connected) {
            fx.chime()
            startListening(speech = false)
        } else if (!rejected && !pendingWake) {
            pendingWake = true
            fx.reconnectNow()
            fx.chime()
        }
    }

    /** Review Focus 1: exactly one interrupt and a flush before any new audio frame. */
    private fun bargeIn(speech: Boolean) {
        fx.sendText(ClientMsg.interrupt())
        fx.flushPlayback()
        dropAudio = true
        startListening(speech)
    }

    private fun startListening(speech: Boolean) {
        for (f in preRoll.drain()) fx.sendAudio(f.toPcm16Le())
        phase = Phase.LISTENING
        phaseStart = now()
        speechSeen = speech
        lastSpeechAt = phaseStart
        reply = ""
        notice = null
        outLevel = 0f
        deep = false
        gotAudio = false
        errorPending = false
        serverDone = false
        playbackPending = false
        speechRun = 0
    }

    private fun listen(t: Long, isSpeech: Boolean) {
        if (isSpeech) {
            speechSeen = true
            lastSpeechAt = t
        }
        val elapsed = t - phaseStart
        if (speechSeen) {
            if (t - lastSpeechAt >= config.endSilenceMs || elapsed >= config.maxUtteranceMs) {
                fx.sendText(ClientMsg.endUtterance())
                phase = Phase.THINKING
            }
        } else if (elapsed >= config.noSpeechMs || elapsed >= config.maxUtteranceMs) {
            fx.sendText(ClientMsg.interrupt()) // Review Focus 5: never end_utterance without speech
            dropAudio = true
            phase = Phase.WAITING
        }
    }

    private fun serverState(s: String) {
        when (s) {
            "thinking", "deep" -> {
                if (s == "thinking") dropAudio = false // Review Focus 2: the new turn has started
                if (!dropAudio && inTurn()) { // "thinking"/"deep" also follow a filler or ack line
                    phase = Phase.THINKING
                    deep = s == "deep"
                    outLevel = 0f
                }
            }
            "idle" -> when {
                errorPending && inTurn() -> serverFinished()
                phase == Phase.THINKING && !gotAudio -> phase = Phase.WAITING
            }
        }
    }

    /** The server sent its last audio; FOLLOW_UP waits until the phone has also played it out. */
    private fun serverFinished() {
        serverDone = true
        if (playbackPending) phase = Phase.SPEAKING // still audible (e.g. after a "deep" ack line)
        else enterFollowUp() // nothing queued (no audio this turn, or already drained)
    }

    private fun enterFollowUp() {
        phase = Phase.FOLLOW_UP
        phaseStart = now()
        outLevel = 0f
        deep = false
        errorPending = false
    }

    private fun goOffline(newNotice: String?) {
        val tone = connected || pendingWake // once per lost connection or failed wake-reconnect
        connected = false
        pendingWake = false
        dropAudio = false // a new connection is a new server session
        errorPending = false
        serverDone = false
        playbackPending = false
        deep = false
        outLevel = 0f
        if (newNotice != null) notice = newNotice
        if (phase != Phase.PAUSED) {
            if (inTurn()) fx.flushPlayback()
            if (tone) fx.errorTone()
            phase = Phase.OFFLINE
        }
        publish()
    }

    private fun publish() {
        _state.value = snapshot()
    }

    private fun snapshot() = UiState(phase, orbState(), micLevel, outLevel, transcript, reply, connected, notice)

    private fun orbState() = when (phase) {
        Phase.WAITING, Phase.PAUSED -> "idle"
        Phase.LISTENING, Phase.FOLLOW_UP -> "listening"
        Phase.THINKING -> if (deep) "deep" else "thinking"
        Phase.SPEAKING -> "speaking"
        Phase.OFFLINE -> "error"
    }

    private companion object {
        const val FRAME_MS = 32

        fun level(sumSquares: Double, n: Int): Float =
            if (n == 0) 0f else (sqrt(sumSquares / n) / 32768.0 * 4).coerceIn(0.0, 1.0).toFloat()

        fun frameLevel(frame: ShortArray): Float {
            var s = 0.0
            for (v in frame) s += v.toDouble() * v
            return level(s, frame.size)
        }

        fun chunkLevel(pcm: ByteArray): Float {
            val n = pcm.size / 2
            var s = 0.0
            for (i in 0 until n) {
                val v = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort().toDouble()
                s += v * v
            }
            return level(s, n)
        }
    }
}

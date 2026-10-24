package com.openclawchip.jarvis.core

import com.openclawchip.jarvis.protocol.ServerMsg
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    /** Records every effect in call order: "text:<type>", "audio:<frame id>", "play", "flush", ... */
    private class FakeFx : Effects {
        val calls = mutableListOf<String>()
        override fun sendText(json: String) { calls += "text:" + JSONObject(json).getString("type") }
        override fun sendAudio(pcm: ByteArray) {
            calls += "audio:" + ((pcm[1].toInt() shl 8) or (pcm[0].toInt() and 0xFF))
        }
        override fun playAudio(pcm24k: ByteArray) { calls += "play" }
        override fun flushPlayback() { calls += "flush" }
        override fun chime() { calls += "chime" }
        override fun errorTone() { calls += "errorTone" }
        override fun reconnectNow() { calls += "reconnect" }
    }

    private val fx = FakeFx()
    private var clock = 0L
    private var nextId = 1
    private lateinit var conv: Conversation

    private fun start(config: Conversation.Config = Conversation.Config(), connect: Boolean = true) {
        conv = Conversation(fx, config, { clock })
        if (connect) conv.onConnected()
    }

    /** Each frame carries its id as every sample, so sent audio can be traced back to frames. */
    private fun frames(n: Int, wake: Boolean = false, speech: Boolean = false) = repeat(n) {
        clock += 32
        val id = nextId++
        conv.onFrame(ShortArray(512) { id.toShort() }, wake, speech)
    }

    private val phase get() = conv.state.value.phase
    private val calls get() = fx.calls
    private fun audio(ids: IntRange) = ids.map { "audio:$it" }
    private fun reset() = fx.calls.clear()

    private fun toListening() { frames(1, wake = true); reset() }
    private fun toThinking() { toListening(); frames(5, speech = true); frames(22); reset() }
    private fun toSpeaking() {
        toThinking(); conv.onServer(ServerMsg.State("thinking")); conv.onServerAudio(ByteArray(960)); reset()
    }
    private fun toFollowUp() {
        toSpeaking(); conv.onServer(ServerMsg.SpeakingDone); conv.onPlaybackDone(); reset()
    }

    @Test fun wakeSendsPreRollThenLiveFrames() {
        start()
        frames(10) // ids 1..10
        frames(1, wake = true) // id 11
        assertEquals(Phase.LISTENING, phase)
        frames(3) // ids 12..14
        assertEquals(listOf("chime") + audio(2..14), calls)
        assertEquals("listening", conv.state.value.orbState)
    }

    @Test fun managedAudioConfigurationWaitsForIdleAndPreservesCaptionsAndTurnRules() {
        start(); toListening()
        assertTrue(!conv.configure(Conversation.Config(endSilenceMs = 1200)))
        frames(5, speech = true); frames(22)
        assertEquals(Phase.THINKING, phase)
        conv.onServer(ServerMsg.Transcript("previous question", "en", true))
        conv.onServer(ServerMsg.ReplyText("previous answer"))
        conv.onServerAudio(ByteArray(960))
        conv.onServer(ServerMsg.SpeakingDone); conv.onPlaybackDone()
        assertEquals(Phase.FOLLOW_UP, phase)
        assertTrue(!conv.configure(Conversation.Config(endSilenceMs = 1200)))
        frames(188); reset()
        assertTrue(conv.configure(Conversation.Config(endSilenceMs = 1200)))
        assertEquals("previous question", conv.state.value.transcript)
        assertEquals("previous answer", conv.state.value.reply)
        assertTrue(calls.isEmpty())
        toListening(); frames(5, speech = true); frames(22)
        assertEquals(Phase.LISTENING, phase)
        frames(16); assertEquals(Phase.THINKING, phase)
    }

    @Test fun endOfSpeechSendsEndUtterance() {
        start(); toListening()
        frames(20, speech = true)
        frames(21) // 672 ms of silence
        assertTrue("text:end_utterance" !in calls)
        frames(1) // 704 ms
        assertEquals(1, calls.count { it == "text:end_utterance" })
        assertEquals("text:end_utterance", calls.last())
        assertEquals(42, calls.count { it.startsWith("audio:") })
        assertEquals(Phase.THINKING, phase)
        reset(); frames(30, speech = true)
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun noSpeechFor6sSendsInterruptNotEnd() {
        start(); toListening()
        frames(187) // 5984 ms
        assertEquals(Phase.LISTENING, phase)
        frames(1) // 6016 ms
        assertEquals("text:interrupt", calls.last())
        assertEquals(1, calls.count { it == "text:interrupt" })
        assertTrue("text:end_utterance" !in calls)
        assertEquals(Phase.WAITING, phase)
        reset(); frames(10)
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun speechResetsNoSpeechTimerOnlyEndSilenceApplies() {
        start(); toListening()
        frames(100); frames(1, speech = true); frames(100, speech = true)
        frames(21)
        assertEquals(Phase.LISTENING, phase)
        frames(1)
        assertEquals("text:end_utterance", calls.last())
        assertTrue("text:interrupt" !in calls)
    }

    @Test fun utteranceCappedAt30s() {
        start(); toListening()
        frames(937, speech = true) // 29984 ms
        assertTrue("text:end_utterance" !in calls)
        frames(1, speech = true) // 30016 ms
        assertEquals("text:end_utterance", calls.last())
        assertEquals(Phase.THINKING, phase)
    }

    @Test fun audioPlaysAndSpeakingDoneStartsFollowUp() {
        start(); toThinking()
        conv.onServer(ServerMsg.State("thinking"))
        val loud = ByteArray(960) { if (it % 2 == 0) 0 else 0x20 } // 8192 -> level 1.0
        conv.onServerAudio(loud)
        assertEquals(listOf("play"), calls)
        assertEquals(Phase.SPEAKING, phase)
        assertEquals("speaking", conv.state.value.orbState)
        assertEquals(1f, conv.state.value.outLevel, 0.001f)
        conv.onServer(ServerMsg.SpeakingDone)
        assertEquals(Phase.SPEAKING, phase) // AudioTrack still draining
        conv.onPlaybackDone()
        assertEquals(Phase.FOLLOW_UP, phase)
        assertEquals("listening", conv.state.value.orbState)
        assertEquals(0f, conv.state.value.outLevel, 0f)
        assertEquals(listOf("play"), calls)
    }

    @Test fun serverStatesAfterSpeakingDoneKeepFollowUp() {
        start(); toFollowUp()
        conv.onServer(ServerMsg.State("idle"))
        conv.onServer(ServerMsg.State("listening"))
        assertEquals(Phase.FOLLOW_UP, phase)
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun followUpSpeechStreamsWithoutWake() {
        start(); toFollowUp()
        frames(12)
        val first = nextId
        frames(1, speech = true)
        assertEquals(Phase.LISTENING, phase)
        assertEquals(audio(first - 9..first), calls)
        reset(); frames(22)
        assertEquals("text:end_utterance", calls.last()) // trigger frame counted as speech
    }

    @Test fun followUpTimesOutToWaiting() {
        start(); toFollowUp()
        frames(187)
        assertEquals(Phase.FOLLOW_UP, phase)
        frames(1)
        assertEquals(Phase.WAITING, phase)
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun wakeWhileSpeakingInterruptsOnceAndFlushes() {
        start(); toSpeaking()
        frames(3)
        val wakeId = nextId
        frames(1, wake = true)
        frames(1, wake = true) // a second wake while LISTENING does nothing extra
        assertEquals(listOf("text:interrupt", "flush") + audio(wakeId - 3..wakeId + 1), calls)
        assertEquals(Phase.LISTENING, phase)
    }

    @Test fun tapWhileThinkingInterruptsOnceAndFlushes() {
        start(); toThinking()
        conv.onTap(); conv.onTap()
        assertEquals("text:interrupt", calls[0])
        assertEquals("flush", calls[1])
        assertTrue(calls.drop(2).all { it.startsWith("audio:") })
        assertEquals(Phase.LISTENING, phase)
    }

    @Test fun lateAudioAfterInterruptIsDropped() {
        start(); toSpeaking()
        frames(1, wake = true)
        conv.onServerAudio(ByteArray(960))
        frames(5, speech = true); frames(22)
        assertEquals(Phase.THINKING, phase)
        reset()
        conv.onServerAudio(ByteArray(960)) // still in flight from the interrupted reply
        conv.onServer(ServerMsg.ReplyText("stale"))
        conv.onServer(ServerMsg.SpeakingDone)
        assertEquals(emptyList<String>(), calls)
        assertEquals(Phase.THINKING, phase)
        assertEquals("", conv.state.value.reply)
        conv.onServer(ServerMsg.State("thinking"))
        conv.onServerAudio(ByteArray(960))
        assertEquals(listOf("play"), calls)
        assertEquals(Phase.SPEAKING, phase)
    }

    @Test fun bargeInBySpeechOnlyWhenEnabled() {
        start(); toSpeaking()
        frames(20, speech = true)
        assertEquals(Phase.SPEAKING, phase)
        assertEquals(emptyList<String>(), calls)

        start(Conversation.Config(interruptBySpeech = true)); toSpeaking()
        frames(9, speech = true); frames(1); frames(9, speech = true) // never 300 ms in a row
        assertEquals(Phase.SPEAKING, phase)
        assertEquals(emptyList<String>(), calls)
        frames(1, speech = true) // 10th consecutive frame = 320 ms
        assertEquals(Phase.LISTENING, phase)
        assertEquals(listOf("text:interrupt", "flush"), calls.take(2))
        reset(); frames(22)
        assertEquals("text:end_utterance", calls.last())
    }

    @Test fun idleWhileThinkingWithoutAudioGoesWaiting() {
        start(); toThinking()
        conv.onServer(ServerMsg.State("thinking"))
        conv.onServer(ServerMsg.Transcript("", "en", true))
        conv.onServer(ServerMsg.State("idle"))
        assertEquals(Phase.WAITING, phase)
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun serverIdleWhileListeningIsIgnored() {
        start(); toListening()
        conv.onServer(ServerMsg.State("idle"))
        conv.onServer(ServerMsg.State("thinking"))
        assertEquals(Phase.LISTENING, phase)
    }

    @Test fun errorShowsNoticeAndIdleGoesFollowUp() {
        start(); toThinking()
        conv.onServer(ServerMsg.State("thinking"))
        conv.onServer(ServerMsg.Error("stt_failed", "Speech recognition failed"))
        assertEquals("Speech recognition failed", conv.state.value.notice)
        conv.onServer(ServerMsg.State("error"))
        conv.onServerAudio(ByteArray(960))
        conv.onServer(ServerMsg.State("idle"))
        assertEquals(Phase.SPEAKING, phase) // spoken error line still draining
        conv.onPlaybackDone()
        assertEquals(Phase.FOLLOW_UP, phase)
        assertEquals(listOf("play"), calls)
    }

    @Test fun errorWithoutAudioIdleGoesFollowUpImmediately() {
        start(); toThinking()
        conv.onServer(ServerMsg.State("thinking"))
        conv.onServer(ServerMsg.Error("llm_failed", "Brain unavailable"))
        conv.onServer(ServerMsg.State("idle"))
        assertEquals(Phase.FOLLOW_UP, phase)
    }

    @Test fun speakingDoneThenPlaybackDoneStartsFollowUp() {
        start(); toSpeaking()
        conv.onServer(ServerMsg.SpeakingDone)
        assertEquals(Phase.SPEAKING, phase)
        assertEquals("speaking", conv.state.value.orbState)
        conv.onPlaybackDone()
        assertEquals(Phase.FOLLOW_UP, phase)
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun playbackDoneThenSpeakingDone() {
        start(); toSpeaking()
        conv.onPlaybackDone() // queue ran dry between chunks
        assertEquals(Phase.SPEAKING, phase)
        conv.onServerAudio(ByteArray(960)) // more reply arrives: playback pending again
        conv.onPlaybackDone()
        assertEquals(Phase.SPEAKING, phase)
        conv.onServer(ServerMsg.SpeakingDone)
        assertEquals(Phase.FOLLOW_UP, phase)
    }

    @Test fun followUpTimerStartsAtDrainNotAtSpeakingDone() {
        start(); toSpeaking()
        conv.onServer(ServerMsg.SpeakingDone)
        frames(150) // 4.8 s of reply still playing
        assertEquals(Phase.SPEAKING, phase)
        conv.onPlaybackDone()
        frames(187)
        assertEquals(Phase.FOLLOW_UP, phase)
        frames(1)
        assertEquals(Phase.WAITING, phase)
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun speechDuringDrainDoesNotStartFollowUpTurn() {
        start(); toSpeaking()
        conv.onServer(ServerMsg.SpeakingDone)
        frames(20, speech = true) // speaker echo: not a new utterance
        assertEquals(Phase.SPEAKING, phase)
        assertEquals(emptyList<String>(), calls)
        frames(1, wake = true) // barge-in still works during the drain
        assertEquals(listOf("text:interrupt", "flush"), calls.take(2))
        assertEquals(Phase.LISTENING, phase)
    }

    @Test fun noAudioTurnSpeakingDoneGoesFollowUpImmediately() {
        start(); toThinking()
        conv.onServer(ServerMsg.State("thinking"))
        conv.onServer(ServerMsg.SpeakingDone)
        assertEquals(Phase.FOLLOW_UP, phase)
    }

    @Test fun playbackDoneIgnoredOutsideSpeaking() {
        start()
        conv.onPlaybackDone()
        assertEquals(Phase.WAITING, phase)
        toListening()
        conv.onPlaybackDone()
        assertEquals(Phase.LISTENING, phase)
        frames(5, speech = true); frames(22)
        conv.onPlaybackDone()
        assertEquals(Phase.THINKING, phase)
        reset()
        conv.onServer(ServerMsg.SpeakingDone)
        assertEquals(Phase.FOLLOW_UP, phase) // no audio this turn, so no drain needed
        conv.onPlaybackDone()
        assertEquals(Phase.FOLLOW_UP, phase)
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun thinkingAfterSpeakingDoneStillEntersFollowUpWhenPlaybackDrains() {
        start(); toSpeaking()
        conv.onServer(ServerMsg.SpeakingDone)
        conv.onServer(ServerMsg.State("thinking"))
        conv.onPlaybackDone()
        assertEquals(Phase.FOLLOW_UP, phase)
    }

    @Test fun speakingDoneInThinkingAfterAudioWaitsForDrain() {
        start(); toSpeaking()
        conv.onServer(ServerMsg.State("deep")) // ack line sent, display back to THINKING
        conv.onServer(ServerMsg.SpeakingDone)
        assertEquals(Phase.SPEAKING, phase) // the ack line is still audible
        conv.onPlaybackDone()
        assertEquals(Phase.FOLLOW_UP, phase)
    }

    @Test fun transcriptReplacedAndReplyAccumulatesAndClearsOnNewTurn() {
        start(); toThinking()
        conv.onServer(ServerMsg.State("thinking"))
        conv.onServer(ServerMsg.Transcript("partial", "en", false))
        conv.onServer(ServerMsg.Transcript("what time is it", "en", true))
        conv.onServer(ServerMsg.ReplyText("It is "))
        conv.onServer(ServerMsg.ReplyText("noon."))
        assertEquals("what time is it", conv.state.value.transcript)
        assertEquals("It is noon.", conv.state.value.reply)
        conv.onServer(ServerMsg.SpeakingDone)
        frames(1, speech = true)
        assertEquals(Phase.LISTENING, phase) // no reply audio in this turn: FOLLOW_UP was immediate
        assertEquals("", conv.state.value.reply)
        assertEquals("what time is it", conv.state.value.transcript)
    }

    @Test fun micLevelIsScaledRmsOfLatestFrame() {
        start()
        conv.onFrame(ShortArray(512) { 2048 }, false, false)
        assertEquals(0.25f, conv.state.value.micLevel, 0.001f)
        conv.onFrame(ShortArray(512) { 20000 }, false, false)
        assertEquals(1f, conv.state.value.micLevel, 0f)
    }

    @Test fun tapInWaitingStartsTurnWithPreRoll() {
        start()
        frames(3)
        conv.onTap()
        assertEquals(listOf("chime") + audio(1..3), calls)
        assertEquals(Phase.LISTENING, phase)
    }

    @Test fun disconnectMidListeningStopsAndGoesOffline() {
        start(); toListening()
        frames(5, speech = true)
        reset()
        conv.onDisconnected()
        assertEquals(listOf("errorTone"), calls)
        assertEquals(Phase.OFFLINE, phase)
        assertEquals("error", conv.state.value.orbState)
        assertEquals(false, conv.state.value.connected)
        frames(30, speech = true)
        conv.onDisconnected()
        conv.onConnected() // reconnect by backoff: nothing buffered is sent
        assertEquals(listOf("errorTone"), calls)
        assertEquals(Phase.WAITING, phase)
    }

    @Test fun disconnectWhileSpeakingFlushesPlayback() {
        start(); toSpeaking()
        conv.onDisconnected()
        assertEquals(listOf("flush", "errorTone"), calls)
        conv.onServerAudio(ByteArray(960))
        assertEquals(listOf("flush", "errorTone"), calls)
    }

    @Test fun wakeWhileOfflineRequestsReconnect() {
        start(); conv.onDisconnected(); reset()
        frames(1, wake = true)
        assertEquals(listOf("reconnect", "chime"), calls)
        assertEquals(Phase.OFFLINE, phase)
        frames(3, wake = true); conv.onTap()
        frames(12)
        assertEquals(listOf("reconnect", "chime"), calls)
        val last = nextId - 1
        conv.onConnected()
        assertEquals(Phase.LISTENING, phase)
        assertEquals(listOf("reconnect", "chime") + audio(last - 9..last), calls)
        frames(1)
        assertEquals("audio:${last + 1}", calls.last())
    }

    @Test fun pendingWakeFailingPlaysErrorTone() {
        start(); conv.onDisconnected(); reset()
        conv.onTap()
        conv.onDisconnected()
        assertEquals(listOf("reconnect", "chime", "errorTone"), calls)
        conv.onConnected()
        assertEquals(Phase.WAITING, phase)
    }

    @Test fun pauseStopsEverythingAndResumeWaits() {
        start(); toSpeaking()
        conv.onPause()
        assertEquals(listOf("text:interrupt", "flush"), calls)
        assertEquals(Phase.PAUSED, phase)
        assertEquals("idle", conv.state.value.orbState)
        reset()
        frames(5, wake = true); frames(5, speech = true); conv.onTap()
        conv.onServerAudio(ByteArray(960)); conv.onServer(ServerMsg.SpeakingDone)
        conv.onDisconnected(); conv.onConnected()
        assertEquals(emptyList<String>(), calls)
        assertEquals(Phase.PAUSED, phase)
        conv.onResume()
        assertEquals(Phase.WAITING, phase)
        assertEquals(emptyList<String>(), calls)
        val id = nextId
        frames(1, wake = true)
        assertEquals(listOf("chime", "audio:$id"), calls) // nothing from before the pause
    }

    @Test fun resumeWhileDisconnectedGoesOfflineSilently() {
        start(); toListening()
        conv.onPause()
        assertEquals(listOf("text:interrupt"), calls)
        reset()
        conv.onDisconnected()
        conv.onResume()
        assertEquals(Phase.OFFLINE, phase)
        assertEquals(emptyList<String>(), calls)
    }

    @Test fun deepStateShowsDeepOrb() {
        start(); toThinking()
        conv.onServer(ServerMsg.State("thinking"))
        assertEquals("thinking", conv.state.value.orbState)
        conv.onServer(ServerMsg.State("deep"))
        assertEquals("deep", conv.state.value.orbState)
        conv.onServerAudio(ByteArray(960))
        assertEquals("speaking", conv.state.value.orbState)
        conv.onServer(ServerMsg.State("deep")) // after the spoken "ack_deep" line
        assertEquals(Phase.THINKING, phase)
        assertEquals("deep", conv.state.value.orbState)
    }

    @Test fun rejected4401ShowsNoticeAndStopsReconnecting() {
        start()
        conv.onRejected(4401)
        assertEquals(listOf("errorTone"), calls)
        assertEquals(Phase.OFFLINE, phase)
        assertEquals("Device token rejected", conv.state.value.notice)
        reset()
        frames(3, wake = true); conv.onTap()
        assertEquals(emptyList<String>(), calls)
        assertEquals("Device token rejected", conv.state.value.notice)
        assertEquals("error", conv.state.value.orbState)
    }

    @Test fun replaced4409ReconnectsOnNextWake() {
        start()
        conv.onRejected(4409)
        assertEquals("Another device took over", conv.state.value.notice)
        assertEquals(Phase.OFFLINE, phase)
        reset()
        frames(1, wake = true)
        assertEquals(listOf("reconnect", "chime"), calls)
        conv.onConnected()
        assertEquals(Phase.LISTENING, phase)
        assertNull(conv.state.value.notice)
    }
}

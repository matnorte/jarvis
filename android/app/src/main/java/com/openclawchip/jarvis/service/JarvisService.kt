package com.openclawchip.jarvis.service

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.*
import com.openclawchip.jarvis.MainActivity
import com.openclawchip.jarvis.audio.*
import com.openclawchip.jarvis.core.*
import com.openclawchip.jarvis.net.GatewayClient
import com.openclawchip.jarvis.protocol.ServerMsg
import com.openclawchip.jarvis.settings.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

/** All pipeline ownership and Conversation inputs are serialized on jarvis-conversation. */
class JarvisService : Service() {
    private val dispatcher = Executors.newSingleThreadExecutor { r -> Thread(r, "jarvis-conversation") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val frames = FrameInbox()
    private var interruptBySpeech = false
    private var conversation: Conversation? = null
    private var client: GatewayClient? = null
    private var input: AudioIn? = null
    private var output: AudioOut? = null
    private var wake: WakeWord? = null
    private var vad: Vad? = null
    private var collector: Job? = null
    private var retry: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var micEpoch = 0
    private var engineNotice: String? = null
    private var micNotice: String? = null
    private var lastNotification = ""
    @Volatile private var stopping = false
    private lateinit var audioManager: AudioManager
    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        scope.launch {
            if (!stopping && conversation != null && mode != AudioManager.MODE_NORMAL) pauseMic()
        }
    }

    override fun onCreate() {
        super.onCreate()
        owner = this
        createChannel(this)
        audioManager = getSystemService(AudioManager::class.java)
        audioManager.addOnModeChangedListener(mainExecutor, modeListener)
        scope.launch {
            while (isActive) {
                val frame = frames.receive() ?: break
                if (!stopping && frame.epoch == micEpoch) processFrame(frame.samples)
                yield() // Let socket, stop, and focus events run even if capture continuously fills the inbox.
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        // Establish the foreground before any engine/network initialization.
        try { startForeground(NOTIFICATION, notification(this, "Starting Jarvis", running = true)) }
        catch (_: SecurityException) {
            _uiState.value = idle().copy(notice = "Microphone permission required")
            getSystemService(NotificationManager::class.java).notify(SETUP_NOTIFICATION, notification(this, "Microphone permission required", false))
            stopSelf(); return START_NOT_STICKY
        }
        scope.launch {
            if (stopping) return@launch
            if (intent?.action == ACTION_RELOAD) releasePipeline()
            if (conversation == null) startPipeline()
            if (intent?.action == ACTION_TALK) conversation?.onTap()
            publish()
        }
        return START_STICKY
    }

    private fun startPipeline() {
        val settings = Settings.open(this)
        if (!settings.isComplete) {
            _uiState.value = idle().copy(notice = "Set up Jarvis")
            getSystemService(NotificationManager::class.java).notify(SETUP_NOTIFICATION, notification(this, "Set up Jarvis", false))
            stopSelf(); return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            _uiState.value = idle().copy(notice = "Microphone permission required")
            stopSelf(); return
        }
        getSystemService(NotificationManager::class.java).cancel(SETUP_NOTIFICATION)
        try {
            interruptBySpeech = settings.interruptBySpeech
            lateinit var newOutput: AudioOut
            newOutput = AudioOut(this) { focus ->
                scope.launch {
                    if (!stopping && output === newOutput && conversation != null && focus < 0) pauseMic()
                }
            }
            output = newOutput
            vad = SileroVad(this)
            wake = try { SherpaWakeWord(this, settings.wakeSensitivity) }
                catch (_: WakeWordUnavailable) { engineNotice = "Wake word unavailable — tap to talk"; null }
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jarvis:turn").apply { setReferenceCounted(false) }
            val fx = object : Effects {
                override fun sendText(json: String) { client?.sendText(json) }
                override fun sendAudio(pcm: ByteArray) { client?.sendAudio(pcm) }
                override fun playAudio(pcm24k: ByteArray) { output?.write(pcm24k) }
                override fun flushPlayback() { output?.flush() }
                override fun chime() = Tones.chime()
                override fun errorTone() = Tones.error()
                override fun reconnectNow() { client?.reconnectNow() }
            }
            val conv = Conversation(fx, Conversation.Config(endSilenceMs = settings.endSilenceMs,
                interruptBySpeech = settings.interruptBySpeech), SystemClock::elapsedRealtime)
            conversation = conv
            val out = output!!
            out.setOnDrained {
                scope.launch {
                    if (!stopping && conversation === conv && output === out && out.isDrained()) {
                        conv.onPlaybackDone(); publish()
                    }
                }
            }
            lateinit var gateway: GatewayClient
            fun event(block: () -> Unit) { scope.launch {
                if (!stopping && client === gateway && conversation === conv) { block(); publish() }
            } }
            gateway = GatewayClient(settings.serverUrl, settings.deviceToken!!, object : GatewayClient.Listener {
                override fun onConnected() = event { conv.onConnected() }
                override fun onDisconnected() = event { conv.onDisconnected() }
                override fun onMessage(msg: ServerMsg) = event { conv.onServer(msg) }
                override fun onAudio(pcm: ByteArray) = event { conv.onServerAudio(pcm) }
                override fun onRejected(code: Int) = event { conv.onRejected(code) }
            }, scope)
            client = gateway
            collector = scope.launch { conv.state.collect { publish() } }
            gateway.connect()
            startMic()
        } catch (_: Exception) {
            releasePipeline()
            _uiState.value = idle().copy(notice = "Audio unavailable — reopen Jarvis to retry")
            getSystemService(NotificationManager::class.java).notify(SETUP_NOTIFICATION, notification(this, "Audio unavailable — reopen Jarvis to retry", false))
            stopSelf()
        }
    }

    private fun startMic() {
        if (stopping || input != null || conversation == null) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micNotice = "Microphone permission required"; publish(); stopSelf(); return
        }
        if (audioManager.mode != AudioManager.MODE_NORMAL) { pauseMic(); return }
        val epoch = ++micEpoch
        val mic = AudioIn(onError = {
            scope.launch { if (!stopping && epoch == micEpoch) pauseMic() }
        }, onFrame = { frame ->
            frames.offer(epoch, frame)
        })
        input = mic
        try {
            mic.start()
            micNotice = null
            conversation?.onResume()
            publish()
        } catch (_: Exception) { pauseMic() }
    }

    private fun processFrame(frame: ShortArray) {
        val conv = conversation ?: return
        val phase = conv.state.value.phase
        if (phase == Phase.PAUSED) return
        try {
            val isWake = wake?.process(frame) ?: false
            val runVad = phase == Phase.LISTENING || phase == Phase.FOLLOW_UP ||
                (phase == Phase.SPEAKING && interruptBySpeech)
            conv.onFrame(frame, isWake, runVad && (vad?.isSpeech(frame) ?: false))
            publish()
        } catch (_: Exception) { pauseMic() }
    }

    private fun pauseMic() {
        if (stopping || conversation == null) return
        micEpoch++
        conversation?.onPause()
        input?.stop(); input = null
        micNotice = "Microphone unavailable — retrying"
        publish()
        if (retry?.isActive == true) return
        retry = scope.launch {
            while (isActive && !stopping && conversation?.state?.value?.phase == Phase.PAUSED) {
                delay(5000)
                startMic()
            }
        }
    }

    private fun publish() {
        if (stopping || owner !== this) return
        val state = conversation?.state?.value ?: return
        val active = state.phase in setOf(Phase.LISTENING, Phase.THINKING, Phase.SPEAKING)
        wakeLock?.let { lock ->
            if (active && !lock.isHeld) lock.acquire(10 * 60 * 1000L)
            else if (!active && lock.isHeld) lock.release()
        }
        _uiState.value = state.copy(outLevel = if (state.phase == Phase.SPEAKING) output?.level() ?: 0f else 0f,
            notice = state.notice ?: micNotice ?: engineNotice)
        val text = _uiState.value.notice ?: state.phase.name.lowercase().replace('_', ' ')
        if (text != lastNotification) {
            lastNotification = text
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(this, text, true))
        }
    }

    private fun releasePipeline() {
        micEpoch++
        retry?.cancel(); retry = null
        collector?.cancel(); collector = null
        val gateway = client; client = null; gateway?.close()
        input?.stop(); input = null
        wake?.close(); wake = null
        vad?.close(); vad = null
        output?.close(); output = null
        conversation = null
        wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
        engineNotice = null; micNotice = null; lastNotification = ""
    }

    override fun onDestroy() {
        stopping = true
        audioManager.removeOnModeChangedListener(modeListener)
        scope.launch {
            releasePipeline()
            frames.close()
            if (owner === this@JarvisService) {
                _uiState.value = idle().copy(notice = _uiState.value.notice?.takeIf {
                    it == "Set up Jarvis" || it == "Microphone permission required" || it.startsWith("Audio unavailable")
                })
                owner = null
            }
            scope.cancel(); dispatcher.close()
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.openclawchip.jarvis.START"
        const val ACTION_TALK = "com.openclawchip.jarvis.TALK"
        const val ACTION_STOP = "com.openclawchip.jarvis.STOP"
        const val ACTION_RELOAD = "com.openclawchip.jarvis.RELOAD"
        private const val CHANNEL = "jarvis"
        private const val NOTIFICATION = 1
        private const val SETUP_NOTIFICATION = 2
        private fun idle() = UiState(Phase.WAITING, "idle", 0f, 0f, "", "", false, null)
        private val _uiState = MutableStateFlow(idle())
        @Volatile private var owner: JarvisService? = null
        val isRunning: Boolean get() = owner?.let { !it.stopping } == true
        val uiState: StateFlow<UiState> = _uiState.asStateFlow()
        fun createChannel(context: android.content.Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Jarvis", NotificationManager.IMPORTANCE_LOW))
        }
        fun notification(context: android.content.Context, text: String, running: Boolean): Notification {
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val builder = Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Jarvis").setContentText(text).setContentIntent(open).setOnlyAlertOnce(true).setOngoing(running)
            if (running) {
                fun action(name: String, code: Int) = PendingIntent.getService(context, code,
                    Intent(context, JarvisService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                builder.addAction(Notification.Action.Builder(null, "Talk", action(ACTION_TALK, 1)).build())
                    .addAction(Notification.Action.Builder(null, "Stop", action(ACTION_STOP, 2)).build())
            }
            return builder.build()
        }
    }
}

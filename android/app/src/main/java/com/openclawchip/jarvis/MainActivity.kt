package com.openclawchip.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.*
import android.view.*
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.camera.view.PreviewView
import com.openclawchip.jarvis.core.Phase
import com.openclawchip.jarvis.service.JarvisService
import com.openclawchip.jarvis.ui.*
import com.openclawchip.jarvis.ui.UiKit.amber
import com.openclawchip.jarvis.ui.UiKit.background
import com.openclawchip.jarvis.ui.UiKit.border
import com.openclawchip.jarvis.ui.UiKit.surface
import com.openclawchip.jarvis.ui.UiKit.ink
import com.openclawchip.jarvis.ui.UiKit.muted
import com.openclawchip.jarvis.ui.UiKit.mint
import com.openclawchip.jarvis.ui.UiKit.dp
import com.openclawchip.jarvis.ui.UiKit.shape
import com.openclawchip.jarvis.ui.UiKit.label
import com.openclawchip.jarvis.ui.UiKit.button
import com.openclawchip.jarvis.ui.UiKit.column
import com.openclawchip.jarvis.ui.UiKit.row
import com.openclawchip.jarvis.ui.UiKit.space
import com.openclawchip.jarvis.ui.UiKit.update
import com.openclawchip.jarvis.vision.CameraStream
import com.openclawchip.jarvis.settings.Settings
import com.openclawchip.jarvis.settings.ManagedPreferences

class MainActivity : ComponentActivity() {
    private lateinit var web: WebView
    private lateinit var onboarding: Onboarding
    private lateinit var connection: TextView
    private lateinit var phase: TextView
    private lateinit var hint: TextView
    private lateinit var transcript: TextView
    private lateinit var reply: TextView
    private lateinit var notice: TextView
    private lateinit var toggle: Button
    private lateinit var talkButton: Button
    private lateinit var assistant: LinearLayout
    private lateinit var visionScreen: LinearLayout
    private lateinit var preview: PreviewView
    private lateinit var cameraPlaceholder: LinearLayout
    private lateinit var cameraToggle: Button
    private lateinit var cameraStatus: TextView
    private lateinit var cameraHint: TextView
    private lateinit var cameraFlip: Button
    private lateinit var visionTalk: Button
    private lateinit var assistantTab: Button
    private lateinit var visionTab: Button
    private var camera: CameraStream? = null
    private var cameraLink: JarvisService.VisionLink? = null
    private var cameraFront = false
    private var cameraPreferences = ManagedPreferences()
    private var lastManagedRevision = 0L
    private var showingVision = false
    private var bridge: OrbBridge? = null
    private var reloadPending = false
    private val handler = Handler(Looper.getMainLooper())
    private val render = object : Runnable {
        override fun run() {
            val ui = JarvisService.uiState.value
            val running = JarvisService.isRunning
            val managed = JarvisService.managedState.value
            if (running && managed.revision > 0 && managed.revision != lastManagedRevision) {
                stopCamera()
                cameraPreferences = managed.preferences; cameraFront = cameraPreferences.frontCamera
                lastManagedRevision = managed.revision
            }
            cameraToggle.isEnabled = cameraPreferences.visionEnabled
            visionTalk.isEnabled = cameraPreferences.visionEnabled
            cameraFlip.isEnabled = camera != null && cameraPreferences.visionEnabled
            if (!cameraPreferences.visionEnabled) cameraHint.update("Live vision is turned off in Hermes. Change it in the Jarvis tab.")
            bridge?.push(ui)
            connection.update(connectionLabel(ui, running))
            connection.setTextColor(if (ui.connected) mint else muted)
            phase.update(if (!running) "Your assistant, on standby" else when (ui.phase) {
                Phase.WAITING -> "Ready when you are"
                Phase.LISTENING -> "I'm listening"
                Phase.THINKING -> "Thinking it through"
                Phase.SPEAKING -> "Here's what I found"
                Phase.FOLLOW_UP -> "Anything else?"
                Phase.PAUSED -> "Microphone paused"
                Phase.OFFLINE -> "Reconnecting"
            })
            hint.update(if (running) "Say “Jarvis” or tap below to talk" else "Start Jarvis to talk hands-free")
            transcript.update(ui.transcript.ifBlank { "Your next conversation starts here." })
            reply.update(ui.reply); reply.visibility = if (ui.reply.isBlank()) View.GONE else View.VISIBLE
            notice.update(ui.notice.orEmpty()); notice.visibility = if (ui.notice == null) View.GONE else View.VISIBLE
            toggle.update(if (running) "Pause" else "Start")
            talkButton.update(if (ui.phase == Phase.SPEAKING) "Interrupt Jarvis" else "Talk to Jarvis")
            if (camera != null && !running) stopCamera()
            val visual = JarvisService.visionState.value
            if (camera != null) {
                val fresh = visual.lastSentAt > 0 && SystemClock.elapsedRealtime() - visual.lastSentAt < 4000
                cameraStatus.update(if (fresh) "●  LIVE · Shared with Jarvis" else "○  Preview · Waiting for connection")
                cameraStatus.setTextColor(if (fresh) mint else amber)
            }
            handler.postDelayed(this, 34)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cached = Settings.open(this)
        cameraPreferences = runCatching { cached.managedPreferences }.getOrDefault(ManagedPreferences())
        cameraFront = cameraPreferences.frontCamera
        lastManagedRevision = cached.managedRevision
        window.setDecorFitsSystemWindows(false)
        window.statusBarColor = UiKit.background; window.navigationBarColor = UiKit.background
        val root = column().apply { setBackgroundColor(UiKit.background) }
        setContentView(root)
        window.insetsController?.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        val header = row().apply { setPadding(dp(24), dp(18), dp(24), dp(14)) }
        val brand = column().apply {
            addView(label("J A R V I S", 18f, amber, true))
            addView(label("YOUR EVERYDAY INTELLIGENCE", 9f, muted).apply { letterSpacing = .12f })
        }
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        toggle = button("Pause") {
            if (JarvisService.isRunning) { stopCamera(); startService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_STOP)) }
            else { getSharedPreferences("onboarding", MODE_PRIVATE).edit().putBoolean("userPaused", false).apply(); onboarding.advance() }
        }
        toggle.id = R.id.assistant_toggle
        header.addView(toggle, LinearLayout.LayoutParams(dp(82), dp(48))); root.addView(header)
        connection = label("○  Connecting", 12f, muted).apply { setPadding(dp(24), 0, dp(24), dp(8)) }
        root.addView(connection)
        val content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        assistant = makeAssistant(); content.addView(assistant, FrameLayout.LayoutParams(-1, -1))
        visionScreen = makeVision().apply { visibility = View.GONE }
        content.addView(visionScreen, FrameLayout.LayoutParams(-1, -1))
        val nav = row().apply { setPadding(dp(20), dp(12), dp(20), dp(12)); background = shape(surface, 0) }
        assistantTab = button("Assistant") { showVision(false) }.apply { id = R.id.assistant_tab }
        visionTab = button("Vision") { showVision(true) }.apply { id = R.id.vision_tab }
        listOf(assistantTab, visionTab, button("Settings") {
            stopCamera(); startActivityForResult(Intent(this, SettingsActivity::class.java), SETTINGS)
        }).forEach { nav.addView(it, LinearLayout.LayoutParams(0, dp(52), 1f).apply { setMargins(dp(3), 0, dp(3), 0) }) }
        root.addView(nav)
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        onboarding = Onboarding(this) {
            if ((!JarvisService.isRunning && !getSharedPreferences("onboarding", MODE_PRIVATE).getBoolean("userPaused", false)) || reloadPending) {
                val action = if (reloadPending) JarvisService.ACTION_RELOAD else JarvisService.ACTION_START
                reloadPending = false
                startForegroundService(Intent(this, JarvisService::class.java).setAction(action))
            }
        }
        showVision(savedInstanceState?.getBoolean("vision") == true)
        web.loadUrl("file:///android_asset/orb/orb.html?embedded=1")
    }

    private fun makeAssistant() = column().apply {
        phase = label("Ready when you are", 26f, ink, true).apply { gravity = Gravity.CENTER }
        hint = label("Say “Jarvis” or tap below to talk", 13f, muted).apply { gravity = Gravity.CENTER }
        space(16); addView(phase); space(6); addView(hint)
        web = WebView(this@MainActivity).apply {
            setBackgroundColor(UiKit.background)
            settings.javaScriptEnabled = true; settings.allowFileAccess = false
            settings.allowContentAccess = false; settings.blockNetworkLoads = true
            settings.setSupportMultipleWindows(false)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val uri = request.url
                    if (uri.scheme == "file" && uri.path?.startsWith("/android_asset/orb/") == true && !uri.path.orEmpty().contains("..")) return null
                    return WebResourceResponse("text/plain", "UTF-8", java.io.ByteArrayInputStream(byteArrayOf()))
                }
                override fun onPageFinished(view: WebView, url: String) {
                    bridge = OrbBridge({ view.evaluateJavascript(it, null) }, SystemClock::elapsedRealtime)
                }
            }
            contentDescription = "Jarvis orb. Tap to talk or interrupt."
            setOnClickListener { talk() }
            var x = 0f; var y = 0f
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_DOWN) { x = event.x; y = event.y }
                if (event.action == MotionEvent.ACTION_UP && kotlin.math.abs(event.x - x) + kotlin.math.abs(event.y - y) < dp(20)) view.performClick()
                true
            }
        }
        addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        val card = column(18).apply { background = shape(surface, dp(22)) }
        card.addView(label("CONVERSATION", 10f, amber, true).apply { letterSpacing = .12f })
        transcript = label("Your next conversation starts here.", 14f, muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        reply = label("", 16f).apply { maxLines = 4; ellipsize = android.text.TextUtils.TruncateAt.END }
        card.space(8); card.addView(transcript); card.space(6); card.addView(reply)
        addView(card, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(24), 0, dp(24), 0) })
        notice = label("", 12f, amber).apply { gravity = Gravity.CENTER }
        addView(notice, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(24), dp(8), dp(24), 0) })
        talkButton = button("Talk to Jarvis", true) { talk() }
        addView(talkButton, LinearLayout.LayoutParams(-1, dp(56)).apply { setMargins(dp(24), dp(14), dp(24), dp(18)) })
    }

    private fun makeVision() = column(24).apply {
        addView(label("A new point of view", 26f, ink, true)); space(8)
        addView(label("Show Jarvis what you see, then ask a question.", 14f, muted)); space(20)
        val viewfinder = FrameLayout(this@MainActivity).apply { background = shape(surface, dp(24)); clipToOutline = true }
        preview = PreviewView(this@MainActivity).apply {
            id = R.id.camera_preview; implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        viewfinder.addView(preview, FrameLayout.LayoutParams(-1, -1))
        cameraPlaceholder = column(24).apply {
            gravity = Gravity.CENTER
            addView(label("See it. Ask it.", 24f, amber, true)); space(12)
            addView(label("Camera is off\nStart when you're ready to share your view.", 14f, muted).apply { gravity = Gravity.CENTER })
        }
        viewfinder.addView(cameraPlaceholder, FrameLayout.LayoutParams(-1, -1))
        addView(viewfinder, LinearLayout.LayoutParams(-1, 0, 1f))
        cameraStatus = label("○  Camera off", 12f, muted)
        space(14); addView(cameraStatus)
        cameraHint = label("Live frames are shared only while this screen is open. Each question uses a recent frame.", 12f, muted)
        space(6); addView(cameraHint); space(16)
        val controls = row()
        cameraToggle = button("Start camera", true) { if (camera != null) stopCamera() else requestCamera() }.apply { id = R.id.camera_toggle }
        controls.addView(cameraToggle, LinearLayout.LayoutParams(0, dp(54), 1f))
        cameraFlip = button("Flip") { if (camera != null) { stopCamera(); cameraFront = !cameraFront; startCamera() } }
        controls.addView(cameraFlip,
            LinearLayout.LayoutParams(dp(76), dp(54)).apply { leftMargin = dp(10) })
        addView(controls); space(10)
        visionTalk = button("Ask about this") { if (camera == null) requestCamera() else talk() }
        addView(visionTalk, LinearLayout.LayoutParams(-1, dp(52)))
    }

    private fun showVision(show: Boolean) {
        if (!show) stopCamera()
        showingVision = show
        assistant.visibility = if (show) View.GONE else View.VISIBLE
        visionScreen.visibility = if (show) View.VISIBLE else View.GONE
        assistantTab.setTextColor(if (show) muted else amber); visionTab.setTextColor(if (show) amber else muted)
    }
    private fun requestCamera() {
        if (!cameraPreferences.visionEnabled) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA)
        else startCamera()
    }
    private fun startCamera() {
        if (!showingVision || camera != null || !cameraPreferences.visionEnabled) return
        val link = JarvisService.openVision()
        if (link == null) { cameraHint.update("Start Jarvis with the button above, then start the camera."); return }
        cameraLink = link; cameraPlaceholder.visibility = View.GONE; cameraToggle.update("Stop camera")
        cameraHint.update("Ask out loud about objects, text, or the scene in view.")
        camera = CameraStream(this, this, preview, cameraFront, link::send) { message ->
            stopCamera(); cameraHint.update(message)
        }.also { it.start() }
    }
    private fun stopCamera() {
        camera?.close(); camera = null; cameraLink?.close(); cameraLink = null
        if (::cameraPlaceholder.isInitialized) {
            cameraPlaceholder.visibility = View.VISIBLE; cameraToggle.update("Start camera")
            cameraStatus.update("○  Camera off"); cameraStatus.setTextColor(muted)
            cameraHint.update("Live frames are shared only while this screen is open. Each question uses a recent frame.")
        }
    }
    private fun talk() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { onboarding.advance(); return }
        startForegroundService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_TALK))
    }
    override fun onResume() {
        super.onResume(); web.onResume(); web.resumeTimers()
        handler.removeCallbacks(render); handler.post(render); onboarding.advance()
    }
    override fun onPause() { stopCamera(); handler.removeCallbacks(render); web.onPause(); web.pauseTimers(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("vision", showingVision); super.onSaveInstanceState(outState) }
    override fun onDestroy() { stopCamera(); handler.removeCallbacks(render); bridge = null; web.destroy(); super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Onboarding.PERMISSIONS) onboarding.onPermissionsResult()
        if (requestCode == CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startCamera()
        else if (requestCode == CAMERA) cameraHint.update("Camera access is off. Allow it in Android app settings to use Vision.")
    }
    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SETTINGS && resultCode == RESULT_OK) reloadPending = true
    }
    companion object { private const val SETTINGS = 42; private const val CAMERA = 43 }
}

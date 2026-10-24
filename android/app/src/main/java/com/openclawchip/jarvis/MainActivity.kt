package com.openclawchip.jarvis

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import com.openclawchip.jarvis.service.JarvisService
import com.openclawchip.jarvis.ui.Onboarding
import com.openclawchip.jarvis.ui.OrbBridge
import com.openclawchip.jarvis.ui.SettingsActivity

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var onboarding: Onboarding
    private lateinit var status: TextView
    private lateinit var captions: TextView
    private lateinit var toggle: Button
    private var bridge: OrbBridge? = null
    private var reloadPending = false
    private val handler = Handler(Looper.getMainLooper())
    private val render = object : Runnable {
        override fun run() {
            val ui = JarvisService.uiState.value
            bridge?.push(ui)
            status.text = "${if (ui.connected) "●" else "○"}  ${ui.phase.name.lowercase().replace('_', ' ')}"
            status.setTextColor(if (ui.connected) Color.rgb(255, 194, 84) else Color.GRAY)
            captions.text = listOfNotNull(ui.transcript.takeIf { it.isNotBlank() }, ui.reply.takeIf { it.isNotBlank() }, ui.notice).joinToString("\n\n")
            toggle.text = if (JarvisService.isRunning) "Stop" else "Start"
            handler.postDelayed(this, 34)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setDecorFitsSystemWindows(false)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        setContentView(root)
        window.insetsController?.hide(WindowInsets.Type.statusBars())
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false // android_asset remains available without general filesystem access.
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
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
            var downX = 0f; var downY = 0f
            setOnTouchListener { view, event ->
                if (event.action == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y }
                if (event.action == MotionEvent.ACTION_UP && kotlin.math.abs(event.x - downX) + kotlin.math.abs(event.y - downY) < 30) view.performClick()
                true
            }
        }
        root.addView(web, FrameLayout.LayoutParams(-1, -1))
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(20, 0, 20, 0) }
        status = TextView(this).apply { textSize = 16f; contentDescription = "Connection and conversation state" }
        top.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        toggle = Button(this).apply { setOnClickListener {
            if (JarvisService.isRunning) startService(Intent(this@MainActivity, JarvisService::class.java).setAction(JarvisService.ACTION_STOP))
            else onboarding.advance()
        } }
        top.addView(toggle)
        top.addView(Button(this).apply { text = "⚙"; contentDescription = "Settings"; setOnClickListener {
            startActivityForResult(Intent(this@MainActivity, SettingsActivity::class.java), SETTINGS)
        } })
        root.addView(top, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        captions = TextView(this).apply {
            textSize = 16f; setTextColor(Color.rgb(255, 210, 122)); setPadding(24, 12, 24, 24)
            setBackgroundColor(0xb0030303.toInt()); maxLines = 9
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        root.addView(captions, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            top.setPadding(20 + bars.left, bars.top, 20 + bars.right, 0)
            captions.setPadding(24, 12, 24, 24 + bars.bottom)
            insets
        }
        onboarding = Onboarding(this) {
            if (!JarvisService.isRunning || reloadPending) {
                val action = if (reloadPending) JarvisService.ACTION_RELOAD else JarvisService.ACTION_START
                reloadPending = false
                startForegroundService(Intent(this, JarvisService::class.java).setAction(action))
            }
        }
        web.loadUrl("file:///android_asset/orb/orb.html?embedded=1")
    }

    private fun talk() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { onboarding.advance(); return }
        startForegroundService(Intent(this, JarvisService::class.java).setAction(JarvisService.ACTION_TALK))
    }
    override fun onResume() {
        super.onResume(); web.onResume(); web.resumeTimers()
        handler.removeCallbacks(render); handler.post(render)
        onboarding.advance()
    }
    override fun onPause() {
        handler.removeCallbacks(render); web.onPause(); web.pauseTimers(); super.onPause()
    }
    override fun onDestroy() { handler.removeCallbacks(render); bridge = null; web.destroy(); super.onDestroy() }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == Onboarding.PERMISSIONS) onboarding.onPermissionsResult()
    }
    @Deprecated("Platform result API used by this plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SETTINGS && resultCode == RESULT_OK) reloadPending = true
    }
    companion object { private const val SETTINGS = 42 }
}

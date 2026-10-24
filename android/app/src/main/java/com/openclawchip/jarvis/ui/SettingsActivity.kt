package com.openclawchip.jarvis.ui

import android.os.*
import android.view.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModelProvider
import com.openclawchip.jarvis.R
import com.openclawchip.jarvis.settings.PairingState
import com.openclawchip.jarvis.settings.Settings
import com.openclawchip.jarvis.ui.UiKit.amber
import com.openclawchip.jarvis.ui.UiKit.ink
import com.openclawchip.jarvis.ui.UiKit.muted
import com.openclawchip.jarvis.ui.UiKit.surface
import com.openclawchip.jarvis.ui.UiKit.dp
import com.openclawchip.jarvis.ui.UiKit.label
import com.openclawchip.jarvis.ui.UiKit.button
import com.openclawchip.jarvis.ui.UiKit.column
import com.openclawchip.jarvis.ui.UiKit.shape
import com.openclawchip.jarvis.ui.UiKit.space
import com.openclawchip.jarvis.ui.UiKit.update

/** Pair/status only. Every editable Jarvis preference now belongs to Hermes's native dashboard. */
class SettingsActivity : ComponentActivity() {
    private lateinit var model: PairingViewModel
    private lateinit var settings: Settings
    private lateinit var status: TextView
    private lateinit var requestId: TextView
    private lateinit var detail: TextView
    private lateinit var action: Button
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private val render = object : Runnable {
        override fun run() {
            val state = model.flow.state.value
            if (state == PairingState.Paired) { setResult(RESULT_OK); finish(); return }
            requestId.visibility = if (state is PairingState.Waiting) View.VISIBLE else View.GONE
            status.update(when (state) {
                PairingState.Idle -> if (settings.managedRevision > 0) "Managed in Hermes" else "Ready to pair"
                PairingState.Connecting -> "Requesting approval"
                is PairingState.Waiting -> "Approve this phone in Hermes"
                PairingState.Expired -> "Request expired or declined"
                is PairingState.Error -> "Pairing unavailable"
                PairingState.Paired -> "Phone approved"
            })
            detail.update(when (state) {
                PairingState.Idle -> "On your computer, open Hermes → Jarvis. Start pairing here, then approve the matching request there."
                PairingState.Connecting -> "Connecting securely to your Jarvis server…"
                is PairingState.Waiting -> {
                    requestId.update(state.id.uppercase())
                    "Match this identifier in the Jarvis tab. Expires in ${maxOf(0, (state.deadlineMs - SystemClock.elapsedRealtime()) / 1000)} seconds."
                }
                PairingState.Expired -> "Start a new request when you're ready. No credential was installed."
                is PairingState.Error -> state.message
                PairingState.Paired -> "Your encrypted connection is ready."
            })
            action.update(if (state is PairingState.Waiting || state == PairingState.Connecting) "Cancel pairing" else "Pair with Hermes")
            if (resumed && !isFinishing) handler.postDelayed(this, 500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setDecorFitsSystemWindows(false)
        settings = Settings.open(this)
        model = ViewModelProvider(this, PairingViewModel.Factory(this))[PairingViewModel::class.java]
        val scroll = ScrollView(this).apply { setBackgroundColor(UiKit.background); isFillViewport = true }
        val form = column(24)
        scroll.addView(form); setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        form.addView(button("‹  Back") { finish() }, LinearLayout.LayoutParams(dp(100), dp(48)))
        form.space(26); form.addView(label("J A R V I S", 12f, amber, true)); form.space(10)
        form.addView(label("One home for your assistant", 30f, ink, true)); form.space(12)
        form.addView(label("All Jarvis configuration lives in Hermes Agent. Your phone handles pairing, permissions and your live conversations.", 15f, muted))
        form.space(28)
        val pair = column(20).apply { background = shape(surface, dp(24)) }
        status = label("Ready to pair", 21f, ink, true).apply { id = R.id.managed_status; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        pair.addView(status); pair.space(16)
        requestId = label("", 28f, amber, true).apply {
            id = R.id.pair_request; typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = .16f; visibility = View.GONE
        }
        pair.addView(requestId)
        detail = label("", 14f, muted)
        pair.space(12); pair.addView(detail); pair.space(22)
        action = button("Pair with Hermes", true) {
            when (model.flow.state.value) {
                is PairingState.Waiting, PairingState.Connecting -> model.flow.cancel()
                else -> model.flow.start()
            }
            handler.removeCallbacks(render); handler.post(render)
        }.apply { id = R.id.pair_start }
        pair.addView(action, LinearLayout.LayoutParams(-1, dp(54)))
        form.addView(pair); form.space(26)
        form.addView(label("Configured on your computer", 18f, ink, true)); form.space(10)
        form.addView(label("Voice & models · Wake & listening\nVision preferences · Connection & credentials", 14f, muted))
        form.space(22)
        form.addView(label("Camera and microphone permissions still require your approval in Android. Pairing never turns on the camera.", 13f, muted))
        form.space(18)
        form.addView(label("Your device credential stays encrypted on this phone and is never displayed.", 12f, muted))
    }
    override fun onResume() {
        super.onResume(); resumed = true; model.flow.resume()
        handler.removeCallbacks(render); handler.post(render)
    }
    override fun onPause() {
        resumed = false; handler.removeCallbacks(render); model.flow.pause(); super.onPause()
    }
}

package com.openclawchip.jarvis.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.*
import android.widget.*
import com.openclawchip.jarvis.R
import com.openclawchip.jarvis.settings.Settings
import com.openclawchip.jarvis.ui.UiKit.amber
import com.openclawchip.jarvis.ui.UiKit.background
import com.openclawchip.jarvis.ui.UiKit.border
import com.openclawchip.jarvis.ui.UiKit.ink
import com.openclawchip.jarvis.ui.UiKit.muted
import com.openclawchip.jarvis.ui.UiKit.surface
import com.openclawchip.jarvis.ui.UiKit.dp
import com.openclawchip.jarvis.ui.UiKit.label
import com.openclawchip.jarvis.ui.UiKit.button
import com.openclawchip.jarvis.ui.UiKit.column
import com.openclawchip.jarvis.ui.UiKit.shape
import com.openclawchip.jarvis.ui.UiKit.space

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setDecorFitsSystemWindows(false)
        val settings = Settings.open(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(UiKit.background); isFillViewport = true }
        val form = column(24)
        scroll.addView(form); setContentView(scroll)
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            scroll.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        form.addView(button("‹  Back") { finish() }, LinearLayout.LayoutParams(dp(100), dp(48)))
        form.space(26)
        form.addView(label("J A R V I S", 12f, amber, true))
        form.space(10)
        form.addView(label(if (settings.isComplete) "Make it yours" else "Meet your new assistant", 30f, ink, true))
        form.space(10)
        form.addView(label(if (settings.isComplete) "Your connection, your voice, your preferences."
            else "Connect to your Jarvis server to get started. Wake-word detection works offline, with no extra account or key.", 15f, muted))
        fun section(title: String, description: String): LinearLayout {
            form.space(26); form.addView(label(title, 19f, ink, true)); form.space(6)
            form.addView(label(description, 13f, muted)); form.space(14)
            return column(18).apply { background = shape(surface, dp(22)); form.addView(this) }
        }
        fun LinearLayout.field(title: String, value: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
            addView(label(title, 13f, muted)); space(6)
            val input = EditText(this@SettingsActivity).apply {
                setText(value); textSize = 15f; setTextColor(ink); setHintTextColor(muted)
                inputType = type; isSingleLine = true; minHeight = dp(52)
                setPadding(dp(14), dp(10), dp(14), dp(10)); background = shape(UiKit.background, dp(12), border)
            }
            addView(input, LinearLayout.LayoutParams(-1, -2)); space(14)
            return input
        }
        val connection = section("Connection", "Your device token pairs this phone with your server.")
        val url = connection.field("Server address", settings.serverUrl, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val stored = settings.hasSecret("deviceToken")
        val token = connection.field("Device token", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            id = R.id.settings_token; isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            hint = if (stored) "Saved securely on this phone" else "Paste your device token"
            isEnabled = !stored
        }
        if (stored) connection.addView(button("Replace device token") { token.isEnabled = true; token.requestFocus() })
        connection.space(10); connection.addView(label("Stored with Android Keystore encryption. Saved tokens are never displayed here.", 12f, muted))
        val voice = section("Voice & listening", "Say “Jarvis” to begin. You can always tap to talk.")
        val sensitivity = voice.field("Wake sensitivity · 0 to 1", settings.wakeSensitivity.toString(), InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        val silence = voice.field("Pause before sending · milliseconds", settings.endSilenceMs.toString(), InputType.TYPE_CLASS_NUMBER)
        fun LinearLayout.option(title: String, value: Boolean) = Switch(this@SettingsActivity).apply {
            text = title; textSize = 14f; setTextColor(ink); isChecked = value
            minHeight = dp(56); setPadding(0, dp(6), 0, dp(6)); addView(this)
        }
        val interrupt = voice.option("Interrupt Jarvis by speaking", settings.interruptBySpeech)
        val startup = section("Stay available", "Keep hands-free access within reach.")
        val boot = startup.option("Show a reminder after reboot", settings.startOnBoot)
        startup.space(8); startup.addView(label("Android requires you to tap the startup notification before Jarvis can use the microphone.", 12f, muted))
        val error = label("", 13f, amber).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        form.space(16); form.addView(error); form.space(8)
        form.addView(button("Save & connect", true) {
            try {
                val wake = sensitivity.text.toString().toFloat()
                val end = silence.text.toString().toLong()
                require(wake in 0f..1f && end in 100L..5000L)
                require(settings.isComplete || token.text.isNotBlank())
                settings.serverUrl = url.text.toString().trim()
                settings.wakeSensitivity = wake; settings.endSilenceMs = end
                settings.interruptBySpeech = interrupt.isChecked; settings.startOnBoot = boot.isChecked
                if (token.text.isNotBlank()) settings.deviceToken = token.text.toString().trim()
                token.text.clear(); setResult(RESULT_OK); finish()
            } catch (_: Exception) { error.text = "Check your WSS address, device token, sensitivity (0–1) and pause (100–5000 ms)." }
        }, LinearLayout.LayoutParams(-1, dp(56)))
        form.space(20)
    }
}

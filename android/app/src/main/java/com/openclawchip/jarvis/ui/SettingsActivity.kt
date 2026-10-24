package com.openclawchip.jarvis.ui

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.openclawchip.jarvis.settings.Settings

class SettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val settings = Settings.open(this)
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(form) })
        fun field(label: String, value: String): EditText {
            form.addView(TextView(this).apply { text = label })
            return EditText(this).apply { setText(value); isSingleLine = true; form.addView(this) }
        }
        val url = field("Server URL", settings.serverUrl)
        fun secret(label: String, name: String): EditText {
            val input = field(label, "").apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                hint = if (settings.hasSecret(name)) "••• saved" else "Required"
                isEnabled = !settings.hasSecret(name)
            }
            form.addView(Button(this).apply { text = "Replace $label"; setOnClickListener { input.isEnabled = true; input.requestFocus() } })
            return input
        }
        val token = secret("Device token", "deviceToken")
        val key = secret("Picovoice key", "picovoiceKey")
        val sensitivity = field("Wake sensitivity (0–1)", settings.wakeSensitivity.toString())
        val silence = field("End silence (100–5000 ms)", settings.endSilenceMs.toString())
        val interrupt = CheckBox(this).apply { text = "Interrupt by speech"; isChecked = settings.interruptBySpeech; form.addView(this) }
        val boot = CheckBox(this).apply { text = "Remind me to start Jarvis after reboot"; isChecked = settings.startOnBoot; form.addView(this) }
        form.addView(TextView(this).apply { text = "Android may require tapping the startup notification before microphone access is allowed." })
        val error = TextView(this).apply { form.addView(this) }
        form.addView(Button(this).apply {
            text = "Save"
            setOnClickListener {
                try {
                    val wake = sensitivity.text.toString().toFloat()
                    val end = silence.text.toString().toLong()
                    require(wake in 0f..1f && end in 100L..5000L)
                    settings.serverUrl = url.text.toString().trim()
                    settings.wakeSensitivity = wake; settings.endSilenceMs = end
                    settings.interruptBySpeech = interrupt.isChecked; settings.startOnBoot = boot.isChecked
                    if (token.text.isNotBlank()) settings.deviceToken = token.text.toString()
                    if (key.text.isNotBlank()) settings.picovoiceKey = key.text.toString()
                    token.text.clear(); key.text.clear()
                    // MainActivity reloads the service with the newly saved configuration on resume.
                    setResult(RESULT_OK); finish()
                } catch (_: Exception) {
                    error.text = "Could not save. Check the WSS URL, sensitivity and silence duration."
                }
            }
        })
    }
}

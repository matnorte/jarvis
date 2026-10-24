package com.openclawchip.jarvis.ui

import com.openclawchip.jarvis.core.Phase
import com.openclawchip.jarvis.core.UiState
import org.json.JSONObject

/** One JS evaluation per 34 ms; the UI periodically pushes the latest state. Clock is milliseconds. */
class OrbBridge(private val js: (String) -> Unit, private val now: () -> Long) {
    private var lastAt: Long? = null
    private var lastState: String? = null
    fun push(ui: UiState) {
        val time = now()
        if (lastAt?.let { time - it < 34 } == true) return
        lastAt = time
        val level = if (ui.phase == Phase.LISTENING || ui.phase == Phase.FOLLOW_UP) ui.micLevel else ui.outLevel
        val safeLevel = if (level.isFinite()) level.coerceIn(0f, 1f) else 0f
        val state = if (lastState != ui.orbState) "JarvisOrb.setState(${JSONObject.quote(ui.orbState)});" else ""
        lastState = ui.orbState
        js("if(window.JarvisOrb){${state}JarvisOrb.setLevel($safeLevel);}")
    }
}

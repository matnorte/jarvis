package com.openclawchip.jarvis.ui

import com.openclawchip.jarvis.core.UiState

/** Terminal socket rejections need action; they must not look like an active retry. */
internal fun connectionLabel(ui: UiState, running: Boolean): String = when {
    !running -> "○  Paused"
    ui.notice == "Device token rejected" -> "○  Token rejected · Open Settings"
    ui.notice == "Another device took over" -> "○  Connected elsewhere"
    ui.connected -> "●  Connected"
    else -> "○  Connecting"
}

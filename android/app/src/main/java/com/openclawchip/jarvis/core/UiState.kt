package com.openclawchip.jarvis.core

enum class Phase { WAITING, LISTENING, THINKING, SPEAKING, FOLLOW_UP, PAUSED, OFFLINE }

/**
 * What the UI renders. [orbState] is the value passed to `JarvisOrb.setState`;
 * [micLevel]/[outLevel] are 0..1 (RMS of the latest mic frame / reply chunk, x4, clipped).
 */
data class UiState(
    val phase: Phase,
    val orbState: String,
    val micLevel: Float,
    val outLevel: Float,
    val transcript: String,
    val reply: String,
    val connected: Boolean,
    val notice: String?,
)

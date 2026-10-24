package com.openclawchip.jarvis.vision

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VisionState(val enabled: Boolean = false, val framesSent: Long = 0, val lastSentAt: Long = 0)

/** Serialized with Conversation. Epochs reject late frames and close callbacks from old previews. */
class LiveVision(private val control: (Boolean) -> Unit, private val send: (ByteArray) -> Boolean, private val now: () -> Long) {
    private var epoch: Long? = null
    private val mutableState = MutableStateFlow(VisionState())
    val state = mutableState.asStateFlow()
    fun start(epoch: Long) {
        this.epoch = epoch; mutableState.value = VisionState(enabled = true); control(true)
    }
    fun frame(epoch: Long, jpeg: ByteArray) {
        if (this.epoch == epoch && send(jpeg)) mutableState.value = state.value.copy(
            framesSent = state.value.framesSent + 1, lastSentAt = now())
    }
    fun stop(epoch: Long) { if (this.epoch == epoch) reset() }
    fun connected() { if (epoch != null) { disconnected(); control(true) } }
    fun disconnected() { mutableState.value = state.value.copy(lastSentAt = 0) }
    fun reset() { if (epoch != null) control(false); epoch = null; mutableState.value = VisionState() }
}

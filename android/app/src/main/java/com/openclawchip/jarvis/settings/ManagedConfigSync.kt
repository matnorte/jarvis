package com.openclawchip.jarvis.settings

import com.openclawchip.jarvis.core.Phase
import com.openclawchip.jarvis.protocol.ClientMsg
import com.openclawchip.jarvis.protocol.ServerMsg

/** Serialized beside Conversation. A configuration acknowledgement also reports safe idle transitions. */
class ManagedConfigSync(
    initialRevision: Long,
    private val apply: (Long, ManagedPreferences) -> Boolean,
    private val send: (String) -> Boolean,
) {
    private var revision = initialRevision
    private var pending: ServerMsg.ManagedConfig? = null
    private var offered = false
    private var failed = false
    private var lastAck: Pair<Long, Boolean>? = null
    fun offer(value: ServerMsg.ManagedConfig) { pending = value; offered = true; failed = false }
    fun disconnected() { pending = null; offered = false; failed = false; lastAck = null }
    fun tick(phase: Phase) {
        val safe = phase == Phase.WAITING || phase == Phase.PAUSED
        val update = pending
        if (safe && update != null && !failed) {
            if (apply(update.revision, update.settings)) { revision = update.revision; pending = null }
            else failed = true
        }
        if (!offered) return
        val acknowledgement = revision to (safe && pending == null)
        if (acknowledgement != lastAck && send(ClientMsg.configAck(acknowledgement.first, acknowledgement.second))) lastAck = acknowledgement
    }
}

package com.openclawchip.jarvis.settings

import com.openclawchip.jarvis.core.Phase
import com.openclawchip.jarvis.protocol.ServerMsg
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ManagedConfigSyncTest {
    @Test fun settingsWaitThroughTheTurnAndFollowUpAndIdleIsReportedAgainAfterEveryTurn() {
        val applied = mutableListOf<Long>()
        val sent = mutableListOf<JSONObject>()
        val sync = ManagedConfigSync(1, { revision, _ -> applied.add(revision); true }, { sent.add(JSONObject(it)); true })
        sync.offer(ServerMsg.ManagedConfig(2, ManagedPreferences()))
        sync.tick(Phase.LISTENING); sync.tick(Phase.THINKING); sync.tick(Phase.FOLLOW_UP)
        assertTrue(applied.isEmpty())
        assertEquals(1, sent.size); assertFalse(sent.last().getBoolean("idle"))
        sync.tick(Phase.WAITING); sync.tick(Phase.WAITING)
        assertEquals(listOf(2L), applied); assertEquals(2, sent.size)
        assertEquals(2L, sent.last().getLong("revision")); assertTrue(sent.last().getBoolean("idle"))
        sync.tick(Phase.LISTENING); sync.tick(Phase.WAITING)
        assertEquals(4, sent.size); assertTrue(sent.last().getBoolean("idle"))
    }

    @Test fun latestOfferWinsAndFailedPersistenceIsNotRetriedOnEveryAudioFrame() {
        var succeeds = false
        val attempts = mutableListOf<Long>()
        val sync = ManagedConfigSync(0, { revision, _ -> attempts.add(revision); succeeds }, { true })
        sync.offer(ServerMsg.ManagedConfig(2, ManagedPreferences()))
        sync.offer(ServerMsg.ManagedConfig(3, ManagedPreferences()))
        sync.tick(Phase.WAITING); sync.tick(Phase.WAITING)
        assertEquals(listOf(3L), attempts)
        succeeds = true
        sync.offer(ServerMsg.ManagedConfig(3, ManagedPreferences()))
        sync.tick(Phase.PAUSED)
        assertEquals(listOf(3L, 3L), attempts)
    }

    @Test fun reconnectRequiresANewOfferAndDroppedAcknowledgementsCanRetry() {
        var connected = false
        var sent = 0
        val sync = ManagedConfigSync(1, { _, _ -> true }, { if (connected) sent++; connected })
        sync.offer(ServerMsg.ManagedConfig(1, ManagedPreferences()))
        sync.tick(Phase.WAITING)
        connected = true; sync.tick(Phase.WAITING)
        assertEquals(1, sent)
        sync.disconnected(); sync.tick(Phase.WAITING)
        assertEquals(1, sent)
        sync.offer(ServerMsg.ManagedConfig(1, ManagedPreferences())); sync.tick(Phase.WAITING)
        assertEquals(2, sent)
    }
}

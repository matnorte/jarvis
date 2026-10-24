package com.openclawchip.jarvis.ui

import com.openclawchip.jarvis.core.Phase
import com.openclawchip.jarvis.core.UiState
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionLabelTest {
    private val offline = UiState(Phase.OFFLINE, "idle", 0f, 0f, "", "", false, null)
    @Test fun terminalRejectionsNeverClaimToBeConnecting() {
        assertEquals("○  Token rejected · Open Settings", connectionLabel(offline.copy(notice = "Device token rejected"), true))
        assertEquals("○  Connected elsewhere", connectionLabel(offline.copy(notice = "Another device took over"), true))
    }
    @Test fun normalConnectedRetryAndExplicitPauseRemainDistinct() {
        assertEquals("○  Connecting", connectionLabel(offline, true))
        assertEquals("●  Connected", connectionLabel(offline.copy(connected = true), true))
        assertEquals("○  Paused", connectionLabel(offline, false))
    }
}

package com.openclawchip.jarvis.ui

import com.openclawchip.jarvis.core.Phase
import com.openclawchip.jarvis.core.UiState
import org.junit.Assert.*
import org.junit.Test

class OrbBridgeTest {
    private var now = 0L
    private val scripts = mutableListOf<String>()
    private val bridge = OrbBridge({ scripts.add(it) }, { now })
    private fun state(phase: Phase = Phase.WAITING, orb: String = "idle") =
        UiState(phase, orb, 0.25f, 0.75f, "", "", true, null)

    @Test fun stateSentOnlyOnChange() {
        bridge.push(state()); now = 34; bridge.push(state())
        now = 68; bridge.push(state(orb = "thinking"))
        assertEquals(2, scripts.count { "setState" in it })
    }
    @Test fun levelThrottledTo30PerSecond() {
        repeat(1000) { now = it.toLong(); bridge.push(state()) }
        assertTrue(scripts.size <= 30)
        assertTrue(scripts.size >= 29)
    }
    @Test fun levelSourceFollowsPhase() {
        bridge.push(state(Phase.LISTENING, "listening"))
        assertTrue(scripts.last().contains("setLevel(0.25)"))
        now = 34; bridge.push(state(Phase.FOLLOW_UP, "listening"))
        assertTrue(scripts.last().contains("setLevel(0.25)"))
        now = 68; bridge.push(state(Phase.SPEAKING, "speaking"))
        assertTrue(scripts.last().contains("setLevel(0.75)"))
    }
    @Test fun stateChangeWithinThrottleIsDeliveredOnNextPush() {
        bridge.push(state())
        now = 1; bridge.push(state(orb = "thinking"))
        assertEquals(1, scripts.size)
        now = 34; bridge.push(state(orb = "thinking"))
        assertTrue(scripts.last().contains("setState(\"thinking\")"))
    }
    @Test fun nonFiniteLevelIsNeverPassedToJavascript() {
        bridge.push(state().copy(outLevel = Float.NaN))
        assertTrue(scripts.last().contains("setLevel(0.0)"))
    }
}

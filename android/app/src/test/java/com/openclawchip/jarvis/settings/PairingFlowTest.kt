package com.openclawchip.jarvis.settings

import com.openclawchip.jarvis.net.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PairingFlowTest {
    private class Api : PairingApi {
        var requests = 0; var claims = 0
        var approved = false
        var wait: CompletableDeferred<Unit>? = null
        override suspend fun request(proof: String): PairingRequest { requests++; return PairingRequest("0123abcd", 180) }
        override suspend fun claim(id: String, proof: String): PairingResult {
            claims++; wait?.await()
            return if (approved) PairingResult.Paired("dummy-device-token", 1, ManagedPreferences()) else PairingResult.Pending
        }
    }
    @Test fun approvalInstallsCredentialWithoutEverPuttingItInUiState() = runTest {
        val api = Api(); var installed = 0
        val flow = PairingFlow(api, this, { testScheduler.currentTime }, { installed++ }, { "x".repeat(43) })
        flow.start(); runCurrent()
        assertTrue(flow.state.value is PairingState.Waiting)
        api.approved = true; advanceTimeBy(2000); runCurrent()
        assertEquals(1, installed); assertEquals(PairingState.Paired, flow.state.value)
        assertFalse(flow.state.value.toString().contains("dummy-device-token"))
    }
    @Test fun pauseAndResumeKeepTheRequestButCancelledClaimsCannotInstallAnything() = runTest {
        val api = Api().apply { wait = CompletableDeferred() }
        var installed = 0
        val flow = PairingFlow(api, this, { testScheduler.currentTime }, { installed++ }, { "x".repeat(43) })
        flow.start(); runCurrent(); flow.pause()
        api.approved = true; api.wait!!.complete(Unit); runCurrent()
        assertEquals(0, installed)
        flow.resume(); runCurrent()
        assertEquals(1, api.requests); assertEquals(1, installed)
        flow.cancel(); assertEquals(PairingState.Idle, flow.state.value)
    }
    @Test fun requestExpiresAndRetryRequiresAnotherExplicitStart() = runTest {
        val api = Api()
        val flow = PairingFlow(api, this, { testScheduler.currentTime }, {}, { "x".repeat(43) })
        flow.start(); runCurrent(); advanceTimeBy(180001); runCurrent()
        assertEquals(PairingState.Expired, flow.state.value)
        flow.resume(); runCurrent(); assertEquals(1, api.requests)
        flow.start(); runCurrent(); assertEquals(2, api.requests)
        flow.cancel()
    }
}

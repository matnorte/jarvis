package com.openclawchip.jarvis.settings

import com.openclawchip.jarvis.net.*
import java.security.SecureRandom
import java.util.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface PairingState {
    data object Idle : PairingState
    data object Connecting : PairingState
    data class Waiting(val id: String, val deadlineMs: Long) : PairingState
    data object Paired : PairingState
    data object Expired : PairingState
    data class Error(val message: String) : PairingState
}

/** UI-dispatcher owner. Only public request identifiers enter the UI state; capabilities stay in phone RAM. */
class PairingFlow(
    private val api: PairingApi,
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val install: (PairingResult.Paired) -> Unit,
    private val proofFactory: () -> String = {
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    },
) {
    private val mutableState = MutableStateFlow<PairingState>(PairingState.Idle)
    val state = mutableState.asStateFlow()
    private var proof: String? = null
    private var request: PairingRequest? = null
    private var deadline = 0L
    private var job: Job? = null
    fun start() {
        if (job?.isActive == true) return
        proof = proofFactory(); request = null
        mutableState.value = PairingState.Connecting
        launch()
    }
    fun pause() { job?.cancel(); job = null }
    fun resume() {
        if (job?.isActive != true && proof != null && (state.value is PairingState.Waiting || state.value == PairingState.Connecting)) launch()
    }
    fun cancel() { pause(); proof = null; request = null; mutableState.value = PairingState.Idle }
    private fun launch() {
        job = scope.launch {
            try {
                val capability = proof ?: return@launch
                var pending = request
                if (pending == null) {
                    pending = api.request(capability)
                    currentCoroutineContext().ensureActive()
                    request = pending
                    deadline = now() + pending.expiresSeconds * 1000L
                }
                mutableState.value = PairingState.Waiting(pending.id, deadline)
                while (now() < deadline) {
                    val result = api.claim(pending.id, capability)
                    currentCoroutineContext().ensureActive()
                    when (result) {
                        PairingResult.Pending -> delay(2000)
                        PairingResult.Expired -> break
                        is PairingResult.Paired -> {
                            install(result)
                            proof = null; request = null
                            mutableState.value = PairingState.Paired
                            return@launch
                        }
                    }
                }
                proof = null; request = null
                mutableState.value = PairingState.Expired
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                currentCoroutineContext().ensureActive()
                proof = null; request = null
                mutableState.value = PairingState.Error("Pairing unavailable. Keep Hermes open and try again.")
            }
        }
    }
}

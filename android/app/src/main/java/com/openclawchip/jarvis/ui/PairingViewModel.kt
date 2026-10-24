package com.openclawchip.jarvis.ui

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.openclawchip.jarvis.net.PairingClient
import com.openclawchip.jarvis.settings.PairingFlow
import com.openclawchip.jarvis.settings.Settings
import kotlinx.coroutines.*

/** Survives rotation; all capability material remains in phone memory until completion/cancellation. */
class PairingViewModel(settings: Settings) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val flow = PairingFlow(PairingClient(settings.serverUrl), scope, SystemClock::elapsedRealtime, {
        settings.completePairing(it.token, it.revision, it.settings)
    })
    override fun onCleared() { flow.cancel(); scope.cancel() }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val application = context.applicationContext
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass == PairingViewModel::class.java)
            @Suppress("UNCHECKED_CAST")
            return PairingViewModel(Settings.open(application)) as T
        }
    }
}

package com.openclawchip.jarvis.service

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.openclawchip.jarvis.MainActivity
import com.openclawchip.jarvis.DeviceSettingsFixture
import com.openclawchip.jarvis.settings.Settings
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test

class ServiceSmokeTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var settingsFixture: DeviceSettingsFixture
    @Before fun prepare() { settingsFixture = DeviceSettingsFixture(context) }
    @After fun cleanup() {
        context.stopService(Intent(context, JarvisService::class.java))
        waitUntil { !JarvisService.isRunning }
        settingsFixture.close()
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }
    private fun launch() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS, "android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND")
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        context.startForegroundService(Intent(context, JarvisService::class.java).setAction(JarvisService.ACTION_START))
    }
    private fun waitUntil(predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue(predicate())
    }
    @Test fun incompleteSettingsStopsWithSetupNotification() {
        context.getSharedPreferences("jarvis", 0).edit().clear().commit()
        launch()
        val notifications = context.getSystemService(NotificationManager::class.java)
        waitUntil { notifications.activeNotifications.any { it.notification.extras.getCharSequence("android.text") == "Set up Jarvis" } }
        assertEquals("Set up Jarvis", JarvisService.uiState.value.notice)
    }
    @Test fun completeSettingsStartsForegroundAndInvalidWakeKeyKeepsTapAvailable() {
        Settings.open(context).apply {
            deviceToken = "test-invalid-token"; picovoiceKey = "test-invalid-key"
            serverUrl = "wss://127.0.0.1:1/ws"
        }
        launch()
        val notifications = context.getSystemService(NotificationManager::class.java)
        waitUntil { notifications.activeNotifications.any {
            it.notification.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE != 0
        } && JarvisService.uiState.value.notice?.contains("Wake word unavailable") == true }
        context.startService(Intent(context, JarvisService::class.java).setAction(JarvisService.ACTION_STOP))
        waitUntil { notifications.activeNotifications.none { it.notification.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE != 0 } }
    }
}

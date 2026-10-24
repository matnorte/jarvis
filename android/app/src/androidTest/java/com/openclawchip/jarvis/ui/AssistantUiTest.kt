package com.openclawchip.jarvis.ui

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.openclawchip.jarvis.DeviceSettingsFixture
import com.openclawchip.jarvis.MainActivity
import com.openclawchip.jarvis.R
import com.openclawchip.jarvis.service.JarvisService
import com.openclawchip.jarvis.settings.Settings
import org.junit.Assert.*
import org.junit.Test

class AssistantUiTest {
    @Test fun explicitPauseSurvivesActivityRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = DeviceSettingsFixture(context)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS, "android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND")
        try {
            Settings.open(context).apply { deviceToken = "test-invalid-token"; serverUrl = "wss://127.0.0.1:1/ws" }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val started = SystemClock.elapsedRealtime() + 15000
                while (!JarvisService.isRunning && SystemClock.elapsedRealtime() < started) Thread.sleep(50)
                assertTrue(JarvisService.isRunning)
                scenario.onActivity { it.findViewById<View>(R.id.assistant_toggle).performClick() }
                val stopped = SystemClock.elapsedRealtime() + 15000
                while (JarvisService.isRunning && SystemClock.elapsedRealtime() < stopped) Thread.sleep(50)
                assertFalse(JarvisService.isRunning)
                scenario.recreate()
                Thread.sleep(1500)
                assertFalse("Resume must respect explicit Pause", JarvisService.isRunning)
            }
        } finally {
            context.stopService(Intent(context, JarvisService::class.java))
            fixture.close(); instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}

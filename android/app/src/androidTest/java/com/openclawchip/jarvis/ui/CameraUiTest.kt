package com.openclawchip.jarvis.ui

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.Button
import androidx.camera.view.PreviewView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.openclawchip.jarvis.DeviceSettingsFixture
import com.openclawchip.jarvis.MainActivity
import com.openclawchip.jarvis.R
import com.openclawchip.jarvis.service.JarvisService
import com.openclawchip.jarvis.settings.Settings
import org.junit.Assert.*
import org.junit.Test

class CameraUiTest {
    @Test fun visionDisabledInHermesCannotStartTheCamera() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = DeviceSettingsFixture(context)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS, "android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND")
        try {
            Settings.open(context).apply {
                deviceToken = "test-invalid-token"
                cacheManaged(1, com.openclawchip.jarvis.settings.ManagedPreferences(serverUrl = "wss://127.0.0.1:1/ws", visionEnabled = false))
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.findViewById<View>(R.id.vision_tab).performClick() }
                var disabled = false
                val deadline = SystemClock.elapsedRealtime() + 3000
                while (!disabled && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { disabled = !it.findViewById<Button>(R.id.camera_toggle).isEnabled }
                    if (!disabled) Thread.sleep(50)
                }
                assertTrue("Hermes's vision preference must gate local capture", disabled)
                assertFalse(JarvisService.visionState.value.enabled)
            }
        } finally {
            context.stopService(Intent(context, JarvisService::class.java)); fixture.close()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test fun visionIsExplicitAndLeavingItStopsCameraContext() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = DeviceSettingsFixture(context)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS,
            "android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND")
        try {
            Settings.open(context).apply { deviceToken = "test-invalid-token"; serverUrl = "wss://127.0.0.1:1/ws" }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertFalse(JarvisService.visionState.value.enabled)
                    activity.findViewById<View>(R.id.vision_tab).performClick()
                    assertFalse(JarvisService.visionState.value.enabled)
                    activity.findViewById<Button>(R.id.camera_toggle).performClick()
                }
                val deadline = SystemClock.elapsedRealtime() + 25000
                var streaming = false
                while (!streaming && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        streaming = activity.findViewById<PreviewView>(R.id.camera_preview).previewStreamState.value == PreviewView.StreamState.STREAMING
                    }
                    if (!streaming) Thread.sleep(100)
                }
                assertTrue("camera preview did not stream", streaming)
                val announced = SystemClock.elapsedRealtime() + 15000
                while (!JarvisService.visionState.value.enabled && SystemClock.elapsedRealtime() < announced) Thread.sleep(50)
                assertTrue("Camera did not announce after service initialization: ${JarvisService.uiState.value.phase}, ${JarvisService.uiState.value.notice}", JarvisService.visionState.value.enabled)
                Thread.sleep(500)
                val screenshot = instrumentation.uiAutomation.takeScreenshot()
                try { java.io.File(context.getExternalFilesDir(null), "vision-emulator.png").outputStream().use {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                } } finally { screenshot.recycle() }
                scenario.onActivity { activity -> activity.findViewById<View>(R.id.assistant_tab).performClick() }
                val stopped = SystemClock.elapsedRealtime() + 3000
                while (JarvisService.visionState.value.enabled && SystemClock.elapsedRealtime() < stopped) Thread.sleep(50)
                assertFalse(JarvisService.visionState.value.enabled)
                scenario.onActivity { activity ->
                    activity.findViewById<View>(R.id.vision_tab).performClick()
                    activity.findViewById<Button>(R.id.camera_toggle).performClick()
                }
                val restarted = SystemClock.elapsedRealtime() + 5000
                while (!JarvisService.visionState.value.enabled && SystemClock.elapsedRealtime() < restarted) Thread.sleep(50)
                assertTrue(JarvisService.visionState.value.enabled)
                scenario.moveToState(Lifecycle.State.CREATED)
                val background = SystemClock.elapsedRealtime() + 3000
                while (JarvisService.visionState.value.enabled && SystemClock.elapsedRealtime() < background) Thread.sleep(50)
                assertFalse("Background must clear camera context", JarvisService.visionState.value.enabled)
            }
        } finally {
            context.stopService(Intent(context, JarvisService::class.java))
            fixture.close()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}

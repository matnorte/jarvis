package com.openclawchip.jarvis.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.openclawchip.jarvis.DeviceSettingsFixture
import com.openclawchip.jarvis.MainActivity
import com.openclawchip.jarvis.service.JarvisService
import com.openclawchip.jarvis.settings.Settings
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class OrbWebViewTest {
    @Test fun packagedOrbRendersOfflineWithHiddenDemoAndWorkingBridge() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val fixture = DeviceSettingsFixture(context)
        instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS, "android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND")
        try {
            Settings.open(context).apply {
                deviceToken = "test-invalid-token"
                serverUrl = "wss://127.0.0.1:1/ws"
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var web: WebView? = null
                val errors = CopyOnWriteArrayList<String>()
                fun find(view: View): WebView? {
                    if (view is WebView) return view
                    if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                    return null
                }
                scenario.onActivity { activity ->
                    web = find(activity.window.decorView)
                    assertNotNull(web)
                    assertFalse(web!!.settings.allowFileAccess)
                    assertTrue(web!!.settings.blockNetworkLoads)
                    web!!.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) errors.add(message.message())
                            return true
                        }
                    }
                }
                val deadline = SystemClock.elapsedRealtime() + 60000
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    val checked = CountDownLatch(1)
                    scenario.onActivity {
                        web!!.evaluateJavascript("typeof window.JarvisOrb === 'object' && document.querySelectorAll('canvas').length > 0 && getComputedStyle(document.getElementById('ui')).display === 'none' && getComputedStyle(document.getElementById('label')).display === 'none'") {
                            ready = it == "true"; checked.countDown()
                        }
                    }
                    assertTrue("WebView JS did not respond while initializing WebGL", checked.await(20, TimeUnit.SECONDS))
                    if (!ready) Thread.sleep(100)
                }
                assertTrue("orb did not initialize: $errors", ready)
                assertTrue("orb console errors: $errors", errors.isEmpty())
                val applied = CountDownLatch(1)
                scenario.onActivity {
                    web!!.evaluateJavascript("JarvisOrb.setState('listening');JarvisOrb.setLevel(0.5);document.getElementById('label').textContent") {
                        assertEquals("\"listening\"", it); applied.countDown()
                    }
                }
                assertTrue(applied.await(20, TimeUnit.SECONDS))
                val bitmap = instrumentation.uiAutomation.takeScreenshot()
                try {
                    File(context.getExternalFilesDir(null), "orb-emulator.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                } finally { bitmap.recycle() }
            }
        } finally {
            context.stopService(Intent(context, JarvisService::class.java))
            val deadline = SystemClock.elapsedRealtime() + 5000
            while (JarvisService.isRunning && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            fixture.close()
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }
}

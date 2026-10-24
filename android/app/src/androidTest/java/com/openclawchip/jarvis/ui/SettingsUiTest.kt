package com.openclawchip.jarvis.ui

import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.openclawchip.jarvis.DeviceSettingsFixture
import com.openclawchip.jarvis.R
import com.openclawchip.jarvis.settings.Settings
import org.junit.Assert.*
import org.junit.Test

class SettingsUiTest {
    @Test fun phoneHasOnlyPairingAndReadOnlyManagedStatusWithoutCredentialEditors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = DeviceSettingsFixture(context)
        try {
            Settings.open(context).deviceToken = "test-invalid-token"
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertNull(activity.findViewById<EditText>(R.id.settings_token))
                    assertNotNull(activity.findViewById<android.widget.Button>(R.id.pair_start))
                    assertEquals(android.view.View.GONE, activity.findViewById<android.view.View>(R.id.pair_request).visibility)
                    assertTrue(activity.window.attributes.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0)
                    // Draw only this synthetic fixture's layout for design QA; production keeps FLAG_SECURE.
                    val view = activity.window.decorView
                    val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                    try {
                        view.draw(android.graphics.Canvas(image))
                        java.io.File(context.getExternalFilesDir(null), "settings-emulator.png").outputStream().use {
                            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                    } finally { image.recycle() }
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertEquals(android.view.View.GONE, activity.findViewById<android.view.View>(R.id.pair_request).visibility)
                }
            }
        } finally { fixture.close() }
    }
}

package com.openclawchip.jarvis.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import com.openclawchip.jarvis.settings.Settings

/** Activity-owned setup flow; re-enter advance() after returning from permissions/settings. */
class Onboarding(private val activity: Activity, private val onReady: () -> Unit) {
    private var busy = false
    private var openedSettings = false
    private var askedPermissions = false
    fun advance() {
        if (busy) return
        if (!Settings.open(activity).isComplete) {
            if (!openedSettings) {
                openedSettings = true
                activity.startActivity(Intent(activity, SettingsActivity::class.java))
            }
            return
        }
        val missing = buildList {
            if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty() && !askedPermissions) {
            busy = true
            AlertDialog.Builder(activity).setTitle("Talk hands-free with Jarvis")
                .setMessage("Microphone access detects Jarvis and captures your questions. Notifications show when Jarvis is running and let you talk or stop.")
                .setPositiveButton("Continue") { _, _ -> askedPermissions = true; activity.requestPermissions(missing.toTypedArray(), PERMISSIONS) }
                .setNegativeButton("Later") { _, _ -> busy = false }.show()
            return
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val prefs = activity.getSharedPreferences("onboarding", Activity.MODE_PRIVATE)
        val power = activity.getSystemService(PowerManager::class.java)
        if (!prefs.getBoolean("batteryOffered", false) && !power.isIgnoringBatteryOptimizations(activity.packageName)) {
            busy = true
            prefs.edit().putBoolean("batteryOffered", true).apply()
            AlertDialog.Builder(activity).setTitle("Keep Jarvis available")
                .setMessage("Optionally allow unrestricted battery use for wake-word detection with the screen off.")
                .setPositiveButton("Allow") { _, _ ->
                    busy = false
                    try { activity.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${activity.packageName}"))) }
                    catch (_: Exception) { advance() }
                }.setNegativeButton("Skip") { _, _ -> busy = false; advance() }.show()
            return
        }
        onReady()
    }
    fun onPermissionsResult() { busy = false; advance() }
    companion object { const val PERMISSIONS = 41 }
}

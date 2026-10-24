package com.openclawchip.jarvis.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.NotificationManager
import com.openclawchip.jarvis.settings.Settings

/** Microphone foreground services cannot safely start from BOOT_COMPLETED; require a visible launch. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = Settings.open(context)
        if (!settings.startOnBoot || !settings.isComplete) return
        JarvisService.createChannel(context)
        context.getSystemService(NotificationManager::class.java).notify(3,
            JarvisService.notification(context, "Tap to start Jarvis after reboot", false))
    }
}

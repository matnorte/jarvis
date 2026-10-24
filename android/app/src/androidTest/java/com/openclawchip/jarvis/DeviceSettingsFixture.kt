package com.openclawchip.jarvis

import android.content.Context

/** Preserve any installed app configuration. Snapshots contain encrypted values only, never logged. */
class DeviceSettingsFixture(private val context: Context) : AutoCloseable {
    private val snapshots = listOf("jarvis", "onboarding").associateWith { name ->
        context.getSharedPreferences(name, 0).all.toMap()
    }
    init { context.getSharedPreferences("onboarding", 0).edit().putBoolean("batteryOffered", true).commit() }
    override fun close() {
        snapshots.forEach { (name, values) ->
            val edit = context.getSharedPreferences(name, 0).edit().clear()
            values.forEach { (key, value) -> when (value) {
                is String -> edit.putString(key, value)
                is Boolean -> edit.putBoolean(key, value)
                is Float -> edit.putFloat(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
            } }
            check(edit.commit())
        }
    }
}

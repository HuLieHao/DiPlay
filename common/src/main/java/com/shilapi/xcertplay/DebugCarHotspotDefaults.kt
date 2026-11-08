package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.os.Build
import java.util.Properties

/** Optional local car-build input; credentials never belong in source or diagnostics. */
internal object DebugCarHotspotDefaults {
    private const val APPLIED = "debug_car_hotspot_provisioned_v1"

    @Synchronized
    fun provision(context: Context, prefs: SharedPreferences) {
        if (context.packageName != "com.shihab.diplay.hudtest" ||
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 ||
            !Build.MODEL.equals("FF-5000", true) ||
            !Build.HARDWARE.equals("ac8227l", true) || Build.VERSION.SDK_INT != 27 ||
            prefs.getBoolean(APPLIED, false)
        ) return
        val properties = runCatching {
            context.assets.open("debug-car-hotspot.properties").use { input ->
                Properties().apply { load(input) }
            }
        }.getOrNull() ?: return
        val ssid = properties.getProperty("ssid") ?: return
        val passphrase = properties.getProperty("passphrase") ?: return
        if (ssid.isBlank() || ssid.toByteArray(Charsets.UTF_8).size > 32 || passphrase.length !in 8..63) return
        // Atomic one-time migration also replaces an old debug phone-hotspot configuration.
        // Subsequent user edits win, including across application updates.
        prefs.edit()
            .putString("manual_hotspot_ssid", ssid)
            .putString("manual_hotspot_passphrase", passphrase)
            .putString("manual_hotspot_security", "WPA2")
            .putString("manual_hotspot_band", "AUTO")
            .putInt("manual_hotspot_channel", 0)
            .putString("manual_hotspot_interface", "")
            .putString("wireless_hotspot_mode", "MANUAL")
            .putBoolean(APPLIED, true)
            .apply()
    }
}

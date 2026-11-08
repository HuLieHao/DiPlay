package com.shilapi.xcertplay

import android.content.Context
import android.content.SharedPreferences
import com.shilapi.xcertplay.media.Ff5000AudioProfile

/** Confirmed defaults for this head unit; saved driver choices always win. */
internal object CarSetupDefaults {
    @Synchronized
    fun initialize(context: Context): SharedPreferences {
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        if (!Ff5000AudioProfile.supported()) return prefs
        // Keep the existing one-time, local-asset hotspot migration and its later-edit protection.
        DebugCarHotspotDefaults.provision(context, prefs)
        val editor = prefs.edit()
        if (!prefs.contains("wireless_enabled")) editor.putBoolean("wireless_enabled", true)
        if (!prefs.contains("media_audio_channel")) editor.putInt("media_audio_channel", 3)
        if (!prefs.contains("navigation_audio_channel") && !prefs.contains("navigation_stream_type")) {
            editor.putInt("navigation_audio_channel", 11)
        }
        if (!prefs.contains("carplay_night_mode")) editor.putString("carplay_night_mode", "vehicle")
        editor.apply()
        val hotspot = context.getSharedPreferences("diplay_car_hotspot", Context.MODE_PRIVATE)
        if (!hotspot.contains("auto_enable")) hotspot.edit().putBoolean("auto_enable", true).apply()
        val volume = context.getSharedPreferences("diplay_volume", Context.MODE_PRIVATE)
        // An explicit custom media route still belongs to firmware unless the driver opts in.
        if (!volume.contains("enabled") && prefs.getInt("media_audio_channel", 3) in setOf(0, 3)) {
            volume.edit().putBoolean("enabled", true).apply()
        }
        return prefs
    }
}

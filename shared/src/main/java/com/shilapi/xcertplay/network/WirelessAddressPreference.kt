package com.shilapi.xcertplay.network

import android.content.SharedPreferences

/** One user connection run: retry hints never become evidence of a successful connection. */
class WirelessAddressPreference {
    companion object {
        fun environment(backend: String, iface: String, ssid: String, bssid: String, phone: String): String {
            // Generated hotspots deliberately change credentials and sometimes interfaces each run.
            val generated = backend == "WIFI_P2P" || backend == "LOCAL_ONLY_HOTSPOT"
            val parts = if (generated) listOf(backend, phone.uppercase(java.util.Locale.ROOT))
                else listOf(backend, iface, ssid, bssid.uppercase(java.util.Locale.ROOT), phone.uppercase(java.util.Locale.ROOT))
            return parts.joinToString("|") { "${it.length}:$it" }
        }
    }
    data class Attempt internal constructor(val id: Long, val environment: String, val family: String?, val source: String)
    private var serial = 0L
    private var environment: String? = null
    private var retryFamily: String? = null
    private var finished = false

    @Synchronized fun begin(environment: String, prefs: SharedPreferences): Attempt {
        if (this.environment != environment) retryFamily = null
        this.environment = environment
        finished = false
        val saved = if (prefs.getString("successful_environment", null) == environment)
            HotspotAddressPolicy.parseFamily(prefs.getString("successful_family", null)) else null
        val family = retryFamily ?: saved
        return Attempt(++serial, environment, family,
            if (retryFamily != null) "current_run_retry" else if (saved != null) "confirmed_first_frame" else "default")
    }

    @Synchronized fun timedOut(attempt: Attempt, next: String): Boolean {
        if (!current(attempt) || HotspotAddressPolicy.parseFamily(next) == null) return false
        retryFamily = next
        finished = true
        return true
    }

    @Synchronized fun confirm(attempt: Attempt, family: String, prefs: SharedPreferences): Boolean {
        if (!current(attempt) || HotspotAddressPolicy.parseFamily(family) == null) return false
        prefs.edit().putString("successful_environment", attempt.environment)
            .putString("successful_family", family).apply()
        retryFamily = null
        finished = true
        return true
    }

    @Synchronized fun clear() {
        serial++
        environment = null
        retryFamily = null
        finished = true
    }
    private fun current(attempt: Attempt) = attempt.id == serial && attempt.environment == environment && !finished
}

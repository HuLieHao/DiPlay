package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.ConnectionMilestone

/** Only fixed protocol routes/methods and byte counts survive into the diagnostic report. */
internal object AirPlayControlDiagnostics {
    /** A request being received is progress, not proof that authentication has succeeded. */
    fun milestone(method: String, path: String): ConnectionMilestone? {
        if (method == "SETUP") return ConnectionMilestone.AIRPLAY_STREAM_SETUP
        val route = path.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()
        return when {
            method in setOf("GET", "POST") && route == "info" -> ConnectionMilestone.AIRPLAY_INFO
            method == "POST" && route == "pair-setup" -> ConnectionMilestone.AIRPLAY_PAIR_SETUP
            method == "POST" && route == "pair-verify" -> ConnectionMilestone.AIRPLAY_PAIR_VERIFY
            method == "POST" && route == "auth-setup" -> ConnectionMilestone.AIRPLAY_MFI
            else -> null
        }
    }

    fun request(method: String, path: String, bytes: Int): String {
        val verb = method.takeIf { it in setOf("GET", "POST", "SETUP", "RECORD", "TEARDOWN", "OPTIONS", "GET_PARAMETER", "SET_PARAMETER") } ?: "OTHER"
        val route = path.substringBefore('?').substringBefore('#').substringAfterLast('/').lowercase()
            .takeIf { it in setOf("info", "pair-setup", "pair-verify", "auth-setup", "fp-setup") } ?: "other"
        return "airplay control request method=$verb route=$route contentBytes=$bytes"
    }
}

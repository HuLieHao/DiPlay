package com.shilapi.xcertplay

import java.security.MessageDigest

/** One content digest and one owned, prepared icon. No Activity, bitmap or dynamic /info state. */
internal class PreparedAirPlayIconCache {
    private var key: ByteArray? = null
    private var prepared: Pair<Int, ByteArray>? = null

    // This cache's lifetime also fixes the sizing implementation/version and MAX_PIXELS.
    // Prepared bytes are read-only, like AirPlayIcon.data; own them even for fit's fast path.
    @Synchronized fun prepare(encoded: ByteArray): Pair<Int, ByteArray>? {
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        if (key?.contentEquals(digest) == true) return prepared
        key = null
        prepared = null
        val fitted = AirPlayIconSizing.fit(encoded) ?: return null
        return (fitted.first to fitted.second.copyOf()).also { prepared = it; key = digest }
    }
}

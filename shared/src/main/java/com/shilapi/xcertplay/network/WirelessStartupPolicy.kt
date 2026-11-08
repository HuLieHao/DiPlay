package com.shilapi.xcertplay.network

import java.io.IOException

object WirelessStartupPolicy {
    const val FIRST_TCP_GRACE_MILLIS = 2_000L
    fun fastWireless(model: String, hardware: String, sdk: Int): Boolean =
        model.equals("FF-5000", true) && hardware.equals("ac8227l", true) && sdk == 27

    const val HOTSPOT_READY_MILLIS = 15_000L
    const val INTERFACE_POLL_MILLIS = 250L
    const val STABLE_SAMPLES = 3
    // FF-5000 successful samples reached TCP in 0.3–2s; bound silent startup only.
    const val AVAILABILITY_MILLIS = 30_000L
    const val FIRST_TCP_MILLIS = 20_000L
    // Conservative startup limits; tune against successful head-unit traces. Never apply after rendering.
    const val SESSION_MILLIS = 30_000L
    const val FIRST_FRAME_MILLIS = 30_000L
    const val MAX_STARTUP_RETRIES = 5
    const val STABLE_SESSION_MILLIS = 60_000L
}

enum class WirelessStartupFailure {
    HOTSPOT_NOT_READY, AVAILABILITY_TIMEOUT, FIRST_TCP_TIMEOUT, SESSION_TIMEOUT, FIRST_FRAME_TIMEOUT, HOTSPOT_CONFIGURATION,

    /** The head unit's Android Bluetooth accepts connections that reach no phone. */
    BLUETOOTH_UNUSABLE,

    /** Android 13+ asked to turn the car hotspot off for Wi-Fi Direct, and the driver said no. */
    WIFI_DIRECT_BESIDE_HOTSPOT;

    /** False when no further attempt can change what went wrong. */
    val retryable: Boolean get() = this != HOTSPOT_CONFIGURATION && this != BLUETOOTH_UNUSABLE &&
        this != WIFI_DIRECT_BESIDE_HOTSPOT
}

class WirelessStartupException(val reason: WirelessStartupFailure, message: String) : IOException(message)

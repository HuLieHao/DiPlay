package com.shilapi.xcertplay

/** Fixed observations only: no peer identifiers, credentials, deadlines or retry decisions. */
enum class ConnectionMilestone {
    IDENTIFYING, IDENTIFIED, AUTHENTICATING, AUTHENTICATED, WIFI_CONFIG_SENT,
    AIRPLAY_INFO, AIRPLAY_PAIR_SETUP, AIRPLAY_PAIR_VERIFY, AIRPLAY_MFI, AIRPLAY_STREAM_SETUP,
    VIDEO_RECEIVED,
}

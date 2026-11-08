package com.shilapi.xcertplay.airplay

import java.util.ArrayDeque

/**
 * Removes only byte-identical authenticated RTP recently delivered on this stream.
 * Never waits for a gap or rejects an unseen late packet. Whole-packet comparison avoids
 * interpreting sequence/timestamp wrap or a changed payload as a duplicate.
 */
internal class AudioRtpDuplicateFilter {
    private data class Delivered(val rtp: ByteArray, val atNs: Long)
    private val history = ArrayDeque<Delivered>()

    fun accept(rtp: ByteArray, nowNs: Long): Boolean {
        while (history.isNotEmpty() && nowNs - history.first.atNs >= HISTORY_NS) {
            history.removeFirst()
        }
        if (history.any { it.rtp.contentEquals(rtp) }) return false
        if (history.size == HISTORY_PACKETS) history.removeFirst()
        history.addLast(Delivered(rtp.copyOf(), nowNs))
        return true
    }

    fun clear() = history.clear()

    private companion object {
        const val HISTORY_PACKETS = 16
        const val HISTORY_NS = 200_000_000L
    }
}

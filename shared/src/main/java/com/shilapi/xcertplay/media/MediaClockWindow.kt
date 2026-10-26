package com.shilapi.xcertplay.media

/** Primitive worker-local observations only. Head/time continuity survives reporting windows. */
internal class MediaClockWindow {
    var samples = 0L; private set
    var minPcm = 0L; private set
    var maxPcm = 0L; private set
    var minCompressed = 0L; private set
    var maxCompressed = 0L; private set
    var maxSampleGapNs = 0L; private set
    var zeroHeadReads = 0L; private set
    var advancingHeadReads = 0L; private set
    var maxHeadAdvance = 0L; private set
    var maxHeadAdvanceNs = 0L; private set
    private var hasPrevious = false
    private var previousHead = 0L
    private var previousNs = 0L

    fun sample(pcm: Long, compressed: Long, head: Int, nowNs: Long) {
        if (samples == 0L) {
            minPcm = pcm; maxPcm = pcm
            minCompressed = compressed; maxCompressed = compressed
        } else {
            minPcm = minOf(minPcm, pcm); maxPcm = maxOf(maxPcm, pcm)
            minCompressed = minOf(minCompressed, compressed); maxCompressed = maxOf(maxCompressed, compressed)
        }
        val unsignedHead = head.toLong() and 0xffff_ffffL
        if (hasPrevious) {
            val gap = nowNs - previousNs
            maxSampleGapNs = maxOf(maxSampleGapNs, gap)
            val advance = (unsignedHead - previousHead) and 0xffff_ffffL
            if (advance == 0L) zeroHeadReads++ else advancingHeadReads++
            if (advance > maxHeadAdvance) { maxHeadAdvance = advance; maxHeadAdvanceNs = gap }
        }
        samples++
        hasPrevious = true; previousHead = unsignedHead; previousNs = nowNs
    }

    fun reset() {
        samples = 0L; minPcm = 0L; maxPcm = 0L; minCompressed = 0L; maxCompressed = 0L
        maxSampleGapNs = 0L; zeroHeadReads = 0L; advancingHeadReads = 0L
        maxHeadAdvance = 0L; maxHeadAdvanceNs = 0L
    }
}

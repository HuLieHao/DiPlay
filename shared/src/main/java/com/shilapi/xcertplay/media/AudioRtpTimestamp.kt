package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioFormat

internal fun audioRtpTimestampUs(sample: Int, format: AudioFormat): Long =
    (sample.toLong() and 0xffff_ffffL) * 1_000_000L / format.rtpClockRate

package com.shilapi.xcertplay.media

import java.util.concurrent.atomic.AtomicLong

/** Metadata only: no PCM retention, and output is matched only by codec presentation timestamp. */
internal class AudioEvidence {
    val id = "renderer-${next.incrementAndGet()}"
    data class Packet(val sequence: Int, val sample: Int, val arrivalNs: Long, val inputNs: Long = 0, val outputNs: Long = 0)
    private val inputs = LinkedHashMap<Long, Packet>()
    val pending: Int get() = inputs.size
    var evicted = 0L; private set
    var unmatched = 0L; private set
    var maxQueueNs = 0L; private set
    private var dequeueNs = 0L
    var maxInputWaitNs = 0L; private set
    var maxDecoderNs = 0L; private set
    var maxWriteNs = 0L; private set
    private var lastWrite = "none"
    // A monotonic high-water guard is constant space and never forgets consumed PTS.
    // Nonmonotonic or wrapped timestamps are conservatively uncorrelated until they
    // exceed the high-water mark; only a new renderer starts a new PTS epoch.
    private var highestPts: Long? = null
    private var rejectedPts = 0L
    private class Peak {
        var value = 0L; var at = -1L; var samples = 0L
        fun record(duration: Long, now: Long) {
            if (samples == 0L || duration > value) { value = duration; at = now }
            samples++
        }
        fun field(name: String) = "${name}Samples=$samples max${name.replaceFirstChar { it.uppercase() }}Ns=$value max${name.replaceFirstChar { it.uppercase() }}AtNs=$at"
        fun reset() { value = 0; at = -1; samples = 0 }
    }
    private val queuePeak = Peak()
    private val inputWaitPeak = Peak()
    private val decoderPeak = Peak()
    private val writePeak = Peak()
    fun windowSummary(): String {
        val result = "maximaScope=stats_window ${queuePeak.field("queue")} ${inputWaitPeak.field("inputWait")} ${decoderPeak.field("decoder")} ${writePeak.field("write")} clock=System.nanoTime"
        listOf(queuePeak, inputWaitPeak, decoderPeak, writePeak).forEach { it.reset() }
        return result
    }
    fun packet(sequence: Int, sample: Int, now: Long) = Packet(sequence, sample, now)
    fun dequeued(packet: Packet, now: Long) { dequeueNs = now; maxQueueNs = maxOf(maxQueueNs, now - packet.arrivalNs); queuePeak.record(now - packet.arrivalNs, now) }
    fun input(pts: Long, packet: Packet, now: Long) {
        if (dequeueNs > 0) {
            maxInputWaitNs = maxOf(maxInputWaitNs, now - dequeueNs)
            inputWaitPeak.record(now - dequeueNs, now)
        }
        if (highestPts?.let { pts <= it } == true) {
            inputs.remove(pts)
            rejectedPts++
            return
        }
        highestPts = pts
        if (inputs.size >= 256) { inputs.remove(inputs.keys.first()); evicted++ }
        inputs[pts] = packet.copy(inputNs = now)
    }
    fun output(pts: Long, now: Long = 0): Packet? = inputs.remove(pts)?.copy(outputNs = now).also { if (it == null) unmatched++ }
    fun decoded(packet: Packet?, now: Long) {
        if (packet != null) {
            maxDecoderNs = maxOf(maxDecoderNs, now - packet.inputNs)
            decoderPeak.record(now - packet.inputNs, now)
        }
    }
    fun wrote(duration: Long, packet: Packet? = null, start: Long = 0, end: Long = 0, bytes: Int = 0) {
        maxWriteNs = maxOf(maxWriteNs, duration)
        writePeak.record(duration, end)
        lastWrite = "${context(packet)} writeStartNs=$start writeEndNs=$end writeBytes=$bytes"
    }
    fun context(packet: Packet?) = "sequence=${packet?.sequence ?: -1} sample=${packet?.sample?.toLong()?.and(0xffffffffL) ?: -1} submitNs=${packet?.arrivalNs ?: -1} inputNs=${packet?.inputNs ?: -1} outputNs=${packet?.outputNs ?: -1}"
    fun summary() = "maximaScope=renderer_lifetime correlationPolicy=monotonic_high_water highestPts=$highestPts rejectedPts=$rejectedPts maxQueueNs=$maxQueueNs maxInputWaitNs=$maxInputWaitNs maxDecoderNs=$maxDecoderNs maxWriteNs=$maxWriteNs metadataPending=$pending metadataEvicted=$evicted unmatchedOutput=$unmatched lastWrite=[$lastWrite] clock=System.nanoTime stageStart=renderer_submit"
    companion object {
        private val next = AtomicLong()
        fun timestamp(available: Boolean, frames: Long, nanoTime: Long, playState: Int) =
            "available=$available frames=${if (available) frames else -1} timestampNs=${if (available) nanoTime else -1} playState=$playState"
    }
}

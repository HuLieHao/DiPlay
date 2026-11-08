package com.shilapi.xcertplay.mfi

import com.shilapi.xcertplay.transport.I2cTransport
import com.shilapi.xcertplay.transport.I2cTransportException
import java.io.InterruptedIOException

/** FF-5000 register settling time observed in the successful on-car probe. Not a general I2C delay. */
class BoardMfiTransport(
    private val delegate: I2cTransport,
    private val diagnostic: (String) -> Unit = {},
    private val waitForRead: (Long) -> Unit = { Thread.sleep(it) },
) : I2cTransport {
    private val lock = Any()
    private var register: Int? = null
    private var failures = 0

    override fun transaction(address7Bit: Int, writeData: ByteArray, readLength: Int): ByteArray =
        synchronized(lock) {
            val selection = writeData.size == 1 && readLength == 0
            if (writeData.isNotEmpty()) register = writeData[0].toInt() and 0xff
            val stage = when {
                selection -> "select"
                writeData.isEmpty() -> "read"
                else -> "write"
            }
            val context = "address=0x${address7Bit.toString(16)} register=${register?.let { "0x${it.toString(16)}" } ?: "unknown"} stage=$stage readBytes=$readLength"
            val started = System.nanoTime()
            val response = try {
                delegate.transaction(address7Bit, writeData, readLength)
            } catch (error: I2cTransportException) {
                failures++
                // Bound diagnostic volume during the existing retry window. No authentication payloads.
                if (failures == 1 || failures % 16 == 0) {
                    report("mfi board io_failed $context failures=$failures elapsedMs=${(System.nanoTime() - started) / 1_000_000} error=${error.message}")
                }
                throw error
            }
            if (selection) {
                try {
                    waitForRead(REGISTER_SETTLE_MILLIS)
                } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("MFi register settling interrupted").apply { initCause(error) }
                }
            } else if (writeData.isEmpty() && failures > 0) {
                report("mfi board io_recovered $context failures=$failures")
                failures = 0
            }
            response
        }

    private fun report(message: String) { runCatching { diagnostic(message) } }

    companion object {
        const val REGISTER_SETTLE_MILLIS = 5L
        fun isSupported(model: String, path: String?): Boolean =
            model.replace('_', '-').equals("FF-5000", ignoreCase = true) && path == "/dev/i2c-2"
    }
}

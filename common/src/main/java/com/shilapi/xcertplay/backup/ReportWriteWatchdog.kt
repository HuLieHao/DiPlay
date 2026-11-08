package com.shilapi.xcertplay.backup

import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Socket.soTimeout covers reads only. Close stalled writes without limiting total download time. */
internal object ReportWriteWatchdog {
    fun write(socket: Socket, timeoutMillis: Long, block: (OutputStream) -> Unit) {
        require(timeoutMillis > 0)
        val progress = AtomicLong()
        val expired = AtomicBoolean()
        val timer = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "report-write-watchdog").apply { isDaemon = true }
        }
        val period = timeoutMillis.coerceAtMost(1000)
        timer.scheduleAtFixedRate({
            val last = progress.get()
            if (last != 0L && System.nanoTime() - last >= TimeUnit.MILLISECONDS.toNanos(timeoutMillis) &&
                expired.compareAndSet(false, true)) runCatching { socket.close() }
        }, period, period, TimeUnit.MILLISECONDS)
        try {
            val output = socket.getOutputStream()
            block(object : OutputStream() {
                override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    if (length == 0) return
                    if (expired.get()) throw SocketTimeoutException("Report write stalled")
                    progress.compareAndSet(0, System.nanoTime())
                    output.write(bytes, offset, length)
                    progress.set(System.nanoTime())
                }
                override fun flush() = output.flush()
                override fun close() = flush()
            })
        } finally { timer.shutdownNow() }
    }
}

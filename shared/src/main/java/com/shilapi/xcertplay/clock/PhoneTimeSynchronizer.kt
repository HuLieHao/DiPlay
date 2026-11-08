package com.shilapi.xcertplay.clock

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Authenticated iAP2 input only. Root fallback is limited to the owner's verified FF-5000. */
class PhoneTimeSynchronizer(context: Context, private val log: (String) -> Unit) : Closeable {
    private val context = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "iphone-time-sync").apply { isDaemon = true } }
    private val owner = IphoneClock.newOwner()
    private val queued = AtomicBoolean()
    private val gate = PhoneTimeSyncGate()
    @Volatile private var closed = false
    @Volatile private var process: Process? = null
    private var lastInvalidLog = Long.MIN_VALUE

    fun onFrame(frame: Iap2Frame, currentSession: () -> Boolean) {
        if (frame.messageId != IphoneTimeUpdate.MESSAGE_ID || closed || !IphoneClock.isOwner(owner) || !currentSession()) return
        val elapsed = SystemClock.elapsedRealtime()
        val update = try { IphoneTimeUpdate.decode(frame) } catch (error: Exception) {
            synchronized(this) {
                if (lastInvalidLog == Long.MIN_VALUE || elapsed - lastInvalidLog >= 60_000) {
                    lastInvalidLog = elapsed
                    log("iphone-time rejected ${error.javaClass.simpleName}: ${error.message}")
                }
            }
            return
        }
        if (closed || !IphoneClock.isOwner(owner) || !currentSession()) return
        val anchor = PhoneClockAnchor(update, elapsed)
        IphoneClock.publish(owner, anchor)
        if (!queued.compareAndSet(false, true)) return
        if (!gate.claim(elapsed)) { queued.set(false); return }
        try {
            executor.execute {
                try {
                    if (closed || !IphoneClock.isOwner(owner) || !currentSession()) return@execute
                    val time = anchor.at(SystemClock.elapsedRealtime()) ?: return@execute
                    if (SystemClock.elapsedRealtime() - elapsed > 5_000) {
                        log("iphone-time skipped stale sample")
                        return@execute
                    }
                    val before = System.currentTimeMillis()
                    log("iphone-time received utcMs=${update.unixMillis} offsetMinutes=${update.offsetMinutes} " +
                        "systemBeforeMs=$before deltaMs=${time.unixMillis - before} source=iAP2-0x4e0b")
                    if (abs(time.unixMillis - before) < 2_000) {
                        log("iphone-time system already aligned; display uses phone clock")
                        return@execute
                    }
                    syncSystem(anchor, currentSession)
                } catch (error: Exception) {
                    log("iphone-time system sync failed ${error.javaClass.simpleName}: ${error.message}; display uses phone clock")
                } finally { queued.set(false) }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { queued.set(false) }
    }

    @SuppressLint("MissingPermission")
    private fun syncSystem(anchor: PhoneClockAnchor, currentSession: () -> Boolean) {
        val target = anchor.at(SystemClock.elapsedRealtime()) ?: return
        if (closed || !IphoneClock.isOwner(owner) || !currentSession()) return
        var method = "AlarmManager"
        try {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            check(manager != null) { "alarm service unavailable" }
            manager.setTime(target.unixMillis)
        } catch (_: SecurityException) {
            if (!PhoneTimeSyncGate.vendorSupported(Build.MODEL, Build.HARDWARE, Build.VERSION.SDK_INT)) {
                log("iphone-time system permission unavailable; display uses phone clock")
                return
            }
            if (closed || !IphoneClock.isOwner(owner) || !currentSession()) return
            method = "FF5000 AlarmManager"
            // This factory su mode accepts only a shell; it is never used for arbitrary commands.
            val child = ProcessBuilder("su", "@#zxcvbnmasdfghjklqwertyuiop1234567890,.")
                .redirectErrorStream(true).start()
            process = child
            try {
                if (closed || !IphoneClock.isOwner(owner) || !currentSession()) return
                val latest = anchor.at(SystemClock.elapsedRealtime()) ?: return
                child.outputStream.bufferedWriter().use { it.write(PhoneTimeSyncGate.rootScript(latest.unixMillis)) }
                val deadline = SystemClock.elapsedRealtime() + 3_000
                var exit: Int? = null
                while (!closed && IphoneClock.isOwner(owner) && currentSession() && SystemClock.elapsedRealtime() < deadline) {
                    exit = try { child.exitValue() } catch (_: IllegalThreadStateException) { null }
                    if (exit != null) break
                    Thread.sleep(25)
                }
                check(exit == 0) { "root time command failed or timed out" }
                // The fixed Binder call returns a small parcel; include it for diagnosis.
                val output = child.inputStream.bufferedReader().readText().take(1024).trim()
                log("iphone-time root result=$output")
            } finally {
                child.destroy()
                process = null
            }
        }
        val after = System.currentTimeMillis()
        val expected = anchor.at(SystemClock.elapsedRealtime())?.unixMillis ?: return
        log("iphone-time system sync ${if (abs(after - expected) < 2_000) "verified" else "not_applied"} " +
            "method=$method systemAfterMs=$after expectedMs=$expected deltaMs=${after - expected}")
    }

    override fun close() {
        closed = true
        IphoneClock.invalidate(owner)
        process?.destroy()
        executor.shutdownNow()
    }
}

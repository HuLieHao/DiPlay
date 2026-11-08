package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.Build
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Verified FF-5000 read-only AIDL transaction. Never invokes MCU setters or PhoneLink. */
internal fun readVehicleIllumination(binder: IBinder): Boolean {
    val request = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
        request.writeInterfaceToken("com.ts.can.carinfo.ICarInfoService")
        check(binder.transact(5, request, reply, 0)) { "unsupported_transaction" }
        reply.readException()
        check(reply.dataAvail() >= 4) { "missing_boolean" }
        return when (val value = reply.readInt()) {
            0 -> false
            1 -> true
            else -> error("invalid_boolean=$value")
        }
    } finally {
        reply.recycle()
        request.recycle()
    }
}

/** Verified MainUI change broadcast + one initial Binder read. IPC stays off-main.
 * The one-second timer only supervises binding/timeouts; it does not periodically read MCU.
 * Only if receiver registration fails do we fall back to a 30-second read interval.
 */
internal class VehicleIllumination(context: Context, private val log: (String) -> Unit) :
    CarPlayNightModeController.VehicleSource {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var callback: ((Boolean?) -> Unit)? = null
    private var connection: ServiceConnection? = null
    private var binder: IBinder? = null
    private var generation = 0
    private var nextBind = 0L
    private var lastResult: String? = null
    private var readStarted = 0L
    private var reading = false
    private var nextRead = 0L
    private var readSerial = 0
    private var needsInitialRead = true
    private var receiverRegistered = false
    private var receiver: BroadcastReceiver? = null
    private fun newReceiver(token: Int) = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (callback == null || generation != token || intent?.action != CAR_INFO_ACTION) return
            // The vendor action also carries unrelated car signals; do not log every one.
            if (!intent.hasExtra(ILL_EXTRA)) return
            val value = runCatching { intent.getIntExtra(ILL_EXTRA, -1) }.getOrDefault(-1)
            if (value !in 0..1) {
                log("VEHICLE_ILL broadcast_ignored action=${intent?.action} extra=$ILL_EXTRA raw=$value reason=invalid_or_missing")
                return
            }
            // A newer event wins over an in-flight initial read.
            readSerial++
            reading = false
            needsInitialRead = false
            // Keep the service binding to observe vendor process death even if the event
            // arrived before the initial connection callback.
            if (binder != null) handler.removeCallbacks(tick)
            publish(value == 1, "broadcast")
        }
    }

    override fun start(onState: (Boolean?) -> Unit) {
        stop()
        callback = onState
        nextBind = 0
        nextRead = 0
        lastResult = null
        needsInitialRead = true
        val currentReceiver = newReceiver(generation)
        receiver = currentReceiver
        receiverRegistered = runCatching {
            val filter = IntentFilter(CAR_INFO_ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(currentReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(currentReceiver, filter)
            }
            true
        }.getOrElse {
            log("VEHICLE_ILL receiver_unavailable=${it.javaClass.simpleName}")
            false
        }
        tick.run()
    }

    override fun stop() {
        generation++
        callback = null
        handler.removeCallbacks(tick)
        if (receiverRegistered) runCatching { context.unregisterReceiver(receiver) }
        receiverRegistered = false
        receiver = null
        release()
        reading = false
    }

    private fun publish(value: Boolean?, reason: String) {
        if (callback == null) return
        val result = "$reason state=$value"
        if (result == lastResult) return
        lastResult = result
        log("VEHICLE_ILL $result elapsedMs=${SystemClock.elapsedRealtime()} generation=$generation initialReadNeeded=$needsInitialRead receiverRegistered=$receiverRegistered")
        callback?.invoke(value)
    }

    private fun release() {
        val old = connection
        connection = null
        binder = null
        if (old != null) runCatching { context.unbindService(old) }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (callback == null) return
            val now = SystemClock.elapsedRealtime()
            if (reading && now - readStarted >= READ_TIMEOUT_MS) {
                readSerial++ // An eventual reply to this timed-out read must not restore stale state.
                reading = false
                release()
                nextBind = now + REBIND_MS
                publish(null, "read_timeout")
            }
            if (connection == null && now >= nextBind) bind()
            val current = binder
            if (current != null && needsInitialRead && now >= nextRead && !reading && ipcBusy.compareAndSet(false, true)) {
                reading = true
                readStarted = now
                val token = generation
                val serial = ++readSerial
                executor.execute {
                    val result = runCatching { readVehicleIllumination(current) }
                    ipcBusy.set(false)
                    handler.post {
                        if (callback != null && generation == token && readSerial == serial) {
                            reading = false
                            if (result.isSuccess) {
                                needsInitialRead = !receiverRegistered
                                if (!receiverRegistered) nextRead = SystemClock.elapsedRealtime() + FALLBACK_POLL_MS
                                publish(result.getOrThrow(), "mcu_initial")
                            } else {
                                release()
                                nextBind = SystemClock.elapsedRealtime() + REBIND_MS
                                publish(null, "read_error=${result.exceptionOrNull()?.javaClass?.simpleName}")
                            }
                        }
                    }
                }
            }
            if (needsInitialRead || reading || binder == null) handler.postDelayed(this, POLL_MS)
        }
    }

    private fun bind() {
        nextBind = SystemClock.elapsedRealtime() + REBIND_MS
        val token = generation
        val candidate = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (callback == null || token != generation || connection !== this) return
                binder = service
                handler.removeCallbacks(tick)
                tick.run()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                if (connection !== this) return
                readSerial++
                reading = false
                needsInitialRead = true
                nextRead = 0
                release()
                publish(null, "service_disconnected")
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, (nextBind - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            }
            override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
            override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)
        }
        connection = candidate
        val bound = runCatching {
            context.bindService(Intent().setComponent(ComponentName(
                "com.ts.MainUI", "com.ts.can.carinfo.CarInfoService",
            )), candidate, Context.BIND_AUTO_CREATE)
        }
        if (bound.getOrDefault(false)) {
            if (needsInitialRead) publish(null, "binding")
            // A service that never calls back must not hold an unusable binding forever.
            handler.postDelayed({
                if (generation == token && connection === candidate && binder == null) {
                    release()
                    if (needsInitialRead) publish(null, "bind_timeout")
                }
            }, REBIND_MS)
        } else {
            release()
            if (needsInitialRead) publish(null, "bind_unavailable=${bound.exceptionOrNull()?.javaClass?.simpleName ?: "not_found"}")
        }
    }

    companion object {
        const val CAR_INFO_ACTION = "broadcast_send_carinfo"
        const val ILL_EXTRA = "ILL_LIGHT"
        private const val FALLBACK_POLL_MS = 30_000L
        private const val POLL_MS = 1_000L
        private const val READ_TIMEOUT_MS = 3_000L
        private const val REBIND_MS = 10_000L
        private val ipcBusy = AtomicBoolean(false)
        private val executor = Executors.newSingleThreadExecutor { task ->
            Thread(task, "vehicle-illumination").apply { isDaemon = true }
        }
    }
}

internal object NightModeLastResult {
    var value: Pair<Boolean, String>? = null
        private set
    fun publish(night: Boolean, source: String) { value = night to source }
}

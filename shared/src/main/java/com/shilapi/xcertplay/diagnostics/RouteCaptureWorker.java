package com.shilapi.xcertplay.diagnostics;

import java.util.*;
import java.util.function.*;

/** Single owner of route output. Service snapshots never enter this worker or delay its polling. */
final class RouteCaptureWorker implements Runnable {
    interface Pause { void sleep() throws InterruptedException; }
    private final Supplier<List<RouteCaptureEvents.Event>> source;
    private final Consumer<List<RouteCaptureEvents.Event>> probe;
    private final BooleanSupplier stopped;
    private final LongSupplier clock;
    private final Pause pause;
    RouteCaptureWorker(Supplier<List<RouteCaptureEvents.Event>> source,
                       Consumer<List<RouteCaptureEvents.Event>> probe, BooleanSupplier stopped,
                       LongSupplier clock, Pause pause) {
        this.source=source; this.probe=probe; this.stopped=stopped; this.clock=clock; this.pause=pause;
    }
    public void run() {
        long nextPeriodic=clock.getAsLong()+30000;
        while (!stopped.getAsBoolean()) {
            List<RouteCaptureEvents.Event> events=source.get();
            if (stopped.getAsBoolean()) return;
            if (!events.isEmpty() || clock.getAsLong()>=nextPeriodic) {
                probe.accept(events);
                nextPeriodic=clock.getAsLong()+30000;
            }
            if (stopped.getAsBoolean()) return;
            try { pause.sleep(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
    }
}

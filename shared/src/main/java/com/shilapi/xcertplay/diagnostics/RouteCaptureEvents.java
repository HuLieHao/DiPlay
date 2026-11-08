package com.shilapi.xcertplay.diagnostics;

import java.util.*;
import java.util.function.Consumer;

/** Connection callbacks enqueue only; the capture writer owns all file IO. No peer is inferred from Bluetooth. */
public final class RouteCaptureEvents {
    private RouteCaptureEvents() { }
    public static java.io.File journal(java.io.File rootDirectory, String path) throws java.io.IOException {
        java.io.File root=rootDirectory.getCanonicalFile(), journal=new java.io.File(path).getCanonicalFile();
        java.io.File app=journal.getParentFile();
        if (!journal.getName().equals("route-events.log") || app==null ||
            !root.getParentFile().equals(app.getParentFile()) ||
            !root.getName().matches(java.util.regex.Pattern.quote(app.getName())+"-root-[01]"))
            throw new java.io.IOException("invalid capture journal path");
        return journal;
    }
    /** Retained only in this collector, replaced whenever a new attempt appears. */
    public static final class Context {
        public Event latest;
        private final LinkedHashSet<String> peers=new LinkedHashSet<>();
        public void observe(List<Event> events) {
            for (Event event:events) {
                if (latest==null || !latest.attempt.equals(event.attempt) || !latest.interfaceName.equals(event.interfaceName)) peers.clear();
                latest=event;
                if (!event.peer.equals("unknown") && peers.size()<8) peers.add(event.peer);
            }
        }
        public List<String> peers() { return new ArrayList<>(peers); }
    }
    private static volatile Consumer<Event> sink;
    public static void setSink(Consumer<Event> value) { sink = value; }
    public static void emit(Event event) {
        Consumer<Event> current = sink;
        if (current != null) try { current.accept(event); } catch (RuntimeException ignored) { /* Diagnostics cannot alter connection policy. */ }
    }
    public static final class Event {
        public final String kind, attempt, interfaceName, peer;
        public final long wallMs, elapsedMs;
        public Event(String kind, String attempt, String interfaceName, long wallMs, long elapsedMs) {
            this(kind, attempt, interfaceName, wallMs, elapsedMs, "unknown");
        }
        public Event(String kind, String attempt, String interfaceName, long wallMs, long elapsedMs, String peer) {
            if (!peer.matches("unknown|[0-9a-fA-F:.]{2,45}")) throw new IllegalArgumentException("invalid peer");
            if (!kind.matches("FIRST_TCP_WAIT|FIRST_TCP_TIMEOUT|FIRST_TCP_ACCEPTED") || !attempt.matches("[a-zA-Z0-9_=/.-]{1,120}") ||
                !interfaceName.matches("[a-zA-Z0-9_.-]{1,15}")) throw new IllegalArgumentException("invalid route event");
            this.kind = kind; this.attempt = attempt; this.interfaceName = interfaceName;
            this.peer = peer; this.wallMs = wallMs; this.elapsedMs = elapsedMs;
        }
        public String encode() { return kind+"\t"+attempt+"\t"+interfaceName+"\t"+wallMs+"\t"+elapsedMs+"\t"+peer; }
        public String evidence() { return "trigger="+kind+" attempt="+attempt+" iface="+interfaceName+
            " eventWallMs="+wallMs+" eventElapsedMs="+elapsedMs+" peer="+peer+" peerSource="+(peer.equals("unknown")?"no_observed_peer_before_first_tcp":"current_listener_tcp_accept"); }
        public static Event parse(String line) {
            try {
                String[] fields = line.split("\t", -1);
                if (fields.length != 6) return null;
                return new Event(fields[0], fields[1], fields[2], Long.parseLong(fields[3]), Long.parseLong(fields[4]), fields[5]);
            } catch (IllegalArgumentException ignored) { return null; }
        }
    }
    /** Distinct stages are never coalesced together; overload is explicit, bounded and keeps newest attempts. */
    public static final class Pending {
        private final LinkedHashMap<String, Event> events = new LinkedHashMap<>();
        private boolean sealed;
        public long coalesced, dropped;
        public synchronized void offer(Event event) {
            if (sealed) return;
            String key=event.attempt+":"+event.kind+(event.kind.equals("FIRST_TCP_ACCEPTED")?":"+event.peer:"");
            if (events.containsKey(key)) { coalesced++; return; }
            if (events.size()==64) { events.remove(events.keySet().iterator().next()); dropped++; }
            events.put(key, event);
        }
        public synchronized List<Event> drain() {
            List<Event> result = new ArrayList<>(events.values()); events.clear(); return result;
        }
        public synchronized int size() { return events.size(); }
        public synchronized void seal() { sealed = true; }
        public synchronized String status() { return "coalesced="+coalesced+" dropped="+dropped; }
    }
}

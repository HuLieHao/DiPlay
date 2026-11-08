package com.shilapi.xcertplay.diagnostics;

import java.io.*;
import java.util.*;
import java.util.function.Consumer;

/** Audio anomalies have their own journal and lifecycle; producers never perform IO. */
public final class AudioCaptureEvents {
    private AudioCaptureEvents() { }
    private static volatile Consumer<String> sink;
    public static void setSink(Consumer<String> value) { sink=value; }
    public static void emit(String event) {
        Consumer<String> current=sink;
        if(current!=null) try { current.accept(event); } catch(RuntimeException ignored) { }
    }
    public static File journal(File root, File appDirectory) throws IOException {
        File candidate=new File(appDirectory,"audio-events.log");
        if(java.nio.file.Files.isSymbolicLink(candidate.toPath())) throw new IOException("audio journal symlink rejected");
        File path=candidate.getCanonicalFile();
        // Reuse the established sibling-directory validation, while rejecting an audio symlink escape.
        RouteCaptureEvents.journal(root,new File(appDirectory,"route-events.log").getPath());
        if(!path.getParentFile().equals(appDirectory.getCanonicalFile()) || !path.getName().equals("audio-events.log"))
            throw new IOException("invalid audio journal path");
        return path;
    }
    public static boolean valid(String line) {
        if(line.length()>1800) return false;
        String[] fields=line.split("\t",-1);
        if(fields.length!=5 || !fields[0].matches("[A-Z_]{1,40}") || !fields[1].matches("renderer-[0-9]+")) return false;
        try { Long.parseLong(fields[2]); Long.parseLong(fields[3]); return true; }
        catch(NumberFormatException e) { return false; }
    }
    /** Cursor describes journal reading, never completion of expensive snapshots. */
    public static String boundary(long offset, long length, String scope) {
        return "boundary="+scope+" journalReadOffset="+offset+" journalLength="+length+
            " journalUnreadBytes="+(offset<0?-1:Math.max(0,length-offset))+" snapshotTailCoverage=not_claimed";
    }
    public static final class Pending {
        private final ArrayDeque<String> events=new ArrayDeque<>();
        private boolean sealed;
        public long dropped;
        public synchronized void offer(String event) {
            if(sealed || events.size()>=512) { dropped++; return; }
            events.add(event);
        }
        public synchronized List<String> drain() { List<String> result=new ArrayList<>(events); events.clear(); return result; }
        public synchronized int size() { return events.size(); }
        public synchronized void seal() { sealed=true; }
        public synchronized String status() { return "dropped="+dropped; }
    }
    /** Only snapshots coalesce. Original events are already retained in app-full.log/journal. */
    public static final class Snapshots {
        private final java.util.function.LongSupplier clock;
        private String representative;
        private boolean urgent, sealed;
        private long first, last, firstWall, lastWall, count;
        private final Map<String, Long> types=new TreeMap<>();
        private long lastUrgent=Long.MIN_VALUE, lastSlow=Long.MIN_VALUE;
        public volatile long coalesced, dropped, suppressed, discarded, cooldownPolls, cancelledBeforeProbe;
        public Snapshots() { this(() -> System.nanoTime()/1000000L); }
        public Snapshots(java.util.function.LongSupplier clock) { this.clock=clock; }
        public synchronized String status() { return "coalesced="+coalesced+" dropped="+dropped+" suppressed="+suppressed+" discarded="+discarded+" cooldownPolls="+cooldownPolls+" cancelledBeforeProbe="+cancelledBeforeProbe+" pending="+count; }
        public synchronized void offer(String value) {
            if(sealed) { dropped++; return; }
            String[] fields=value.split("\t",-1);
            String kind=fields[0];
            boolean isUrgent=kind.equals("UNDERRUN") || (kind.equals("REBUFFER_PAUSE") || kind.equals("REBUFFER_RECOVERY") || kind.equals("REBUFFER")) || kind.equals("WRITE_ERROR");
            boolean slow=kind.equals("WRITE_ANOMALY");
            if(!isUrgent && !slow) { suppressed++; return; }
            long eventNs=Long.parseLong(fields[3]), wallMs=Long.parseLong(fields[2]);
            if(count>0) coalesced++;
            else { first=eventNs; firstWall=wallMs; }
            last=eventNs; lastWall=wallMs; count++;
            types.put(kind,types.getOrDefault(kind,0L)+1);
            if(representative==null || (isUrgent && !urgent)) representative=value;
            urgent|=isUrgent;
        }
        public synchronized String take() {
            if(sealed || representative==null) return null;
            long now=clock.getAsLong();
            long previous=urgent?lastUrgent:lastSlow;
            long interval=urgent?10000:60000;
            if(previous!=Long.MIN_VALUE && now-previous<interval) { cooldownPolls++; return null; }
            if(urgent) lastUrgent=now; else lastSlow=now;
            String value=representative+" snapshotBatchFirstDetectedNs="+first+" snapshotBatchLastDetectedNs="+last+" snapshotBatchFirstWallMs="+firstWall+" snapshotBatchLastWallMs="+lastWall+" snapshotBatchCount="+count+" snapshotBatchTypes="+types+" snapshotBatchUrgent="+urgent;
            representative=null; urgent=false; count=0; types.clear();
            return value;
        }
        public synchronized void cancelledBeforeProbe() { cancelledBeforeProbe++; }
        public synchronized void seal() {
            sealed=true;
            if(representative!=null) { discarded+=count; representative=null; count=0; types.clear(); }
        }
    }
}

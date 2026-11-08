package com.shilapi.xcertplay.diagnostics;

import java.io.*;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Live private diagnostic mode, independent of packet/event collection. */
public final class AudioCaptureMode {
    private AudioCaptureMode() {}
    public static boolean enabled(File file) {
        // Only this tiny regular file is accepted. Never follow a mode-file symlink or read an
        // unbounded replacement file in the privileged collector. Missing/bad input preserves ON.
        try {
            if(file == null || Files.isSymbolicLink(file.toPath()) || !file.isFile() || file.length()!=4) return true;
            try(InputStream input=Files.newInputStream(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return !(input.read()=='o' && input.read()=='f' && input.read()=='f' && input.read()=='\n' && input.read()==-1);
            }
        } catch(IOException | SecurityException e) { return true; }
    }
    public static final class Cached {
        private final java.util.function.LongSupplier clock;
        private File previousFile;
        private long checkedAt;
        private boolean initialized, value;
        public Cached(java.util.function.LongSupplier clock) { this.clock=clock; }
        public synchronized boolean enabled(File file) {
            long now=clock.getAsLong();
            if(!initialized || !java.util.Objects.equals(previousFile,file) || now-checkedAt>=100_000_000L || now<checkedAt)
                return refresh(file);
            return value;
        }
        public synchronized boolean refresh(File file) {
            value=AudioCaptureMode.enabled(file);
            previousFile=file; checkedAt=clock.getAsLong(); initialized=true;
            return value;
        }
    }
    public static boolean heavyCommand(String[] args) {
        return args.length==2 && args[0].equals("dumpsys") &&
            (args[1].equals("audio") || args[1].equals("media.audio_flinger") || args[1].equals("media.audio_policy"));
    }
    public static boolean await(Process process, long timeoutMs, BooleanSupplier cancelled) throws InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while(!cancelled.getAsBoolean()) {
            long remaining=deadline-System.nanoTime();
            if(remaining<=0) return false;
            if(process.waitFor(Math.min(100,Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining))),TimeUnit.MILLISECONDS)) return true;
        }
        return false;
    }
}

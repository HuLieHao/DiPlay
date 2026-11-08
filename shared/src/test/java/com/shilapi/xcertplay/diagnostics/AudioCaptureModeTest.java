package com.shilapi.xcertplay.diagnostics;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class AudioCaptureModeTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void offIsLiveAndInvalidOrMissingModePreservesExistingCapture() throws Exception {
        File mode = new File(temp.getRoot(), "audio-heavy-mode.txt");
        assertTrue(AudioCaptureMode.enabled(mode));
        Files.write(mode.toPath(), "off\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(AudioCaptureMode.enabled(mode));
        Files.write(mode.toPath(), "on\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(AudioCaptureMode.enabled(mode));
        for (String invalid : new String[]{"", "off", "off\nextra", "OFF\n", "off\n".repeat(10000)}) {
            Files.write(mode.toPath(), invalid.getBytes(StandardCharsets.UTF_8));
            assertTrue("invalid mode must preserve heavy capture", AudioCaptureMode.enabled(mode));
        }
        mode.delete();
        File outside = temp.newFile(); Files.write(outside.toPath(), "off\n".getBytes(StandardCharsets.UTF_8));
        Files.createSymbolicLink(mode.toPath(), outside.toPath());
        assertTrue("root must not follow mode symlink", AudioCaptureMode.enabled(mode));
    }
    @Test public void threadScanReadsReuseModeForAtMostOneHundredMilliseconds() throws Exception {
        File mode = new File(temp.getRoot(), "audio-heavy-mode.txt");
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        AudioCaptureMode.Cached cached = new AudioCaptureMode.Cached(clock::get);
        Files.write(mode.toPath(), "on\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(cached.enabled(mode));
        Files.write(mode.toPath(), "off\n".getBytes(StandardCharsets.UTF_8));
        clock.set(99_000_000);
        assertTrue("reuse cached state within 100 ms", cached.enabled(mode));
        assertFalse("command boundary bypasses scan cache", cached.refresh(mode));
        Files.write(mode.toPath(), "on\n".getBytes(StandardCharsets.UTF_8));
        clock.set(198_000_000);
        assertFalse(cached.enabled(mode));
        clock.set(199_000_000);
        assertTrue("state must refresh after 100 ms", cached.enabled(mode));
    }

    @Test public void cancellationStopsWaitingForAnInFlightHeavyProbe() throws Exception {
        Process process = new ProcessBuilder("sh", "-c", "sleep 10").start();
        long start = System.nanoTime();
        try {
            assertFalse(AudioCaptureMode.await(process, 3000, () -> true));
            assertTrue("cancellation must not wait for the command timeout", (System.nanoTime()-start)/1_000_000 < 500);
        } finally { process.destroyForcibly(); process.waitFor(); }
    }
    @Test public void onlyAudioServiceCommandsAreHeavy() {
        for(String service : new String[]{"audio", "media.audio_flinger", "media.audio_policy"})
            assertTrue(AudioCaptureMode.heavyCommand(new String[]{"dumpsys", service}));
        for(String service : new String[]{"wifi", "connectivity", "tethering", "bluetooth_manager", "cpuinfo"})
            assertFalse(AudioCaptureMode.heavyCommand(new String[]{"dumpsys", service}));
        assertFalse(AudioCaptureMode.heavyCommand(new String[]{"ip", "route"}));
    }
}

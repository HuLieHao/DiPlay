package com.shilapi.xcertplay.diagnostics

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class CaptureEvidenceTest {
    @get:Rule val temp = TemporaryFolder()
    private fun put(path: String, text: String): File = File(temp.root, path).apply {
        parentFile!!.mkdirs(); writeText(text)
    }

    @Test fun comparisonOffRetainsProcessAndSystemCountersButSkipsThreadScans() {
        put("proc/stat", "system-counter")
        put("proc/42/stat", "app-counter")
        put("proc/42/task/43/stat", "heavy-thread-counter")
        val out = ByteArrayOutputStream()
        CaptureEvidence.resources(temp.root, out, 42, 99, { false }, { false })
        val result = out.toString("UTF-8")
        assertTrue(result.contains("system-counter"))
        assertTrue(result.contains("app-counter"))
        assertFalse(result.contains("heavy-thread-counter"))
        assertTrue(result.contains("threadScans=disabled"))
    }

    @Test fun stopBoundaryReportsLateWriterTailAndUnknownCursor() {
        assertTrue(AudioCaptureEvents.boundary(10, 10, "observed_at_stop").contains("journalUnreadBytes=0"))
        val final = AudioCaptureEvents.boundary(10, 25, "app_writer_and_root_stopped")
        assertTrue(final.contains("journalUnreadBytes=15"))
        assertTrue(final.contains("snapshotTailCoverage=not_claimed"))
        assertTrue(AudioCaptureEvents.boundary(-1, 25, "unknown").contains("journalUnreadBytes=-1"))
    }

    @Test fun audioSchedulingIncludesAppAndAudioserverAndHonorsStop() {
        put("proc/42/task/43/comm", "carplay-audio")
        put("proc/42/task/43/schedstat", "app-scheduling")
        put("proc/77/comm", "audioserver")
        put("proc/77/task/78/sched", "server-scheduling")
        put("proc/88/comm", "unrelated")
        put("proc/88/task/89/sched", "must-not-collect")
        val out = ByteArrayOutputStream()
        CaptureEvidence.audioResources(temp.root, out, 42) { false }
        assertTrue(out.toString().contains("app-scheduling"))
        assertTrue(out.toString().contains("server-scheduling"))
        assertFalse(out.toString().contains("must-not-collect"))
        val stopped = ByteArrayOutputStream()
        CaptureEvidence.audioResources(temp.root, stopped, 42) { true }
        assertEquals(0, stopped.size())
    }

    @Test fun readsConfiguredControlDirectoryWithoutExecutingConfigText() {
        put("data/misc/wifi/hostapd.conf", "interface=ap0\nctrl_interface=DIR=/data/misc/wifi/hostapd/ctrl GROUP=wifi\n")
        val endpoints = CaptureEvidence.hostapdEndpoints(temp.root)
        assertTrue(endpoints.any { it.contentEquals(arrayOf("/data/misc/wifi/hostapd/ctrl", "ap0")) })
        put("data/misc/wifi/hostapd.conf", "interface=ap0;reboot\nctrl_interface=/tmp/anything\n")
        assertFalse(CaptureEvidence.hostapdEndpoints(temp.root).any { it[1].contains(';') || it[0] == "/tmp/anything" })
    }

    @Test fun preservesLiveProcessCountersAndReportsMissingEvidence() {
        put("proc/42/stat", "42 (app) S 1 2 3 4 raw-starttime-and-cpu\n")
        put("proc/42/io", "write_bytes: 8192\n")
        put("proc/42/task/43/stat", "43 (audio writer) S raw-thread-counters\n")
        put("proc/42/task/43/io", "write_bytes: 4096\n")
        put("proc/77/comm", "audioserver\n")
        put("proc/77/stat", "77 (audioserver) S live-audio-cpu\n")
        put("proc/88/comm", "unrelated\n")
        put("proc/88/stat", "must-not-collect\n")
        put("sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq", "800000\n")
        put("sys/class/thermal/thermal_zone0/type", "cpu-thermal\n")
        put("sys/class/thermal/thermal_zone0/temp", "63000\n")
        val out = ByteArrayOutputStream()
        CaptureEvidence.resources(temp.root, out, 42, 99) { false }
        val result = out.toString("UTF-8")
        assertTrue(result.contains("write_bytes: 8192"))
        assertTrue(result.contains("raw-thread-counters"))
        assertTrue(result.contains("write_bytes: 4096"))
        assertTrue(result.contains("live-audio-cpu"))
        assertTrue(result.contains("800000"))
        assertTrue(result.contains("63000"))
        assertTrue(result.contains("/proc/99/stat unavailable="))
        assertFalse(result.contains("must-not-collect"))
    }

    @Test fun discoversConfiguredBluetoothLogWithoutEnablingIt() {
        put("system/etc/bluetooth/bt_stack.conf", "BtSnoopLogOutput=false\nBtSnoopFileName=/data/misc/bluedroid/custom_hci.log\n")
        assertTrue(CaptureEvidence.snoopPaths(temp.root).contains("/data/misc/bluedroid/custom_hci.log"))
        assertEquals("BtSnoopLogOutput=false\nBtSnoopFileName=/data/misc/bluedroid/custom_hci.log\n",
            File(temp.root, "system/etc/bluetooth/bt_stack.conf").readText())
    }

    @Test fun cancellationStopsResourceTraversal() {
        val out = ByteArrayOutputStream()
        CaptureEvidence.resources(temp.root, out, 42, 99) { true }
        assertEquals(0, out.size())
    }
}

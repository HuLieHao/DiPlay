package com.shilapi.xcertplay.diagnostics

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RootCaptureModeTest {
    @Test fun liveOffSkipsAudioDumpsButKeepsOtherCommandsAndCanResume() {
        val folder = Files.createTempDirectory("root-capture-mode").toFile()
        val type = RootCaptureMain::class.java
        fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
        val directory = field("directory")
        val audioJournal = field("audioJournal")
        val oldDirectory = directory.get(null)
        val oldJournal = audioJournal.get(null)
        val oldMode = field("lastHeavyMode").get(null)
        val command = type.getDeclaredMethod("command", String::class.java, Int::class.javaPrimitiveType, Array<String>::class.java).apply { isAccessible = true }
        try {
            directory.set(null, folder)
            audioJournal.set(null, folder.resolve("audio-events.log"))
            folder.resolve("audio-heavy-mode.txt").writeText("off\n")
            command.invoke(null, "audio-off.log", 1, arrayOf("dumpsys", "audio"))
            assertFalse("OFF must skip periodic and anomaly audio dumps at the shared command boundary", folder.resolve("audio-off.log").exists())
            command.invoke(null, "connectivity.log", 1, arrayOf("dumpsys", "connectivity"))
            assertTrue("OFF must retain unrelated connectivity commands", folder.resolve("connectivity.log").exists())
            folder.resolve("audio-heavy-mode.txt").writeText("on\n")
            command.invoke(null, "audio-on.log", 1, arrayOf("dumpsys", "audio"))
            assertTrue("ON must resume heavy probes", folder.resolve("audio-on.log").exists())
        } finally {
            directory.set(null, oldDirectory); audioJournal.set(null, oldJournal)
            field("lastHeavyMode").set(null, oldMode)
            folder.deleteRecursively()
        }
    }
}

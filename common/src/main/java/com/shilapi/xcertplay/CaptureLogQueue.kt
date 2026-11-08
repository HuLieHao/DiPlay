package com.shilapi.xcertplay

/** Bounded RAM, not bounded disk retention. A failed writer stops admission entirely. */
internal class CaptureLogQueue(private val maxChars: Int = 2 * 1024 * 1024) {
    private val entries = java.util.ArrayDeque<String>()
    private var enabled = true
    var pendingChars = 0
        private set
    var dropped = 0L
        private set
    @Synchronized fun offer(line: String): Boolean {
        if (!enabled || line.length > maxChars - pendingChars || entries.size >= 8192) {
            dropped++; return false
        }
        entries.addLast(line); pendingChars += line.length; return true
    }
    @Synchronized fun poll(): String? = entries.pollFirst()?.also { pendingChars -= it.length }
    @Synchronized fun seal() { enabled = false }
    @Synchronized fun disable() { enabled = false; dropped += entries.size; entries.clear(); pendingChars = 0 }
    @Synchronized fun status() = "pending=${entries.size} pendingChars=$pendingChars dropped=$dropped admission=$enabled"
}

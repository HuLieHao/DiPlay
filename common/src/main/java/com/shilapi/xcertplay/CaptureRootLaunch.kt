package com.shilapi.xcertplay

import java.io.File

internal object CaptureRootLaunch {
    fun attemptDirectory(parent: File, attempt: Int): File {
        require(attempt in 0..1)
        return File(parent.parentFile, "${parent.name}-root-$attempt")
    }
    fun awaitReady(child: Process, directory: File, timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (child.isAlive && !File(directory, "root-ready").exists() && System.nanoTime() < deadline) Thread.sleep(25)
        return child.isAlive && File(directory, "root-ready").exists()
    }
}

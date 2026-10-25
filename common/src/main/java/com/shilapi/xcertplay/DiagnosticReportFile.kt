package com.shilapi.xcertplay

import java.io.File
import java.io.IOException
import java.io.Writer

/** A private, uniquely named snapshot. The download transport owns deletion after this returns. */
internal object DiagnosticReportFile {
    fun create(directory: File, versionCode: Long, write: (Writer) -> Unit): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Report storage is unavailable")
        val file = File.createTempFile("BuickPlay-code$versionCode-", ".txt", directory)
        try {
            file.outputStream().writer(Charsets.UTF_8).buffered(32 * 1024).use(write)
            return file
        } catch (failure: Throwable) {
            runCatching { file.delete() }
            throw failure
        }
    }
}

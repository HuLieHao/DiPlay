package com.shilapi.xcertplay

import java.io.File

/** Append-only files are never rotated/deleted by the ordinary diagnostic archive. */
internal object StartupCaptureFiles {
    fun snapshot(directory: File): List<Pair<File, Long>> {
        if (!directory.isDirectory) return emptyList()
        val root = directory.canonicalFile
        return directory.listFiles().orEmpty().filter { it.isDirectory && it.canonicalFile == File(root, it.name) }
            .flatMap { run -> run.listFiles().orEmpty().filter { file ->
                file.isFile && file.canonicalFile == File(run.canonicalFile, file.name) &&
                    !file.name.endsWith(".tmp") && !file.name.endsWith(".pcap.complete")
            }.map { file ->
                val length = if (file.extension == "pcap") {
                    val marker = File(file.path + ".complete")
                    val published = if (marker.isFile && marker.length() <= 40 && marker.canonicalFile == File(file.parentFile!!.canonicalFile, marker.name))
                        marker.readText().trim().toLongOrNull() else null
                    (published ?: 0L).coerceIn(0, file.length())
                } else file.length()
                file to length
            } }.sortedBy { it.first.path }
    }

    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    fun processStart(stat: String): String = stat.substring(stat.lastIndexOf(')') + 2).split(" ")[19]
}

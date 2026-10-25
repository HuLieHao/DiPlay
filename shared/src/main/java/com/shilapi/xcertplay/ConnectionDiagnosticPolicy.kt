package com.shilapi.xcertplay

/** Evidence may outlive its UI generation; this policy never authorizes state callbacks. */
object ConnectionDiagnosticPolicy {
    fun retainAfterGenerationChange(message: String): Boolean =
        message.startsWith("CONNECTION_DIAGNOSTIC ") ||
            message.startsWith("TRACE airplay control response-buffered ") ||
            message.startsWith("TRACE airplay control responses-flushed ")
}

package com.shilapi.xcertplay.media

import android.os.Build

/** Verified firmware music/GIS streams; GIS signaling still belongs to the system. */
object Ff5000AudioProfile {
    fun supported(): Boolean = Build.MODEL.equals("FF-5000", true) &&
        Build.HARDWARE.equals("ac8227l", true) && Build.VERSION.SDK_INT == 27
}

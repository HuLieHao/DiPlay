package com.shilapi.xcertplay

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.host.BuildConfig
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal object DiagnosticBuildInfo {
    fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())

    fun report(context: Context): String = buildString {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val code = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else @Suppress("DEPRECATION") it.versionCode.toLong() }
        appendLine("package=${context.packageName} versionName=${info?.versionName} versionCode=$code")
        appendLine("sourceRevision=${BuildConfig.SOURCE_REVISION} buildType=${BuildConfig.BUILD_TYPE}")
        appendLine("reportGeneratedAt=${timestamp()} timeZone=${TimeZone.getDefault().id}")
    }
}

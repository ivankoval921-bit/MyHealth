package com.myhealth.ai.logic

import android.content.Context
import android.os.Build

object AppMeta {
    const val APP_NAME = "Моё здоровье"
    const val APP_EN_NAME = "MyHealth"
    const val DEVELOPER = "Volodymyr's Ilchenko"
    const val GITHUB_REPO = "ivankoval921-bit/MyHealth"
    const val GITHUB_URL = "https://github.com/ivankoval921-bit/MyHealth"
    const val RELEASES_BASE_URL = "https://github.com/ivankoval921-bit/MyHealth/releases/latest/download/"
    const val DEFAULT_APK_FILENAME = "MyHealth.apk"
    const val VERSION_NAME_FALLBACK = "1.0.1"
    const val VERSION_CODE_FALLBACK = 2L

    @Suppress("DEPRECATION")
    fun installedVersionCode(context: Context): Long = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
    }.getOrDefault(VERSION_CODE_FALLBACK)

    fun installedVersionName(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
            ?.takeIf { it.isNotBlank() } ?: VERSION_NAME_FALLBACK
    }.getOrDefault(VERSION_NAME_FALLBACK)
}

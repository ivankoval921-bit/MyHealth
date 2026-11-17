package com.myhealth.ai.logic

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.myhealth.ai.MainActivity
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class AppUpdateInfo(
    val code: Long,
    val name: String,
    val notes: String,
    val apkUrl: String
)

object UpdateChecker {
    private const val CHANNEL_ID = "myhealth_updates"
    private const val NOTIFICATION_ID = 1101
    private const val PREFS = "myhealth_update_prefs"
    private const val KEY_NOTIFIED_CODE = "notified_version_code"

    fun parseVersionJson(rawJson: String, currentCode: Long): AppUpdateInfo? {
        val trimmed = rawJson.trim()
        if (trimmed.isEmpty()) return null
        val json = JSONObject(trimmed)
        val remoteCode = json.optLong("code", -1L)
        if (remoteCode <= currentCode || remoteCode <= 0L) return null
        val name = json.optString("name", "").ifBlank { "v$remoteCode" }
        val notes = json.optString("notes", "")
        val apkUrl = json.optString("apkUrl", "").ifBlank {
            AppMeta.RELEASES_BASE_URL + AppMeta.DEFAULT_APK_FILENAME
        }
        return AppUpdateInfo(
            code = remoteCode,
            name = name,
            notes = notes,
            apkUrl = apkUrl
        )
    }

    private fun openConnection(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "${AppMeta.APP_EN_NAME}/${AppMeta.VERSION_NAME_FALLBACK}")
        }

    fun checkBlocking(context: Context): Result<AppUpdateInfo?> = runCatching {
        val conn = openConnection(AppMeta.RELEASES_BASE_URL + "version.json")
        try {
            val status = conn.responseCode
            if (status == HttpURLConnection.HTTP_NOT_FOUND) {
                return@runCatching null
            }
            if (status !in 200..299) {
                error("HTTP $status при проверке обновлений")
            }
            val raw = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseVersionJson(raw, AppMeta.installedVersionCode(context))
        } finally {
            conn.disconnect()
        }
    }

    fun updateApkFile(context: Context): File =
        File(File(context.cacheDir, "update").apply { mkdirs() }, AppMeta.DEFAULT_APK_FILENAME)

    fun downloadBlocking(
        context: Context,
        info: AppUpdateInfo,
        onProgress: (Int) -> Unit
    ): Result<File> = runCatching {
        val target = updateApkFile(context)
        val tmp = File(target.parentFile, "${AppMeta.DEFAULT_APK_FILENAME}.part")
        val conn = openConnection(info.apkUrl)
        try {
            val status = conn.responseCode
            if (status !in 200..299) {
                error("HTTP $status при загрузке APK")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(65_536)
                    var copied = 0L
                    var lastPct = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        val pct = if (total > 0) ((copied * 100L) / total).toInt().coerceIn(0, 100) else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct)
                        }
                    }
                }
            }
            if (tmp.length() < 1_000_000L) {
                tmp.delete()
                error("Скачанный APK повреждён или слишком мал (${tmp.length()} байт)")
            }
            if (target.exists()) target.delete()
            check(tmp.renameTo(target)) { "Не удалось сохранить загруженный APK" }
            target
        } finally {
            conn.disconnect()
        }
    }

    fun canRequestPackageInstalls(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }

    fun launchInstallerOrPermissionSettings(context: Context, apkFile: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(permIntent)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(installIntent)
        return true
    }

    fun schedulePeriodicChecks(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, UpdateCheckReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            401,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val intervalMs = AlarmManager.INTERVAL_HALF_DAY
        alarmManager.setInexactRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 15 * 60_000L,
            intervalMs,
            pending
        )
    }

    fun notifyIfNewUpdate(context: Context, info: AppUpdateInfo) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastNotified = prefs.getLong(KEY_NOTIFIED_CODE, -1L)
        if (lastNotified >= info.code) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Обновления ${AppMeta.APP_NAME}",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления о выходе новой версии на GitHub"
            }
            nm.createNotificationChannel(channel)
        }
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("extra_check_update", true)
        }
        val contentPending = PendingIntent.getActivity(
            context,
            402,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = buildString {
            append("Доступна версия v${info.name} (разработчик ${AppMeta.DEVELOPER}).")
            if (info.notes.isNotBlank()) {
                append(' ')
                append(info.notes.take(120))
            }
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Обновление ${AppMeta.APP_NAME} v${info.name}")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentPending)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
        prefs.edit().putLong(KEY_NOTIFIED_CODE, info.code).apply()
    }
}

class UpdateCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        executor.execute {
            try {
                UpdateChecker.checkBlocking(context).getOrNull()?.let { info ->
                    UpdateChecker.notifyIfNewUpdate(context, info)
                }
            } catch (_: Exception) {
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val executor = Executors.newSingleThreadExecutor()
    }
}

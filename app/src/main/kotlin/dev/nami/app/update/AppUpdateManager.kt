package dev.nami.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AppUpdateManager"
private const val GITHUB_RELEASES_URL = "https://api.github.com/repos/MozzarellaCheesee/Nami/releases/latest"

data class UpdateInfo(
    val tagName: String,
    val versionName: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkSizeBytes: Long,
    val isNewer: Boolean,
)

sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class Available(val info: UpdateInfo) : UpdateStatus()
    object UpToDate : UpdateStatus()
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : UpdateStatus()
    data class ReadyToInstall(val apkFile: File) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

@Singleton
class AppUpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status

    val currentVersionName: String = try {
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0)
        }
        packageInfo.versionName ?: "0.1.0"
    } catch (_: Exception) {
        "0.1.0"
    }

    suspend fun checkForUpdates(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        _status.value = UpdateStatus.Checking
        runCatching {
            val request = Request.Builder()
                .url(GITHUB_RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Nami-Android-App")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val msg = "GitHub API вернул статус: ${response.code}"
                _status.value = UpdateStatus.Error(msg)
                error(msg)
            }

            val body = response.body?.string() ?: error("Пустой ответ от GitHub API")
            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "")
            val bodyText = json.optString("body", "")
            val assets = json.optJSONArray("assets")

            var apkUrl: String? = null
            var apkSize = 0L
            var apkVersion: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        apkSize = asset.optLong("size", 0L)
                        apkVersion = androidVersionFromApkName(name)
                        break
                    }
                }
            }

            if (apkUrl.isNullOrBlank()) {
                _status.value = UpdateStatus.UpToDate
                return@runCatching null
            }

            // Один GitHub-релиз содержит и сервер, и Android. Поэтому tag_name (например,
            // v1.0.7) — версия сервера, а версию приложения берём из имени APK.
            val releaseVersion = apkVersion ?: tagName.removePrefix("v")
            val isNewer = isVersionNewer(releaseVersion, currentVersionName)
            val info = UpdateInfo(
                tagName = tagName,
                versionName = releaseVersion,
                releaseNotes = bodyText,
                apkDownloadUrl = apkUrl,
                apkSizeBytes = apkSize,
                isNewer = isNewer,
            )

            if (isNewer) {
                _status.value = UpdateStatus.Available(info)
            } else {
                _status.value = UpdateStatus.UpToDate
            }
            info
        }.onFailure { e ->
            Log.e(TAG, "Error checking for updates", e)
            _status.value = UpdateStatus.Error(e.message ?: "Ошибка проверки обновлений")
        }
    }

    suspend fun downloadAndInstall(info: UpdateInfo) = withContext(Dispatchers.IO) {
        runCatching {
            _status.value = UpdateStatus.Downloading(0, 0, info.apkSizeBytes)
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val apkFile = File(updatesDir, "nami-${info.tagName}.apk")

            val request = Request.Builder()
                .url(info.apkDownloadUrl)
                .header("User-Agent", "Nami-Android-App")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) error("Ошибка скачивания: HTTP ${response.code}")

            val body = response.body ?: error("Пустое тело ответа при скачивании APK")
            val totalBytes = if (info.apkSizeBytes > 0) info.apkSizeBytes else body.contentLength()

            body.byteStream().use { input ->
                FileOutputStream(apkFile).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesCopied = 0L
                    var read: Int
                    var lastReportTime = 0L

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesCopied += read
                        val now = System.currentTimeMillis()
                        if (now - lastReportTime > 200 || bytesCopied == totalBytes) {
                            lastReportTime = now
                            val progress = if (totalBytes > 0) {
                                ((bytesCopied * 100) / totalBytes).toInt().coerceIn(0, 100)
                            } else 50
                            _status.value = UpdateStatus.Downloading(progress, bytesCopied, totalBytes)
                        }
                    }
                }
            }

            _status.value = UpdateStatus.ReadyToInstall(apkFile)
            withContext(Dispatchers.Main) {
                installApk(apkFile)
            }
        }.onFailure { e ->
            Log.e(TAG, "Error downloading update", e)
            _status.value = UpdateStatus.Error(e.message ?: "Ошибка скачивания обновления")
        }
    }

    fun installApk(apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    return
                }
            }

            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile,
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            _status.value = UpdateStatus.Error("Не удалось запустить установщик: ${e.message}")
        }
    }

    fun dismiss() {
        _status.value = UpdateStatus.Idle
    }

}

internal fun androidVersionFromApkName(name: String): String? =
    Regex("""(?i)(\d+\.\d+\.\d+(?:-(?:alpha|beta|rc)(?:[.-]\d+)?)?)""")
        .find(name)
        ?.groupValues
        ?.get(1)

internal fun isVersionNewer(latestTag: String, currentVersion: String): Boolean {
    val latest = latestTag.trimStart('v', 'V').trim().lowercase()
    val current = currentVersion.trimStart('v', 'V').trim().lowercase()
    if (latest == current) return false

    val latestCore = latest.substringBefore('-').split('.').mapNotNull(String::toIntOrNull)
    val currentCore = current.substringBefore('-').split('.').mapNotNull(String::toIntOrNull)
    repeat(maxOf(latestCore.size, currentCore.size)) { index ->
        val difference = latestCore.getOrElse(index) { 0 } - currentCore.getOrElse(index) { 0 }
        if (difference != 0) return difference > 0
    }

    val latestPre = latest.substringAfter('-', "")
    val currentPre = current.substringAfter('-', "")
    if (latestPre.isEmpty()) return currentPre.isNotEmpty()
    if (currentPre.isEmpty()) return false
    val latestNumber = latestPre.substringAfterLast('.').toIntOrNull() ?: 0
    val currentNumber = currentPre.substringAfterLast('.').toIntOrNull() ?: 0
    return latestNumber > currentNumber
}

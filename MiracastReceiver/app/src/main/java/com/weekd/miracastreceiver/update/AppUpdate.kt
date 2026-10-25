package com.weekd.miracastreceiver.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * 应用内自动更新：检查上游 GitHub Releases，下载对应架构的 APK 并调起安装。
 *
 * 更新源：weekdayjast/MiracastReceiver 的公开 Releases（无需 token）。
 * 版本比较：用 tag 的语义版本号（如 v1.2.7）与本机 versionName 比较，
 * 因为上游 Release 文件名里不带 versionCode，无法按构建号比较。
 * 架构选择：按本机首选 ABI（arm64-v8a / armeabi-v7a）挑对应包，找不到则回退 universal 包。
 */
object AppUpdate {

    private const val RELEASES_LATEST_URL =
        "https://api.github.com/repos/weekdayjast/MiracastReceiver/releases/latest"
    private const val PREFS_NAME = "app_update"
    private const val KEY_NOTIFIED_VERSION = "notified_version"

    private val httpClient = OkHttpClient()

    data class UpdateInfo(
        val versionName: String,
        val downloadUrl: String,
        val size: Long,
        val fileName: String,
    ) {
        val displaySize: String
            get() = if (size < 1024 * 1024) {
                "%.1f KB".format(size / 1024.0)
            } else {
                "%.1f MB".format(size / (1024.0 * 1024.0))
            }
    }

    /** 检查结果：区分“已是最新”“检查失败”“有新版”，避免失败时误报已是最新。 */
    sealed class CheckResult {
        object UpToDate : CheckResult()
        data class Failed(val reason: String) : CheckResult()
        data class Available(val info: UpdateInfo) : CheckResult()
    }

    enum class InstallOutcome { STARTED, NEEDS_PERMISSION, FAILED }

    fun currentVersionName(context: Context): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: ""
        } catch (e: Exception) {
            Timber.w(e, "读取本机版本失败")
            ""
        }
    }

    /** 语义版本比较：a > b 返回正数，a == b 返回 0，a < b 返回负数。 */
    fun compareVersion(a: String, b: String): Int {
        fun parts(version: String): List<Int> {
            val core = version.trim().removePrefix("v").substringBefore("-")
            return core.split(".").map { it.toIntOrNull() ?: 0 }
        }
        val pa = parts(a)
        val pb = parts(b)
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val va = pa.getOrNull(i) ?: 0
            val vb = pb.getOrNull(i) ?: 0
            if (va != vb) return va - vb
        }
        return 0
    }

    private fun pickAsset(assets: List<JSONObject>): JSONObject? {
        if (assets.isEmpty()) return null
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
        // 优先本机架构，其次 universal 包，最后任意 apk
        return assets.firstOrNull { it.optString("name").contains("-$abi.apk") }
            ?: assets.firstOrNull { it.optString("name").contains("universal") }
            ?: assets.firstOrNull { it.optString("name").endsWith(".apk") }
    }

    suspend fun checkForUpdate(context: Context): CheckResult {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(RELEASES_LATEST_URL)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext CheckResult.Failed("服务端返回 ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                response.close()
                val json = JSONObject(body)
                val tag = json.optString("tag_name").ifEmpty { return@withContext CheckResult.Failed("未取到版本号") }
                val remoteVersion = tag.removePrefix("v")
                val current = currentVersionName(context)
                if (current.isEmpty()) {
                    return@withContext CheckResult.Failed("无法读取本机版本")
                }
                if (compareVersion(remoteVersion, current) <= 0) {
                    return@withContext CheckResult.UpToDate
                }
                val assetsJson = json.optJSONArray("assets") ?: return@withContext CheckResult.Failed("该版本无安装包")
                val assets = (0 until assetsJson.length()).map { assetsJson.getJSONObject(it) }
                val asset = pickAsset(assets) ?: return@withContext CheckResult.Failed("没有匹配本机架构的安装包")
                val downloadUrl = asset.optString("browser_download_url")
                if (downloadUrl.isEmpty()) {
                    return@withContext CheckResult.Failed("安装包下载地址为空")
                }
                CheckResult.Available(
                    UpdateInfo(
                        versionName = remoteVersion,
                        downloadUrl = downloadUrl,
                        size = asset.optLong("size"),
                        fileName = asset.optString("name"),
                    ),
                )
            } catch (e: Exception) {
                Timber.w(e, "检查更新失败")
                CheckResult.Failed("网络异常：${e.message ?: "未知错误"}")
            }
        }
    }

    /** 下载 APK，onProgress 回调 0~100 的进度。 */
    suspend fun downloadUpdate(
        context: Context,
        info: UpdateInfo,
        onProgress: (Int) -> Unit,
    ): File? {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(info.downloadUrl)
                    .header("Accept", "application/octet-stream")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Timber.w("下载更新失败：HTTP ${response.code}")
                    return@withContext null
                }
                val body = response.body ?: return@withContext null
                val total = body.contentLength()
                val file = File(context.cacheDir, "update.apk")
                if (file.exists()) file.delete()
                body.byteStream().use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var received = 0L
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            output.write(buffer, 0, read)
                            received += read
                            if (total > 0) {
                                onProgress((received * 100 / total).toInt().coerceIn(0, 100))
                            }
                        }
                    }
                }
                onProgress(100)
                file
            } catch (e: Exception) {
                Timber.w(e, "下载更新失败")
                null
            }
        }
    }

    /** 调起系统安装。未授予“安装未知应用”权限时先跳转设置页，由用户授权后重新点更新。 */
    fun installApk(context: Context, file: File): InstallOutcome {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()
            ) {
                val settingsIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                context.startActivity(settingsIntent)
                return InstallOutcome.NEEDS_PERMISSION
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
            InstallOutcome.STARTED
        } catch (e: Exception) {
            Timber.w(e, "调起安装失败")
            InstallOutcome.FAILED
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun alreadyNotified(context: Context, version: String): Boolean =
        prefs(context).getString(KEY_NOTIFIED_VERSION, "") == version

    fun markNotified(context: Context, version: String) {
        prefs(context).edit().putString(KEY_NOTIFIED_VERSION, version).apply()
    }
}

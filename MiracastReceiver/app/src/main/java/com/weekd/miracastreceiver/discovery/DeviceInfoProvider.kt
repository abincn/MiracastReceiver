package com.weekd.miracastreceiver.discovery

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.util.AppSettings
import com.weekd.miracastreceiver.utils.CodecUtils
import com.weekd.miracastreceiver.utils.NetworkUtils

/**
 * 设备信息提供器
 */
class DeviceInfoProvider(private val context: Context) {

    /**
     * 对外显示的设备名，AirPlay / DLNA / Miracast 共用。
     *
     * 默认（AppSettings 开关开启，无需手动输入）：「App 名 + 型号」，
     * 例如「Miracast Receiver XR-65X91K」。App 名取 R.string.app_name，
     * 型号取 Build.MODEL。只改本应用的广播名，不改电视系统名，
     * 所以电视自带投屏不会跟着变 —— 同一台电视上出现两个同名
     * 条目时，靠这个前缀区分。
     * 开关关闭时退回：系统设置里的设备名称（Settings.Global
     * `device_name`，即电视「设置 → 关于 → 设备名称」）>「厂商 型号」。
     * 常量 Settings.Global.DEVICE_NAME 是 API 25 才加的，minSdk 21 所以直接用键名。
     */
    fun getDeviceName(): String {
        val useAppModelName = try {
            AppSettings.isUseAppModelName(context)
        } catch (e: Exception) {
            true
        }
        if (useAppModelName) {
            val appName = try {
                context.getString(R.string.app_name)
            } catch (e: Exception) {
                "MiracastReceiver"
            }.trim()
            val model = Build.MODEL?.trim().orEmpty()
            return listOf(appName, model).filter { it.isNotEmpty() }.joinToString(" ")
                .ifEmpty { "Android TV" }
        }
        val systemName = try {
            Settings.Global.getString(context.contentResolver, "device_name")
        } catch (e: Exception) {
            null
        }
        return systemName?.trim()?.takeIf { it.isNotEmpty() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifEmpty { "Android TV" }
    }

    fun getDeviceId(): String {
        return "${Build.MANUFACTURER}-${Build.MODEL}-${Build.SERIAL}"
            .replace(" ", "-")
            .replace("/", "-")
    }

    fun getDeviceInfo(): Map<String, String> {
        val videoConfig = CodecUtils.getRecommendedVideoConfig()

        return mapOf(
            "name" to getDeviceName(),
            "model" to Build.MODEL,
            "manufacturer" to Build.MANUFACTURER,
            "android_version" to Build.VERSION.RELEASE,
            "sdk" to Build.VERSION.SDK_INT.toString(),
            "ip" to (NetworkUtils.getLocalIpAddress() ?: "unknown"),
            "h264" to CodecUtils.isVideoDecoderSupported(CodecUtils.MIME_VIDEO_H264).toString(),
            "h265" to CodecUtils.isVideoDecoderSupported(CodecUtils.MIME_VIDEO_H265).toString(),
            "recommended_codec" to videoConfig.mimeType,
            "max_width" to videoConfig.width.toString(),
            "max_height" to videoConfig.height.toString(),
            "max_fps" to videoConfig.frameRate.toString()
        )
    }
}

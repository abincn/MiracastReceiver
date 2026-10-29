package com.weekd.miracastreceiver.discovery

import android.content.Context
import android.os.Build
import android.provider.Settings
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
     * 优先级：本应用自定义投屏名称（AppSettings，用户在主界面里改）>
     * 系统设置里的设备名称（Settings.Global `device_name`，即电视「设置 → 关于 → 设备名称」）>
     * 「厂商 型号」。
     * 自定义名称只改本应用的广播名，不改电视系统名，所以电视自带投屏
     * 不会跟着变 —— 同一台电视上出现两个同名条目时，靠它区分。
     * 常量 Settings.Global.DEVICE_NAME 是 API 25 才加的，minSdk 21 所以直接用键名。
     */
    fun getDeviceName(): String {
        val customName = try {
            AppSettings.getCustomDisplayName(context)
        } catch (e: Exception) {
            ""
        }
        if (customName.isNotEmpty()) return customName
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

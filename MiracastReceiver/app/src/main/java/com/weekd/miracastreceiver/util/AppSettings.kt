package com.weekd.miracastreceiver.util

import android.content.Context

/**
 * 应用设置（用户可在主界面里改的开关）。
 *
 * 单独放在 SharedPreferences 里，和 AirPlay 配对信息、设备 UUID 那几个
 * 内部用的 prefs 文件分开，避免清配对时误删用户设置。
 */
object AppSettings {

    private const val PREFS_NAME = "miracast_settings"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_CUSTOM_DISPLAY_NAME = "custom_display_name"

    /**
     * 是否开机自动启动接收服务。
     *
     * 默认开启：这个应用装在电视上就是当常驻投屏接收器用的，
     * 每次重启电视都要手动打开一次不符合使用场景。
     */
    fun isAutoStartOnBoot(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_START_ON_BOOT, true)

    fun setAutoStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply()
    }

    /**
     * 用户自定义的投屏名称，空串表示跟随电视系统名称。
     * 只影响本应用对外广播的名字（AirPlay / DLNA / Miracast 共用，
     * 见 DeviceInfoProvider），不改电视系统设备名，所以不会把电视
     * 自带投屏也一起改名 —— 这正是用来区分两个同名条目的。
     */
    fun getCustomDisplayName(context: Context): String =
        prefs(context).getString(KEY_CUSTOM_DISPLAY_NAME, "")?.trim() ?: ""

    fun setCustomDisplayName(context: Context, name: String) {
        prefs(context).edit().putString(KEY_CUSTOM_DISPLAY_NAME, name.trim()).apply()
    }

    /** 界面语言的 BCP 47 标签，空串表示跟随系统。由 [AppLanguage] 读写。 */
    fun getLanguageTag(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, "") ?: ""

    fun setLanguageTag(context: Context, tag: String) {
        prefs(context).edit().putString(KEY_LANGUAGE, tag).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}

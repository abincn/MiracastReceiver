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
    private const val KEY_USE_APP_MODEL_NAME = "use_app_model_name"

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
     * 是否用「App 名 + 型号」作为投屏名称，默认开启。
     * 1.2.9.1 的手动输入框已废弃：电视遥控器输名字、点保存后
     * 流程走不下去（对话框焦点 + 服务即时重启都不可靠），
     * 所以改成不需要输入的自动名字。旧的 custom_display_name
     * 已不再读取，残留值不会再影响广播名。
     * 开启时只改本应用的广播名，不改电视系统设备名，所以电视
     * 自带投屏不会跟着变；关闭时跟随电视系统名。
     * 注意：服务启动时才读一次名字，开关改动需重启应用后生效，
     * 本处故意不做即时重启服务，避免重蹈 1.2.9.1 的覆辙。
     */
    fun isUseAppModelName(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_APP_MODEL_NAME, true)

    fun setUseAppModelName(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_APP_MODEL_NAME, enabled).apply()
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

package com.weekd.miracastreceiver.dlna

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DLNA 诊断：主界面上能看到的最近一次请求。
 *
 * 服务和主界面在同一个进程里，所以这个单例两边都能读写。
 * 目的就是回答“投屏那一下到底有没有到电视”：
 * 投完如果这里还是“暂无请求”，说明发送端根本没把 SetURI 发到这台电视
 * （点错了设备、订阅失败、网络问题等）；如果这里有 SetURI，
 * 说明问题在拉起播放页或播地址那一步。
 */
object DlnaDiagnostic {
    @Volatile
    var lastRequest: String = ""
        private set

    @Volatile
    var lastError: String = ""
        private set

    fun recordRequest(text: String) {
        lastRequest = "${time()} $text".take(500)
    }

    fun recordError(text: String) {
        lastError = "${time()} $text".take(500)
    }

    fun summary(): String {
        val request = if (lastRequest.isEmpty()) "暂无请求（投一次试试）" else lastRequest
        return if (lastError.isEmpty()) {
            "DLNA 日志：$request"
        } else {
            "DLNA 日志：$request\n错误：$lastError"
        }
    }

    private fun time(): String {
        return try {
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        } catch (e: Exception) {
            ""
        }
    }
}

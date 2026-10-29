package com.weekd.miracastreceiver.dlna

import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * DLNA 诊断：主界面下方滚动日志框里显示的历史记录。
 *
 * 服务和主界面在同一个进程里，所以这个单例两边都能读写。
 * 1.2.9.3 只存最近一条，投完回来容易被后续 SUBSCRIBE / 轮询盖掉；
 * 1.2.9.4 改存最近 100 条，按时间顺序追加，SetURI 不会再被订阅盖掉。
 */
object DlnaDiagnostic {
    private const val MAX_ENTRIES = 100
    private val history = ArrayDeque<String>()
    private val lock = Any()

    @Volatile
    var lastRequest: String = ""
        private set

    @Volatile
    var lastError: String = ""
        private set

    fun recordRequest(text: String) {
        val entry = "${time()} $text".take(500)
        lastRequest = entry
        synchronized(lock) {
            history.addLast(entry)
            while (history.size > MAX_ENTRIES) {
                history.removeFirst()
            }
        }
    }

    fun recordError(text: String) {
        val entry = "${time()} 错误：$text".take(500)
        lastError = entry
        synchronized(lock) {
            history.addLast(entry)
            while (history.size > MAX_ENTRIES) {
                history.removeFirst()
            }
        }
    }

    fun historyText(): String {
        synchronized(lock) {
            return if (history.isEmpty()) {
                "暂无请求（投一次试试）"
            } else {
                history.joinToString("\n")
            }
        }
    }

    fun historySize(): Int {
        synchronized(lock) {
            return history.size
        }
    }

    fun summary(): String {
        return "DLNA 日志：\n" + historyText()
    }

    private fun time(): String {
        return try {
            SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        } catch (e: Exception) {
            ""
        }
    }
}

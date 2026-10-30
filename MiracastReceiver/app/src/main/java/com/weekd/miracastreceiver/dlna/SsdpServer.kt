package com.weekd.miracastreceiver.dlna

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * SSDP (Simple Service Discovery Protocol) 服务器
 * 用于 DLNA/UPnP 设备发现
 */
class SsdpServer(
    private val context: Context,
    private val deviceUuid: String,
    private val localIp: String,
    private val httpPort: Int = 8080
) {
    companion object {
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SSDP_SEARCH_PATTERN = "M-SEARCH"

        // UPnP Device Types
        private const val DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1"
        private const val SERVICE_TYPE_AV = "urn:schemas-upnp-org:service:AVTransport:1"
        private const val SERVICE_TYPE_RC = "urn:schemas-upnp-org:service:RenderingControl:1"
        private const val SERVICE_TYPE_CM = "urn:schemas-upnp-org:service:ConnectionManager:1"
    }

    private var multicastSocket: MulticastSocket? = null
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    fun start() {
        if (serverJob?.isActive == true) {
            Timber.w("SSDP server already running")
            return
        }

        serverJob = scope.launch {
            try {
                multicastSocket = MulticastSocket(SSDP_PORT).apply {
                    reuseAddress = true

                    // 加入多播组
                    val group = InetAddress.getByName(SSDP_ADDRESS)
                    joinGroup(InetSocketAddress(group, SSDP_PORT), null)
                }

                Timber.i("SSDP server started on port $SSDP_PORT")

                val buffer = ByteArray(1024)
                while (multicastSocket?.isClosed == false) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    multicastSocket?.receive(packet)

                    val message = String(packet.data, 0, packet.length)
                    if (message.contains(SSDP_SEARCH_PATTERN)) {
                        handleSearchRequest(message, packet.address, packet.port)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "SSDP server error")
            }
        }

        // 启动定期广播
        startPeriodicNotify()
    }

    fun stop() {
        try {
            sendByebye()
            serverJob?.cancel()
            multicastSocket?.close()
            Timber.i("SSDP server stopped")
        } catch (e: Exception) {
            Timber.e(e, "Error stopping SSDP server")
        }
    }

    private fun handleSearchRequest(message: String, address: InetAddress, port: Int) {
        Timber.d("SSDP search from ${address.hostAddress}:$port")

        // 检查搜索目标
        val searchTarget = extractSearchTarget(message)

        // 1.2.9.5：把每一次 M-SEARCH 都写进主界面滚动日志。
        // 微信视频号搜不到时，这一行是关键证据：
        // 有这一行 = 微信发了 DLNA 搜索、电视收到了，问题在回包/过滤；
        // 没有这一行 = 微信根本没走 DLNA SSDP（或组播被拦），再查协议/网络。
        DlnaDiagnostic.recordRequest(
            "SSDP 搜索 ST=${searchTarget ?: "(无 ST)"} 来自 ${address.hostAddress}"
        )

        val shouldRespond = when {
            searchTarget == null -> false
            searchTarget == "ssdp:all" -> true
            searchTarget == "upnp:rootdevice" -> true
            searchTarget.contains("MediaRenderer") -> true
            searchTarget.contains("AVTransport") -> true
            searchTarget.contains("RenderingControl") -> true
            searchTarget.contains("ConnectionManager") -> true
            searchTarget == "uuid:$deviceUuid" -> true
            else -> false
        }

        if (shouldRespond) {
            if (searchTarget == "ssdp:all") {
                // 1.2.9.5：按 UPnP 规范，ssdp:all 应回多条（每设备/每服务一条），
                // 原实现只回一条 ST=ssdp:all，严格客户端可能据此丢弃。
                // 回包顺序与 NOTIFY 一致：rootdevice、uuid、设备、三个服务。
                val targets = listOf(
                    "upnp:rootdevice",
                    "uuid:$deviceUuid",
                    DEVICE_TYPE,
                    SERVICE_TYPE_AV,
                    SERVICE_TYPE_RC,
                    SERVICE_TYPE_CM
                )
                for (target in targets) {
                    sendSearchResponse(address, port, target)
                }
                DlnaDiagnostic.recordRequest("SSDP 回复 ssdp:all 共 6 条给 ${address.hostAddress}")
            } else {
                sendSearchResponse(address, port, searchTarget ?: "upnp:rootdevice")
                DlnaDiagnostic.recordRequest("SSDP 回复 ST=$searchTarget 给 ${address.hostAddress}")
            }
        } else {
            DlnaDiagnostic.recordRequest("SSDP 未回复 ST=$searchTarget（不在支持列表）")
        }
    }

    private fun httpDate(): String {
        return try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("GMT")
            }.format(Date())
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractSearchTarget(message: String): String? {
        // 1.2.9.5：同时按 \r\n 和 \n 切行，原实现只按 \r\n 切，
        // 只发 \n 的客户端会被误判为无 ST 而不回复。
        val lines = message.split("\r\n", "\n")
        for (line in lines) {
            if (line.trimStart().startsWith("ST:", ignoreCase = true)) {
                return line.substringAfter(":", "").trim()
                    .trim('"')
            }
        }
        return null
    }

    private fun sendSearchResponse(address: InetAddress, port: Int, searchTarget: String) {
        val location = "http://$localIp:$httpPort/device.xml"

        val response = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("CACHE-CONTROL: max-age=1800\r\n")
            append("DATE: ${httpDate()}\r\n")
            append("EXT:\r\n")
            append("LOCATION: $location\r\n")
            append("SERVER: Android/11 UPnP/1.0 MiracastReceiver/1.0\r\n")
            append("ST: $searchTarget\r\n")
            append("USN: uuid:$deviceUuid")

            // 根据 ST 添加不同的 USN
            when {
                searchTarget == "upnp:rootdevice" -> append("::upnp:rootdevice")
                searchTarget.contains("device:") -> append("::$searchTarget")
                searchTarget.contains("service:") -> append("::$searchTarget")
            }

            append("\r\n\r\n")
        }

        try {
            val socket = DatagramSocket()
            val data = response.toByteArray()
            val packet = DatagramPacket(data, data.size, address, port)
            socket.send(packet)
            socket.close()
            Timber.d("Sent SSDP response to ${address.hostAddress}:$port")
        } catch (e: Exception) {
            Timber.e(e, "Error sending SSDP response")
        }
    }

    private fun startPeriodicNotify() {
        scope.launch {
            try {
                kotlinx.coroutines.delay(2000) // 启动后等待2秒
                sendNotify()

                // 每30分钟发送一次 NOTIFY
                while (true) {
                    kotlinx.coroutines.delay(30 * 60 * 1000)
                    sendNotify()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error in periodic notify")
            }
        }
    }

    private fun sendNotify() {
        val location = "http://$localIp:$httpPort/device.xml"

        val notifications = listOf(
            "upnp:rootdevice",
            "uuid:$deviceUuid",
            DEVICE_TYPE,
            SERVICE_TYPE_AV,
            SERVICE_TYPE_RC,
            SERVICE_TYPE_CM
        )

        try {
            val socket = DatagramSocket()
            val group = InetAddress.getByName(SSDP_ADDRESS)

            for (nt in notifications) {
                val message = buildString {
                    append("NOTIFY * HTTP/1.1\r\n")
                    append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                    append("CACHE-CONTROL: max-age=1800\r\n")
                    append("LOCATION: $location\r\n")
                    append("NT: $nt\r\n")
                    append("NTS: ssdp:alive\r\n")
                    append("SERVER: Android/11 UPnP/1.0 MiracastReceiver/1.0\r\n")
                    append("USN: uuid:$deviceUuid")

                    if (nt != "uuid:$deviceUuid") {
                        append("::$nt")
                    }

                    append("\r\n\r\n")
                }

                val data = message.toByteArray()
                val packet = DatagramPacket(data, data.size, group, SSDP_PORT)
                socket.send(packet)
            }

            socket.close()
            Timber.i("Sent SSDP NOTIFY messages")
        } catch (e: Exception) {
            Timber.e(e, "Error sending SSDP notify")
        }
    }

    private fun sendByebye() {
        val notifications = listOf(
            "upnp:rootdevice",
            "uuid:$deviceUuid",
            DEVICE_TYPE,
            SERVICE_TYPE_AV,
            SERVICE_TYPE_RC,
            SERVICE_TYPE_CM
        )

        try {
            val socket = DatagramSocket()
            val group = InetAddress.getByName(SSDP_ADDRESS)

            for (nt in notifications) {
                val message = buildString {
                    append("NOTIFY * HTTP/1.1\r\n")
                    append("HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n")
                    append("NT: $nt\r\n")
                    append("NTS: ssdp:byebye\r\n")
                    append("USN: uuid:$deviceUuid")

                    if (nt != "uuid:$deviceUuid") {
                        append("::$nt")
                    }

                    append("\r\n\r\n")
                }

                val data = message.toByteArray()
                val packet = DatagramPacket(data, data.size, group, SSDP_PORT)
                socket.send(packet)
            }

            socket.close()
            Timber.i("Sent SSDP byebye messages")
        } catch (e: Exception) {
            Timber.e(e, "Error sending SSDP byebye")
        }
    }
}

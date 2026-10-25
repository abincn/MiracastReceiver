package com.weekd.miracastreceiver.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.widget.Button
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.weekd.miracastreceiver.R
import com.weekd.miracastreceiver.discovery.DeviceInfoProvider
import com.weekd.miracastreceiver.discovery.MdnsAdvertiser
import com.weekd.miracastreceiver.service.CastReceiverService
import com.weekd.miracastreceiver.update.AppUpdate
import com.weekd.miracastreceiver.util.AppSettings
import com.weekd.miracastreceiver.utils.NetworkUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import android.widget.TextView

/**
 * 主 Activity - 等待投屏连接
 */
class MainActivity : AppCompatActivity() {

    private lateinit var mdnsAdvertiser: MdnsAdvertiser
    private lateinit var deviceInfoProvider: DeviceInfoProvider

    private lateinit var tvDeviceName: TextView
    private lateinit var tvDeviceIp: TextView
    private lateinit var tvConnectionCode: TextView
    private lateinit var tvStatus: TextView
    private lateinit var switchAutoStart: SwitchCompat
    private lateinit var tvVersion: TextView
    private lateinit var btnCheckUpdate: Button

    private var connectionCode: String = ""

    /** 定位权限弹窗还在时不叠加悬浮窗权限提示，等它有结果再说 */
    private var wifiPermissionPending = false
    private var overlayPromptShown = false
    private var checkingUpdate = false
    private var autoUpdateChecked = false

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1001

        /**
         * Wi-Fi Direct 需要的危险权限，必须运行时申请。
         *
         * 少了它 `WifiP2pManager.createGroup()` 会抛 SecurityException，Miracast 直接不可用
         * （AirPlay / DLNA 不受影响）。Android 13+ 用 NEARBY_WIFI_DEVICES 取代定位权限。
         */
        private val WIFI_DIRECT_PERMISSIONS: Array<String>
            get() = if (Build.VERSION.SDK_INT >= 33) {
                arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                // Android 12+ 只申请 FINE 会被系统直接拒绝，必须两个一起申请
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initServices()
        initViews()
        requestWifiDirectPermissions()
        checkNetworkAndStart()
        autoCheckUpdate()
    }

    private fun requestWifiDirectPermissions() {
        val missing = WIFI_DIRECT_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Timber.i("Requesting Wi-Fi Direct permissions: $missing")
            wifiPermissionPending = true
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return
        wifiPermissionPending = false

        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            // 权限是在服务启动之后才拿到的，重启一次服务让 Wi-Fi Direct 重新初始化
            Timber.i("Wi-Fi Direct permissions granted, restarting cast service")
            startCastService()
        } else {
            Timber.w("Wi-Fi Direct permissions denied — Miracast unavailable")
            tvStatus.text = "未授予定位权限，Windows 无线投屏不可用"
        }
    }

    override fun onResume() {
        super.onResume()
        if (!wifiPermissionPending) promptOverlayPermissionIfNeeded()
    }

    /**
     * 「显示在其他应用上层」权限。
     *
     * Android 10 起系统禁止后台启动 Activity，前台服务也不例外。开机自启或者用户按了
     * 主页键之后，应用不在前台，投屏连上时服务拉起播放页会被系统静默拦截 —— 声音、
     * 解码都在跑，屏幕上却什么都没有。持有这个权限的应用可以豁免该限制。
     */
    private fun promptOverlayPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (overlayPromptShown || Settings.canDrawOverlays(this)) return
        overlayPromptShown = true

        AlertDialog.Builder(this)
            .setTitle("需要「显示在其他应用上层」权限")
            .setMessage(
                "未打开本应用时（例如开机自启后、或退回桌面后），系统会阻止投屏画面自动弹出，" +
                    "表现为投屏没有反应。\n\n请在接下来的设置页中为本应用开启此权限。"
            )
            .setPositiveButton("去设置") { _, _ -> openOverlaySettings() }
            .setNegativeButton("稍后", null)
            .show()
    }

    private fun openOverlaySettings() {
        val withPackage = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        // 部分电视的设置页不认带包名的 Intent，退回到列表页让用户自己找
        val intents = listOf(withPackage, Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Timber.w("Overlay settings not available: ${intent.data}")
            }
        }
        Timber.w("No overlay permission settings page on this device")
        Toast.makeText(
            this,
            "此设备没有该设置页，请用 adb 执行：appops set $packageName SYSTEM_ALERT_WINDOW allow",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun initServices() {
        deviceInfoProvider = DeviceInfoProvider(this)
        mdnsAdvertiser = MdnsAdvertiser(this)

        // 生成连接码
        connectionCode = NetworkUtils.generateConnectionCode()
    }

    private fun initViews() {
        tvDeviceName = findViewById(R.id.tv_device_name)
        tvDeviceIp = findViewById(R.id.tv_device_ip)
        tvConnectionCode = findViewById(R.id.tv_connection_code)
        tvStatus = findViewById(R.id.tv_status)
        switchAutoStart = findViewById(R.id.switch_auto_start)

        // 开机自启开关
        switchAutoStart.isChecked = AppSettings.isAutoStartOnBoot(this)
        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            AppSettings.setAutoStartOnBoot(this, isChecked)
            Timber.i("Auto start on boot set to $isChecked")
        }

        // 应用更新：显示当前版本，按钮手动检查；启动时也会静默查一次
        tvVersion = findViewById(R.id.tv_version)
        btnCheckUpdate = findViewById(R.id.btn_check_update)
        tvVersion.text = getString(R.string.current_version, AppUpdate.currentVersionName(this))
        btnCheckUpdate.setOnClickListener { manualCheckUpdate() }

        // 设置设备名称
        val deviceName = deviceInfoProvider.getDeviceName()
        tvDeviceName.text = deviceName

        // 设置连接码
        tvConnectionCode.text = getString(R.string.connection_code, connectionCode)
    }

    /**
     * 手动检查更新：按钮触发。检查失败会明确提示失败原因，
     * 不会像“已是最新版本”那样把失败和无更新混在一起。
     */
    private fun manualCheckUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        btnCheckUpdate.isEnabled = false
        btnCheckUpdate.text = getString(R.string.checking_update)
        lifecycleScope.launch {
            try {
                when (val result = AppUpdate.checkForUpdate(this@MainActivity)) {
                    is AppUpdate.CheckResult.UpToDate -> {
                        Toast.makeText(this@MainActivity, R.string.update_already_latest, Toast.LENGTH_SHORT).show()
                    }
                    is AppUpdate.CheckResult.Failed -> {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.update_check_failed, result.reason),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                    is AppUpdate.CheckResult.Available -> {
                        AppUpdate.markNotified(this@MainActivity, result.info.versionName)
                        promptUpdate(result.info)
                    }
                }
            } finally {
                checkingUpdate = false
                btnCheckUpdate.isEnabled = true
                btnCheckUpdate.text = getString(R.string.check_update)
            }
        }
    }

    /**
     * 启动时静默检查一次：只有真有新版、且该版本没提示过时才弹窗，
     * 无更新或检查失败都不打扰。
     */
    private fun autoCheckUpdate() {
        if (autoUpdateChecked) return
        autoUpdateChecked = true
        lifecycleScope.launch {
            when (val result = AppUpdate.checkForUpdate(this@MainActivity)) {
                is AppUpdate.CheckResult.Available -> {
                    if (!AppUpdate.alreadyNotified(this@MainActivity, result.info.versionName)) {
                        AppUpdate.markNotified(this@MainActivity, result.info.versionName)
                        promptUpdate(result.info)
                    }
                }
                else -> Unit
            }
        }
    }

    private fun promptUpdate(info: AppUpdate.UpdateInfo) {
        val current = AppUpdate.currentVersionName(this)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title, info.versionName))
            .setMessage(getString(R.string.update_available_message, current, info.versionName, info.displaySize))
            .setPositiveButton(R.string.update_now) { _, _ -> downloadAndInstall(info) }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun downloadAndInstall(info: AppUpdate.UpdateInfo) {
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            setPadding(48, 32, 48, 8)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_downloading)
            .setView(progressBar)
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val file = AppUpdate.downloadUpdate(this@MainActivity, info) { progress ->
                progressBar.progress = progress
            }
            dialog.dismiss()
            if (file == null) {
                Toast.makeText(this@MainActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            when (AppUpdate.installApk(this@MainActivity, file)) {
                AppUpdate.InstallOutcome.NEEDS_PERMISSION -> {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.update_need_install_permission,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                AppUpdate.InstallOutcome.FAILED -> {
                    Toast.makeText(this@MainActivity, R.string.update_install_failed, Toast.LENGTH_LONG).show()
                }
                AppUpdate.InstallOutcome.STARTED -> Unit
            }
        }
    }

    private fun checkNetworkAndStart() {
        if (!NetworkUtils.isNetworkAvailable(this)) {
            tvStatus.text = "网络未连接"
            Timber.w("Network not available")
            return
        }

        if (!NetworkUtils.isWifiConnected(this)) {
            tvStatus.text = "未连接到 Wi-Fi"
            Timber.w("WiFi not connected")
        }

        // 获取 IP 地址
        val ipAddress = NetworkUtils.getLocalIpAddress()
        if (ipAddress != null) {
            tvDeviceIp.text = getString(R.string.device_ip, ipAddress)
            Timber.i("Local IP: $ipAddress")
        } else {
            tvDeviceIp.text = getString(R.string.device_ip, "获取中...")
        }

        // 启动服务
        startCastService()
        startAdvertising()

        // 更新状态
        updateStatus()
    }

    private fun startCastService() {
        val intent = Intent(this, CastReceiverService::class.java)
        startService(intent)
        Timber.i("Cast receiver service started")
    }

    private fun startAdvertising() {
        val deviceInfo = deviceInfoProvider.getDeviceInfo()
        val deviceName = deviceInfoProvider.getDeviceName()
        val macAddress = NetworkUtils.getMacAddress()

        // AirPlay is advertised by CastReceiverService/AirPlayReceiver only.
        // Do not register a second _airplay._tcp service here: duplicate AirPlay mDNS
        // records with different TXT capabilities can make iOS connect with the wrong
        // protocol path and never send the mirroring video SETUP.

        // 启动自定义 Miracast 服务广播（用于自定义 Android 发送端）
        mdnsAdvertiser.startAdvertising(
            serviceName = deviceName,
            port = 8080,
            deviceInfo = deviceInfo + ("code" to connectionCode)
        )

        Timber.i("Started AirPlay and Miracast advertising: $deviceName")
    }

    private fun updateStatus() {
        lifecycleScope.launch {
            while (true) {
                if (mdnsAdvertiser.isAdvertising()) {
                    tvStatus.text = getString(R.string.waiting_connection)
                } else {
                    tvStatus.text = "服务启动中..."
                }
                delay(2000)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mdnsAdvertiser.stopAdvertising()
        Timber.i("MainActivity destroyed")
    }
}

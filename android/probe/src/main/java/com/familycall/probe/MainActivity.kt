package com.familycall.probe

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Phase 0 hardware probe (PLAN.md section 5). One screen, D-pad only: the report on the
 * left, a live preview and six buttons on the right. Inventory scans run on launch; the three
 * live tests (Camera2 preview, UVC preview, record-and-play) run when their button is pressed.
 */
class MainActivity : Activity() {

    private lateinit var reportView: TextView
    private lateinit var statusView: TextView
    private lateinit var previewBox: FrameLayout

    private val ui = Handler(Looper.getMainLooper())
    private val report = Report()

    private var usbScan: Scans.UsbResult? = null
    private var camera2Scan: Scans.Camera2Result? = null
    private var audioScan: Scans.AudioResult? = null

    private var camera2Test: Camera2PreviewTest? = null
    private var uvcTest: UvcPreviewTest? = null
    private var audioTest: AudioLoopTest? = null

    private var afterPermission: (() -> Unit)? = null

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted && device != null) {
                runUvcTest(device)
            } else {
                finishTest(Report.SUM_TEST_UVC, Report.SEC_TEST_UVC, TestResult(false, "失败：USB 授权被拒绝", "系统弹出的 USB 授权框被拒绝或被取消。"))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)
        reportView = findViewById(R.id.reportText)
        statusView = findViewById(R.id.statusText)
        previewBox = findViewById(R.id.previewBox)

        findViewById<View>(R.id.btnCamera2).setOnClickListener { startCamera2Test() }
        findViewById<View>(R.id.btnUvc).setOnClickListener { startUvcTest() }
        findViewById<View>(R.id.btnAudio).setOnClickListener { startAudioTest() }
        findViewById<View>(R.id.btnStop).setOnClickListener {
            stopPreviewTests()
            status("预览已停止。")
        }
        findViewById<View>(R.id.btnScan).setOnClickListener { scan() }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveReport(announce = true) }
        findViewById<View>(R.id.btnCamera2).requestFocus()

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            // The exported/not-exported flag only exists (and is only required) from API 33.
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(usbPermissionReceiver, filter)
        }

        for (item in listOf(Report.SUM_TEST_CAMERA2, Report.SUM_TEST_UVC, Report.SUM_TEST_AUDIO)) {
            report.setSummary(item, Report.NOT_TESTED)
        }
        scan()
    }

    override fun onStop() {
        super.onStop()
        // Release the camera when the app leaves the screen.
        stopPreviewTests()
        audioTest?.cancel()
        audioTest = null
    }

    override fun onDestroy() {
        unregisterReceiver(usbPermissionReceiver)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ scan

    private fun scan() {
        status("正在扫描硬件…")
        Thread({
            val device = Scans.deviceInfo(this)
            val usb = Scans.usb(this)
            val camera2 = Scans.camera2(this)
            val audio = Scans.audioInputs(this)
            val codecs = Scans.codecs()
            ui.post {
                usbScan = usb
                camera2Scan = camera2
                audioScan = audio

                report.setSummary(Report.SUM_USB_VIDEO, if (usb.videoDevices.isEmpty()) "未找到" else "找到 ${usb.videoDevices.size} 个")
                report.setSummary(Report.SUM_USB_AUDIO, if (usb.hasAudioInterface) "有" else "无")
                report.setSummary(
                    Report.SUM_CAMERA2,
                    when {
                        camera2.externalIds.isNotEmpty() -> "有，ID ${camera2.externalIds.joinToString(", ")}"
                        camera2.ids.isNotEmpty() -> "无外接相机，但有 ${camera2.ids.size} 个其他相机（ID ${camera2.ids.joinToString(", ")}）"
                        else -> "无（Camera2 没有任何相机）"
                    },
                )
                report.setSummary(Report.SUM_MIC, audio.usbInput?.productName?.toString() ?: "未发现")
                report.setSummary(
                    Report.SUM_H264,
                    "720p 编码=${if (codecs.hwAvcEncoder720p) "支持" else "不支持"}，720p 解码=${if (codecs.hwAvcDecoder720p) "支持" else "不支持"}",
                )
                report.setSection(Report.SEC_DEVICE, device)
                report.setSection(Report.SEC_USB, usb.markdown)
                report.setSection(Report.SEC_CAMERA2, camera2.markdown)
                report.setSection(Report.SEC_AUDIO, audio.markdown)
                report.setSection(Report.SEC_CODECS, codecs.markdown)
                refresh()
                status(getString(R.string.status_idle))
            }
        }, "scan").start()
    }

    // --------------------------------------------------------------- camera2

    private fun startCamera2Test() = withPermission(Manifest.permission.CAMERA, "相机") {
        stopPreviewTests()
        // Rescan here: the camera list can change once the CAMERA permission is granted.
        val scan = Scans.camera2(this).also { camera2Scan = it }
        report.setSection(Report.SEC_CAMERA2, scan.markdown)
        val id = scan.externalIds.firstOrNull() ?: scan.ids.firstOrNull()
        if (id == null) {
            finishTest(Report.SUM_TEST_CAMERA2, Report.SEC_TEST_CAMERA2, TestResult(false, "失败：Camera2 没有任何相机", "系统没有把 USB 摄像头暴露给 Camera2，阶段 3 需要走 UVC 库。"))
            return@withPermission
        }
        val size = scan.previewSizes[id] ?: Size(640, 480)
        withFreshTexture { texture ->
            camera2Test = Camera2PreviewTest(this, texture, ::status) { result ->
                finishTest(Report.SUM_TEST_CAMERA2, Report.SEC_TEST_CAMERA2, result)
            }.also { it.start(id, size) }
        }
    }

    // ------------------------------------------------------------------- uvc

    // CAMERA first: from Android 9 the system refuses USB permission for video-class
    // devices to apps that do not hold the CAMERA permission.
    private fun startUvcTest() = withPermission(Manifest.permission.CAMERA, "相机") {
        stopPreviewTests()
        val usb = Scans.usb(this).also { usbScan = it }
        report.setSection(Report.SEC_USB, usb.markdown)
        val device = usb.videoDevices.firstOrNull()
        if (device == null) {
            finishTest(Report.SUM_TEST_UVC, Report.SEC_TEST_UVC, TestResult(false, "失败：没有找到 UVC 摄像头", "USB 设备里没有视频类（class 14）接口。请确认摄像头已插好，再按“重新扫描”。"))
            return@withPermission
        }
        val manager = getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) {
            report.setSummary(Report.SUM_USB_PERMISSION, "无需弹框（系统已记住授权）")
            runUvcTest(device)
        } else {
            report.setSummary(Report.SUM_USB_PERMISSION, "本次弹出了授权框")
            status("请在弹出的 USB 授权框里选“确定”。\n如果有“默认用于该 USB 设备”的勾选框，请勾上。")
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(packageName)
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            manager.requestPermission(device, PendingIntent.getBroadcast(this, 0, intent, flags))
        }
    }

    private fun runUvcTest(device: UsbDevice) {
        stopPreviewTests()
        withFreshTexture { texture ->
            uvcTest = UvcPreviewTest(this, texture, ::status) { result ->
                finishTest(Report.SUM_TEST_UVC, Report.SEC_TEST_UVC, result)
            }.also { it.start(device) }
        }
    }

    // ----------------------------------------------------------------- audio

    private fun startAudioTest() = withPermission(Manifest.permission.RECORD_AUDIO, "录音") {
        if (audioTest != null) return@withPermission
        val audio = Scans.audioInputs(this).also { audioScan = it }
        report.setSection(Report.SEC_AUDIO, audio.markdown)
        report.setSummary(Report.SUM_MIC, audio.usbInput?.productName?.toString() ?: "未发现")
        audioTest = AudioLoopTest(audio.usbInput, ::status) { result ->
            ui.post { audioTest = null }
            finishTest(Report.SUM_TEST_AUDIO, Report.SEC_TEST_AUDIO, result)
        }.also { it.start() }
    }

    // --------------------------------------------------------------- helpers

    /** Safe from any thread. Records a test outcome, redraws and saves the report. */
    private fun finishTest(summaryItem: String, section: String, result: TestResult) {
        ui.post {
            report.setSummary(summaryItem, result.summary)
            report.setSection(section, result.details)
            refresh()
            status("$summaryItem：${result.summary}")
        }
    }

    private fun stopPreviewTests() {
        camera2Test?.stop()
        camera2Test = null
        uvcTest?.stop()
        uvcTest = null
        previewBox.removeAllViews()
    }

    /**
     * Gives each preview test a brand-new TextureView, so a surface left half-connected by the
     * previous test (Camera2 vs. the UVC library's native window) can never affect the next one.
     */
    private fun withFreshTexture(use: (SurfaceTexture) -> Unit) {
        previewBox.removeAllViews()
        val view = TextureView(this)
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) = use(texture)
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
            // false: the running test still draws into the texture; it releases it in stop().
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean = false
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        }
        previewBox.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun withPermission(permission: String, label: String, action: () -> Unit) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            afterPermission = action
            status("请在弹出的权限框里允许“$label”权限。")
            requestPermissions(arrayOf(permission), REQUEST_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSION) return
        val action = afterPermission
        afterPermission = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            action?.invoke()
        } else {
            status("权限被拒绝，无法测试。\n可以到 设置 → 应用 → 通话硬件探测 → 权限 里手动打开，再回来重试。")
        }
    }

    /** Safe from any thread. */
    private fun status(text: String) {
        ui.post { statusView.text = text }
    }

    private fun refresh() {
        reportView.text = report.toMarkdown()
        saveReport(announce = false)
    }

    private fun saveReport(announce: Boolean) {
        Thread({
            val paths = report.save(this)
            if (announce) {
                status(if (paths.isEmpty()) "保存失败，请用 adb logcat -d -s ${Report.TAG} 导出。" else "报告已保存到：\n${paths.first()}")
            }
        }, "save").start()
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "com.familycall.probe.USB_PERMISSION"
        const val REQUEST_PERMISSION = 1
    }
}

package com.familycall.probe

import android.app.ActivityManager
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Size

/** Read-only inventory checks. None of these open a device or need a runtime permission. */
object Scans {

    // ---------------------------------------------------------------- device

    fun deviceInfo(context: Context): String = buildString {
        append("| 项 | 值 |\n| --- | --- |\n")
        row("厂商 / 型号", "${Build.MANUFACTURER} / ${Build.MODEL}")
        row("设备代号 / 产品", "${Build.DEVICE} / ${Build.PRODUCT}")
        row("Android 版本", "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        row("系统版本号", Build.DISPLAY)
        val soc = if (Build.VERSION.SDK_INT >= 31) " / ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
        row("芯片平台", "${Build.HARDWARE} / ${Build.BOARD}$soc")
        row("CPU 架构", Build.SUPPORTED_ABIS.joinToString(", "))
        row("CPU 核心数", Runtime.getRuntime().availableProcessors().toString())
        val mem = ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mem)
        row("内存", "总 ${mem.totalMem / MB} MB，可用 ${mem.availMem / MB} MB")
        val pm = context.packageManager
        val features = listOf(
            "android.hardware.usb.host" to "USB Host",
            "android.hardware.camera.external" to "外接相机",
            "android.hardware.camera.any" to "任意相机",
            "android.hardware.microphone" to "麦克风",
            "android.software.leanback" to "Leanback",
        ).joinToString("，") { (name, label) -> "$label=${if (pm.hasSystemFeature(name)) "有" else "无"}" }
        row("系统特性", features)
    }

    // ------------------------------------------------------------------- usb

    class UsbResult(val markdown: String, val videoDevices: List<UsbDevice>, val hasAudioInterface: Boolean)

    fun usb(context: Context): UsbResult {
        val manager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return UsbResult("系统没有 USB 服务。", emptyList(), false)
        val devices = try {
            manager.deviceList.values.toList()
        } catch (e: Exception) {
            return UsbResult("读取 USB 设备列表失败：$e", emptyList(), false)
        }
        if (devices.isEmpty()) return UsbResult("没有发现任何 USB 设备。请确认摄像头已插好。", emptyList(), false)

        val video = mutableListOf<UsbDevice>()
        var audio = false
        val md = buildString {
            for (device in devices) {
                val interfaces = (0 until device.interfaceCount).map { device.getInterface(it) }
                val isVideo = interfaces.any { it.interfaceClass == UsbConstants.USB_CLASS_VIDEO }
                val isAudio = interfaces.any { it.interfaceClass == UsbConstants.USB_CLASS_AUDIO }
                if (isVideo) video += device
                if (isAudio) audio = true
                val name = listOfNotNull(safe { device.manufacturerName }, safe { device.productName })
                    .joinToString(" ").ifBlank { "（无名称）" }
                append("### ").append(name).append("\n\n")
                append("- VID:PID = ").append(hex4(device.vendorId)).append(':').append(hex4(device.productId)).append('\n')
                append("- 设备类 = ").append(usbClassName(device.deviceClass)).append('\n')
                append("- 路径 = ").append(device.deviceName).append('\n')
                append("- 已授权 = ").append(if (safe { manager.hasPermission(device) } == true) "是" else "否").append('\n')
                append("- 识别为：")
                append(if (isVideo) "UVC 视频 " else "")
                append(if (isAudio) "USB 音频 " else "")
                append(if (!isVideo && !isAudio) "其他设备" else "").append('\n')
                // Collapse alternate settings: one line per (class, subclass) with a count.
                val grouped = interfaces.groupingBy { it.interfaceClass to it.interfaceSubclass }.eachCount()
                append("- 接口：")
                append(grouped.entries.joinToString("；") { (key, count) ->
                    "${usbClassName(key.first)} 子类 ${key.second} ×$count"
                })
                append("\n\n")
            }
        }
        return UsbResult(md, video, audio)
    }

    private fun usbClassName(cls: Int): String = when (cls) {
        UsbConstants.USB_CLASS_PER_INTERFACE -> "0 按接口定义"
        UsbConstants.USB_CLASS_AUDIO -> "1 音频"
        UsbConstants.USB_CLASS_COMM -> "2 通信"
        UsbConstants.USB_CLASS_HID -> "3 HID"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "8 存储"
        UsbConstants.USB_CLASS_HUB -> "9 Hub"
        UsbConstants.USB_CLASS_VIDEO -> "14 视频"
        UsbConstants.USB_CLASS_WIRELESS_CONTROLLER -> "224 无线"
        UsbConstants.USB_CLASS_MISC -> "239 杂项（复合设备）"
        UsbConstants.USB_CLASS_VENDOR_SPEC -> "255 厂商自定义"
        else -> cls.toString()
    }

    // --------------------------------------------------------------- camera2

    class Camera2Result(
        val markdown: String,
        val ids: List<String>,
        val externalIds: List<String>,
        /** Preview size to try per camera id (720p when offered). */
        val previewSizes: Map<String, Size>,
    )

    fun camera2(context: Context): Camera2Result {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return Camera2Result("系统没有相机服务。", emptyList(), emptyList(), emptyMap())
        val ids = try {
            manager.cameraIdList.toList()
        } catch (e: Exception) {
            return Camera2Result("读取相机列表失败：$e", emptyList(), emptyList(), emptyMap())
        }
        if (ids.isEmpty()) {
            return Camera2Result(
                "Camera2 没有列出任何相机：系统没有把 USB 摄像头暴露成相机，只能走 UVC 库。",
                emptyList(), emptyList(), emptyMap(),
            )
        }
        val external = mutableListOf<String>()
        val previewSizes = mutableMapOf<String, Size>()
        val md = buildString {
            for (id in ids) {
                append("### 相机 ID ").append(id).append("\n\n")
                try {
                    val c = manager.getCameraCharacteristics(id)
                    val facing = c.get(CameraCharacteristics.LENS_FACING)
                    if (facing == CameraCharacteristics.LENS_FACING_EXTERNAL) external += id
                    append("- 朝向 = ").append(
                        when (facing) {
                            CameraCharacteristics.LENS_FACING_FRONT -> "前置"
                            CameraCharacteristics.LENS_FACING_BACK -> "后置"
                            CameraCharacteristics.LENS_FACING_EXTERNAL -> "外接（EXTERNAL）"
                            else -> "未知($facing)"
                        }
                    ).append('\n')
                    append("- 硬件级别 = ").append(
                        when (val level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                            else -> "未知($level)"
                        }
                    ).append('\n')
                    val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val preview = map?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
                    val yuv = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
                    append("- 预览分辨率 = ").append(sizes(preview)).append('\n')
                    append("- YUV_420_888 分辨率 = ").append(sizes(yuv)).append('\n')
                    val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    append("- 帧率范围 = ").append(fps?.joinToString(", ") { "${it.lower}-${it.upper}" } ?: "未知").append('\n')
                    pickPreviewSize(preview)?.let { previewSizes[id] = it }
                } catch (e: Exception) {
                    append("- 读取特性失败：").append(e).append('\n')
                }
                append('\n')
            }
        }
        return Camera2Result(md, ids, external, previewSizes)
    }

    /** 1280x720 if offered, else the largest size that is not bigger than 720p, else the smallest. */
    private fun pickPreviewSize(sizes: List<Size>): Size? {
        sizes.firstOrNull { it.width == 1280 && it.height == 720 }?.let { return it }
        return sizes.filter { it.width <= 1280 && it.height <= 720 }.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
    }

    private fun sizes(list: List<Size>): String =
        if (list.isEmpty()) "无" else list.sortedByDescending { it.width * it.height }
            .take(12).joinToString(", ") { "${it.width}x${it.height}" } + if (list.size > 12) " …" else ""

    // ----------------------------------------------------------------- audio

    class AudioResult(val markdown: String, val usbInput: AudioDeviceInfo?)

    fun audioInputs(context: Context): AudioResult {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = try {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        } catch (e: Exception) {
            return AudioResult("读取音频输入设备失败：$e", null)
        }
        val usb = inputs.firstOrNull { isUsbAudio(it.type) }
        val md = buildString {
            if (inputs.isEmpty()) {
                append("系统没有列出任何音频输入设备。\n")
            } else {
                append("| 名称 | 类型 | 采样率 | 声道数 |\n| --- | --- | --- | --- |\n")
                for (d in inputs) {
                    append("| ").append(d.productName).append(" | ").append(audioTypeName(d.type)).append(" | ")
                    append(d.sampleRates.joinToString(", ").ifEmpty { "任意" }).append(" | ")
                    append(d.channelCounts.joinToString(", ").ifEmpty { "任意" }).append(" |\n")
                }
            }
            append("\n系统音效（阶段 3 的回声消除要用）：")
            append("回声消除 AEC=").append(yesNo(safe { AcousticEchoCanceler.isAvailable() }))
            append("，降噪 NS=").append(yesNo(safe { NoiseSuppressor.isAvailable() }))
            append("，自动增益 AGC=").append(yesNo(safe { AutomaticGainControl.isAvailable() })).append('\n')
        }
        return AudioResult(md, usb)
    }

    fun isUsbAudio(type: Int): Boolean =
        type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_ACCESSORY ||
            (Build.VERSION.SDK_INT >= 26 && type == AudioDeviceInfo.TYPE_USB_HEADSET)

    fun audioTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦克风"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 设备"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 配件"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳麦"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳麦"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙 SCO"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_TELEPHONY -> "电话"
        AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "内录 submix"
        else -> "类型 $type"
    }

    // ---------------------------------------------------------------- codecs

    class CodecResult(val markdown: String, val hwAvcEncoder720p: Boolean, val hwAvcDecoder720p: Boolean)

    private val videoTypes = listOf(
        "video/avc" to "H.264",
        "video/hevc" to "H.265",
        "video/x-vnd.on2.vp8" to "VP8",
        "video/x-vnd.on2.vp9" to "VP9",
    )

    fun codecs(): CodecResult {
        var hwEnc = false
        var hwDec = false
        val md = buildString {
            append("| 格式 | 方向 | 硬件 | 名称 | 最大分辨率 | 720p30 | 1080p30 |\n")
            append("| --- | --- | --- | --- | --- | --- | --- |\n")
            val infos = try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList()
            } catch (e: Exception) {
                append("\n读取编解码器列表失败：").append(e).append('\n')
                emptyList()
            }
            for ((mime, label) in videoTypes) {
                for (info in infos) {
                    val type = info.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) } ?: continue
                    val video = safe { info.getCapabilitiesForType(type).videoCapabilities } ?: continue
                    val hardware = isHardware(info)
                    val can720 = safe { video.areSizeAndRateSupported(1280, 720, 30.0) } == true
                    val can1080 = safe { video.areSizeAndRateSupported(1920, 1080, 30.0) } == true
                    if (mime == "video/avc" && hardware && can720) {
                        if (info.isEncoder) hwEnc = true else hwDec = true
                    }
                    append("| ").append(label)
                    append(" | ").append(if (info.isEncoder) "编码" else "解码")
                    append(" | ").append(if (hardware) "是" else "否")
                    append(" | ").append(info.name)
                    append(" | ").append(video.supportedWidths.upper).append('x').append(video.supportedHeights.upper)
                    append(" | ").append(if (can720) "支持" else "不支持")
                    append(" | ").append(if (can1080) "支持" else "不支持").append(" |\n")
                }
            }
        }
        return CodecResult(md, hwEnc, hwDec)
    }

    private fun isHardware(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return info.isHardwareAccelerated
        // Before API 29 there is no flag; the software codecs are the ones Google ships.
        val name = info.name.lowercase()
        return !(name.startsWith("omx.google.") || name.startsWith("c2.android.") || name.contains(".sw."))
    }

    // --------------------------------------------------------------- helpers

    private const val MB = 1024L * 1024L

    private fun StringBuilder.row(key: String, value: String) {
        append("| ").append(key).append(" | ").append(value).append(" |\n")
    }

    private fun hex4(value: Int): String = String.format("%04X", value)

    private fun yesNo(value: Boolean?): String = when (value) {
        true -> "有"
        false -> "无"
        null -> "未知"
    }

    private inline fun <T> safe(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        null
    }
}

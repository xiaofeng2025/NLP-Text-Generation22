package com.familycall.probe

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The probe's findings: a short summary (what the human reads on the TV and reports back)
 * plus detailed sections. Rendered as Markdown so the saved file can be dropped into
 * docs/probe-report.md unchanged.
 */
class Report {
    private val summary = linkedMapOf<String, String>()
    private val sections = linkedMapOf<String, String>()

    @Synchronized
    fun setSummary(item: String, value: String) {
        summary[item] = value
    }

    @Synchronized
    fun setSection(title: String, body: String) {
        sections[title] = body
    }

    @Synchronized
    fun toMarkdown(): String = buildString {
        append("# 硬件探测报告\n\n")
        append("生成时间：")
        append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        append("\n\n## 结论摘要\n\n")
        for ((item, value) in summary) append("- ").append(item).append("：").append(value).append('\n')
        for ((title, body) in sections) {
            append("\n## ").append(title).append("\n\n").append(body.trimEnd()).append('\n')
        }
    }

    /**
     * Writes the report to app-specific external storage (for `adb pull`) and to internal
     * storage (for `adb shell run-as ... cat`, which works even where Android/data is locked
     * down). Returns the paths written.
     */
    fun save(context: Context): List<String> {
        val text = toMarkdown()
        val written = mutableListOf<String>()
        val targets = listOfNotNull(context.getExternalFilesDir(null), context.filesDir)
        for (dir in targets) {
            try {
                val file = File(dir, FILE_NAME)
                file.writeText(text)
                written += file.absolutePath
            } catch (e: Exception) {
                Log.w(TAG, "save to $dir failed", e)
            }
        }
        // Third way out: `adb logcat -d -s ProbeReport`.
        text.lineSequence().forEach { Log.i(TAG, it) }
        return written
    }

    companion object {
        const val TAG = "ProbeReport"
        const val FILE_NAME = "probe-report.md"

        const val SUM_USB_VIDEO = "USB 摄像头（UVC 视频接口）"
        const val SUM_USB_AUDIO = "USB 音频接口"
        const val SUM_USB_PERMISSION = "USB 授权"
        const val SUM_CAMERA2 = "Camera2 外接相机"
        const val SUM_MIC = "系统音频输入里的 USB 麦克风"
        const val SUM_H264 = "硬件 H.264 编解码"
        const val SUM_TEST_CAMERA2 = "Camera2 预览测试"
        const val SUM_TEST_UVC = "UVC 预览测试"
        const val SUM_TEST_AUDIO = "录音回放测试"

        const val SEC_DEVICE = "设备信息"
        const val SEC_USB = "USB 设备"
        const val SEC_CAMERA2 = "Camera2 相机"
        const val SEC_AUDIO = "音频输入设备"
        const val SEC_CODECS = "视频编解码器"
        const val SEC_TEST_CAMERA2 = "Camera2 预览测试"
        const val SEC_TEST_UVC = "UVC 预览测试"
        const val SEC_TEST_AUDIO = "录音回放测试"

        const val NOT_TESTED = "未测试"
    }
}

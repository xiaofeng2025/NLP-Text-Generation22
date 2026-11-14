package com.familycall.probe

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Drives the USB camera directly over USB Host with the UVC library (bypassing Camera2),
 * shows it on [texture] and measures the frame rate of the NV21 frames the library hands
 * back, since NV21 frames are what a custom WebRTC VideoCapturer would consume in phase 3.
 *
 * The test owns [texture] and releases it in [stop], after the camera has let go of it.
 *
 * The caller must already hold the USB permission for the device: we call
 * [USBMonitor.openDevice] directly and never [USBMonitor.register], because the library's own
 * permission flow uses an immutable PendingIntent and an unflagged receiver, which break on
 * Android 12+ / 14+.
 */
class UvcPreviewTest(
    private val context: Context,
    private val texture: SurfaceTexture,
    private val onStatus: (String) -> Unit,
    private val onDone: (TestResult) -> Unit,
) {
    private val thread = HandlerThread("uvc-test").apply { start() }
    private val handler = Handler(thread.looper)
    private val frames = AtomicInteger()
    private var monitor: USBMonitor? = null
    private var camera: UVCCamera? = null
    private var surface: Surface? = null
    private var finished = false
    private var stopped = false

    private class Mode(val width: Int, val height: Int, val format: Int) {
        val label get() = "${width}x$height ${if (format == UVCCamera.FRAME_FORMAT_MJPEG) "MJPEG" else "YUYV"}"
    }

    fun start(device: UsbDevice) {
        handler.post {
            try {
                onStatus("UVC：正在打开 USB 摄像头…")
                val usbMonitor = USBMonitor(context, NoopListener).also { monitor = it }
                val cam = UVCCamera().also { camera = it }
                cam.open(usbMonitor.openDevice(device))

                val mjpeg = cam.getSupportedSizeList(UVCCamera.FRAME_FORMAT_MJPEG).orEmpty()
                val yuyv = cam.getSupportedSizeList(UVCCamera.FRAME_FORMAT_YUYV).orEmpty()
                val capabilities = "- MJPEG 分辨率 = ${describe(mjpeg)}\n- YUYV 分辨率 = ${describe(yuyv)}\n"

                // Preference order: what phase 3 wants (720p MJPEG) first, then safer fallbacks.
                val candidates = listOf(
                    Mode(1280, 720, UVCCamera.FRAME_FORMAT_MJPEG),
                    Mode(640, 480, UVCCamera.FRAME_FORMAT_MJPEG),
                    Mode(1280, 720, UVCCamera.FRAME_FORMAT_YUYV),
                    Mode(640, 480, UVCCamera.FRAME_FORMAT_YUYV),
                ) + mjpeg.take(1).map { Mode(it.width, it.height, UVCCamera.FRAME_FORMAT_MJPEG) } +
                    yuyv.take(1).map { Mode(it.width, it.height, UVCCamera.FRAME_FORMAT_YUYV) }
                val rejected = mutableListOf<String>()
                var chosen: Mode? = null
                for (mode in candidates) {
                    try {
                        cam.setPreviewSize(
                            mode.width, mode.height,
                            UVCCamera.DEFAULT_PREVIEW_MIN_FPS, UVCCamera.DEFAULT_PREVIEW_MAX_FPS,
                            mode.format, UVCCamera.DEFAULT_BANDWIDTH,
                        )
                        chosen = mode
                        break
                    } catch (e: Exception) {
                        rejected += mode.label
                    }
                }
                val mode = chosen ?: run {
                    finish(false, "摄像头不接受任何分辨率", capabilities + "- 都被拒绝：${rejected.joinToString("、")}")
                    return@post
                }

                val target = Surface(texture).also { surface = it }
                cam.setPreviewDisplay(target)
                cam.setFrameCallback(IFrameCallback { frames.incrementAndGet() }, UVCCamera.PIXEL_FORMAT_NV21)
                cam.startPreview()
                onStatus("UVC：已启动 ${mode.label}，等待画面…")

                // Let the stream settle for a second, then count frames over the window.
                handler.postDelayed({
                    val startFrames = frames.get()
                    onStatus("UVC：正在测量帧率（${MEASURE_MS / 1000} 秒）…")
                    handler.postDelayed({
                        val delivered = frames.get() - startFrames
                        val fps = delivered * 1000f / MEASURE_MS
                        val details = capabilities +
                            "- 实际使用 = ${mode.label}\n" +
                            (if (rejected.isEmpty()) "" else "- 被拒绝的模式 = ${rejected.joinToString("、")}\n") +
                            String.format(Locale.US, "- 实测帧率（NV21 回调）= %.1f 帧/秒\n", fps) +
                            "- 请人工确认：电视右上角能看到摄像头实时画面。"
                        if (delivered > 0) {
                            finish(true, String.format(Locale.US, "通过，%s，%.1f 帧/秒", mode.label, fps), details)
                        } else {
                            finish(false, "打开成功但没有收到任何帧（${mode.label}）", details)
                        }
                    }, MEASURE_MS)
                }, SETTLE_MS)
            } catch (e: Throwable) {
                // Throwable: a missing native library surfaces as UnsatisfiedLinkError.
                finish(false, "打开失败", "UVC 库抛出异常：$e")
            }
        }
    }

    private fun describe(sizes: List<Size>): String =
        if (sizes.isEmpty()) "无" else sizes.joinToString(", ") { "${it.width}x${it.height}" }

    private fun finish(ok: Boolean, summary: String, details: String) {
        if (finished || stopped) return
        finished = true
        onDone(TestResult(ok, if (ok) summary else "失败：$summary", details))
    }

    fun stop() {
        handler.post {
            stopped = true
            handler.removeCallbacksAndMessages(null)
            try { camera?.stopPreview() } catch (_: Throwable) {}
            try { camera?.destroy() } catch (_: Throwable) {}
            try { monitor?.destroy() } catch (_: Throwable) {}
            try { surface?.release() } catch (_: Throwable) {}
            try { texture.release() } catch (_: Throwable) {}
            thread.quitSafely()
        }
    }

    private object NoopListener : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {}
        override fun onDetach(device: UsbDevice?) {}
        override fun onConnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?, createNew: Boolean) {}
        override fun onDisconnect(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {}
        override fun onCancel(device: UsbDevice?) {}
    }

    private companion object {
        const val SETTLE_MS = 1_000L
        const val MEASURE_MS = 5_000L
    }
}

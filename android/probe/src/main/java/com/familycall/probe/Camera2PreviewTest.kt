package com.familycall.probe

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opens one Camera2 camera, shows it on [texture] and measures the delivered frame rate.
 * The preview keeps running after the measurement until [stop] is called.
 * [onDone] fires exactly once, on the test's own thread. The test owns [texture] and
 * releases it in [stop], after the camera has let go of it.
 */
class Camera2PreviewTest(
    private val context: Context,
    private val texture: SurfaceTexture,
    private val onStatus: (String) -> Unit,
    private val onDone: (TestResult) -> Unit,
) {
    private val thread = HandlerThread("camera2-test").apply { start() }
    private val handler = Handler(thread.looper)
    private val frames = AtomicInteger()
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var surface: Surface? = null
    private var finished = false
    private var stopped = false

    @SuppressLint("MissingPermission") // The activity checks CAMERA before starting the test.
    fun start(cameraId: String, size: Size) {
        handler.post {
            onStatus("Camera2：正在打开相机 $cameraId（${size.width}x${size.height}）…")
            handler.postDelayed({
                if (frames.get() == 0) finish(false, "超时", "打开相机后 ${OPEN_TIMEOUT_MS / 1000} 秒内没有收到任何画面。")
            }, OPEN_TIMEOUT_MS)
            try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        if (stopped) {
                            device.close()
                            return
                        }
                        camera = device
                        startSession(device, cameraId, size)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        device.close()
                        finish(false, "相机断开", "相机 $cameraId 被系统断开。")
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        device.close()
                        finish(false, "打开失败（错误码 $error）", "CameraDevice.onError 错误码 $error。")
                    }
                }, handler)
            } catch (e: Exception) {
                finish(false, "打开失败", "openCamera 抛出异常：$e")
            }
        }
    }

    @Suppress("DEPRECATION") // createCaptureSession(List, ...) is the only form available on API 23.
    private fun startSession(device: CameraDevice, cameraId: String, size: Size) {
        try {
            texture.setDefaultBufferSize(size.width, size.height)
            val target = Surface(texture).also { surface = it }
            device.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (stopped) return
                    session = s
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(target) }
                        s.setRepeatingRequest(request.build(), object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureCompleted(cs: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                                // The first frame starts the measurement window.
                                if (frames.getAndIncrement() == 0) measure(cameraId, size)
                            }
                        }, handler)
                    } catch (e: Exception) {
                        finish(false, "预览启动失败", "setRepeatingRequest 抛出异常：$e")
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    finish(false, "会话配置失败", "createCaptureSession 配置失败（${size.width}x${size.height}）。")
                }
            }, handler)
        } catch (e: Exception) {
            finish(false, "会话创建失败", "createCaptureSession 抛出异常：$e")
        }
    }

    private fun measure(cameraId: String, size: Size) {
        onStatus("Camera2：已出图，正在测量帧率（${MEASURE_MS / 1000} 秒）…")
        val startFrames = frames.get()
        handler.postDelayed({
            val fps = (frames.get() - startFrames) * 1000f / MEASURE_MS
            val summary = String.format(Locale.US, "通过，相机 %s，%dx%d，%.1f 帧/秒", cameraId, size.width, size.height, fps)
            finish(
                true, summary,
                "- 相机 ID = $cameraId\n- 分辨率 = ${size.width}x${size.height}\n" +
                    String.format(Locale.US, "- 实测帧率 = %.1f 帧/秒\n", fps) +
                    "- 请人工确认：电视右上角能看到摄像头实时画面。",
            )
        }, MEASURE_MS)
    }

    private fun finish(ok: Boolean, summary: String, details: String) {
        if (finished || stopped) return
        finished = true
        onDone(TestResult(ok, if (ok) summary else "失败：$summary", details))
    }

    fun stop() {
        handler.post {
            stopped = true
            handler.removeCallbacksAndMessages(null)
            try { session?.close() } catch (_: Exception) {}
            try { camera?.close() } catch (_: Exception) {}
            try { surface?.release() } catch (_: Exception) {}
            try { texture.release() } catch (_: Exception) {}
            thread.quitSafely()
        }
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 10_000L
        const val MEASURE_MS = 5_000L
    }
}

class TestResult(val ok: Boolean, val summary: String, val details: String)

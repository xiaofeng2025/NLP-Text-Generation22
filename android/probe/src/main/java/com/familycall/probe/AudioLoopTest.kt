package com.familycall.probe

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Records [SECONDS] seconds with AudioRecord (routed to the USB microphone when the system
 * lists one), reports the signal level, then plays the recording back through the TV.
 * Runs on its own thread; [onDone] fires once when playback has finished.
 */
class AudioLoopTest(
    private val preferred: AudioDeviceInfo?,
    private val onStatus: (String) -> Unit,
    private val onDone: (TestResult) -> Unit,
) {
    @Volatile
    private var cancelled = false

    fun start() {
        Thread({ run() }, "audio-test").start()
    }

    fun cancel() {
        cancelled = true
    }

    @SuppressLint("MissingPermission") // The activity checks RECORD_AUDIO before starting the test.
    private fun run() {
        var record: AudioRecord? = null
        var rate = 0
        for (candidate in intArrayOf(48_000, 44_100, 16_000)) {
            val min = AudioRecord.getMinBufferSize(candidate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val attempt = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, candidate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, candidate),
                )
            } catch (e: Exception) {
                null
            }
            if (attempt != null && attempt.state == AudioRecord.STATE_INITIALIZED) {
                record = attempt
                rate = candidate
                break
            }
            attempt?.release()
        }
        if (record == null) {
            onDone(TestResult(false, "失败：无法创建 AudioRecord", "48000 / 44100 / 16000 Hz 单声道都无法初始化 AudioRecord，系统可能没有可用的音频输入。"))
            return
        }

        val samples = ShortArray(rate * SECONDS)
        var total = 0
        var routed: AudioDeviceInfo? = null
        try {
            if (preferred != null) record.setPreferredDevice(preferred)
            record.startRecording()
            var emptyReads = 0
            var lastSecond = -1
            while (total < samples.size && !cancelled) {
                val n = record.read(samples, total, minOf(rate / 10, samples.size - total))
                if (n > 0) {
                    total += n
                    emptyReads = 0
                } else if (++emptyReads > 20) {
                    break
                }
                val second = total / rate
                if (second != lastSecond) {
                    lastSecond = second
                    onStatus("正在录音：请对着摄像头说话…（剩 ${SECONDS - second} 秒）")
                }
            }
            routed = record.routedDevice
        } catch (e: Exception) {
            onDone(TestResult(false, "失败：录音出错", "AudioRecord 抛出异常：$e"))
            return
        } finally {
            try { record.stop() } catch (_: Exception) {}
            record.release()
        }
        if (cancelled) return
        if (total == 0) {
            onDone(TestResult(false, "失败：没有录到任何数据", "AudioRecord 已启动（$rate Hz）但 read 始终返回 0。"))
            return
        }

        var peak = 0
        var sumSquares = 0.0
        for (i in 0 until total) {
            val v = samples[i].toInt()
            if (abs(v) > peak) peak = abs(v)
            sumSquares += v.toDouble() * v
        }
        val peakDb = dbfs(peak.toDouble())
        val rmsDb = dbfs(sqrt(sumSquares / total))
        val hasSignal = peakDb > SILENCE_DBFS
        val source = routed?.let { "${it.productName}（${Scans.audioTypeName(it.type)}）" } ?: "未知"
        val fromUsb = routed != null && Scans.isUsbAudio(routed.type)

        onStatus("正在回放刚才的录音，请听电视有没有声音…")
        val playbackError = play(samples, total, rate)

        val details = buildString {
            append("- 采样率 = ").append(rate).append(" Hz，单声道，16 bit\n")
            append("- 实际录音设备 = ").append(source).append('\n')
            append("- 录到 = ").append(String.format(Locale.US, "%.1f", total.toFloat() / rate)).append(" 秒\n")
            append(String.format(Locale.US, "- 峰值 = %.1f dBFS，平均 = %.1f dBFS\n", peakDb, rmsDb))
            append("- 判定 = ").append(if (hasSignal) "有声音信号" else "几乎是静音（麦克风可能没有工作）").append('\n')
            if (playbackError != null) append("- 回放失败：").append(playbackError).append('\n')
            append("- 请人工确认：回放时电视里能听到自己刚才说的话。")
        }
        val summary = when {
            !hasSignal -> "失败：录音几乎是静音（来源 $source）"
            playbackError != null -> "失败：录音正常但回放出错"
            else -> String.format(Locale.US, "通过，来源 %s%s，峰值 %.0f dBFS", source, if (fromUsb) "" else "（不是 USB 设备）", peakDb)
        }
        onDone(TestResult(hasSignal && playbackError == null, summary, details))
    }

    @Suppress("DEPRECATION") // The stream-type constructor is the one that exists on API 23.
    private fun play(samples: ShortArray, count: Int, rate: Int): String? {
        var track: AudioTrack? = null
        return try {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            track = AudioTrack(
                AudioManager.STREAM_MUSIC, rate, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate), AudioTrack.MODE_STREAM,
            )
            track.play()
            var written = 0
            while (written < count && !cancelled) {
                val n = track.write(samples, written, minOf(rate / 10, count - written))
                if (n <= 0) break
                written += n
            }
            // write() returns once data is queued; give the tail time to reach the speakers.
            Thread.sleep(500)
            null
        } catch (e: Exception) {
            e.toString()
        } finally {
            try { track?.stop() } catch (_: Exception) {}
            track?.release()
        }
    }

    private fun dbfs(amplitude: Double): Double = if (amplitude < 1.0) -96.0 else 20 * log10(amplitude / 32768.0)

    private companion object {
        const val SECONDS = 5
        const val SILENCE_DBFS = -50.0
    }
}

package com.example.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.app.parsing.VietnameseNumberParser
import com.example.app.speech.TikTokAudioStatus
import com.example.app.speech.VietnameseVoskModel
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.sqrt

/** Captures only TikTok's playback stream. This service never opens a microphone input. */
class TikTokPlaybackCaptureService : Service() {

    @Volatile private var running = false
    @Volatile private var autoCommentEnabled = true
    @Volatile private var prefixAt = true
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture("Đã dừng nghe audio TikTok Live")
                stopSelf(startId)
            }
            ACTION_UPDATE_SETTINGS -> {
                autoCommentEnabled = intent.getBooleanExtra(EXTRA_AUTO_COMMENT, autoCommentEnabled)
                prefixAt = intent.getBooleanExtra(EXTRA_PREFIX_AT, prefixAt)
            }
            ACTION_START -> {
                if (!running) startPlaybackCapture(intent, startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun startPlaybackCapture(intent: Intent, startId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            TikTokAudioStatus.state("Thiết bị cần Android 10 trở lên để nghe trực tiếp audio TikTok")
            stopSelf(startId)
            return
        }

        autoCommentEnabled = intent.getBooleanExtra(EXTRA_AUTO_COMMENT, true)
        prefixAt = intent.getBooleanExtra(EXTRA_PREFIX_AT, true)
        try {
            startForegroundWithCaptureType()
            val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
            @Suppress("DEPRECATION")
            val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
            if (resultData == null) throw IllegalStateException("Thiếu xác nhận capture audio của Android")

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: throw IllegalStateException("Android không cấp quyền capture audio")
            mediaProjection = projection
            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (mediaProjection !== projection) return
                    stopCapture("Quyền capture đã bị dừng; audio TikTok không còn được nhận", stopProjection = false)
                    stopSelf()
                }
            }, mainHandler)

            running = true
            TikTokAudioStatus.listening(true)
            TikTokAudioStatus.state("Đang kết nối audio playback của TikTok Live; không dùng micro")
            captureThread = Thread({ captureTikTokAudio() }, "tiktok-playback-capture").apply { start() }
        } catch (error: Exception) {
            Log.e(TAG, "Unable to start TikTok playback capture", error)
            TikTokAudioStatus.listening(false)
            TikTokAudioStatus.state("Không thể capture audio TikTok: ${error.message ?: "lỗi quyền hoặc thiết bị"}")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun startForegroundWithCaptureType() {
        createNotificationChannel()
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("TikTok Live Audio")
            .setContentText("Đang nhận audio phát trực tiếp từ TikTok")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TikTok Live audio capture",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun captureTikTokAudio() {
        var model: Model? = null
        var recognizer: Recognizer? = null
        try {
            val tiktokUids = TikTokAutoCommentService.TIKTOK_PACKAGES.mapNotNull { packageName ->
                try {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(packageName, 0).uid
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }.distinct()
            check(tiktokUids.isNotEmpty()) { "Không tìm thấy gói TikTok được hỗ trợ trên điện thoại" }

            val modelDirectory = runBlocking {
                VietnameseVoskModel.ensureInstalled(applicationContext) { percent ->
                    mainHandler.post { TikTokAudioStatus.state("Đang tải model nhận dạng tiếng Việt: $percent%") }
                }
            }
            if (!running) return
            model = Model(modelDirectory.absolutePath)
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())

            val projection = mediaProjection ?: error("Phiên MediaProjection đã kết thúc")
            val captureConfiguration = AudioPlaybackCaptureConfiguration.Builder(projection).apply {
                tiktokUids.forEach(::addMatchingUid)
            }.build()
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            val minimumBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimumBufferSize > 0) { "Thiết bị không hỗ trợ định dạng audio cần thiết" }
            val recorder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimumBufferSize * 2, SAMPLE_RATE))
                .setAudioPlaybackCaptureConfig(captureConfiguration)
                .build()
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Không khởi tạo được TikTok playback capture" }
            if (!running) {
                recorder.release()
                return
            }
            audioRecord = recorder
            recorder.startRecording()

            val samples = ShortArray(SAMPLES_PER_READ)
            var lastSignalAt = SystemClock.elapsedRealtime()
            var lastStatusAt = 0L
            while (running) {
                val readCount = recorder.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                if (readCount <= 0) {
                    if (readCount == AudioRecord.ERROR_DEAD_OBJECT) error("Audio playback capture đã ngắt")
                    continue
                }

                var energy = 0.0
                val pcm = ByteArray(readCount * 2)
                for (index in 0 until readCount) {
                    val sample = samples[index].toInt()
                    energy += sample.toDouble() * sample
                    pcm[index * 2] = sample.toByte()
                    pcm[index * 2 + 1] = (sample shr 8).toByte()
                }
                val rms = sqrt(energy / readCount)
                val now = SystemClock.elapsedRealtime()
                if (rms >= MIN_SIGNAL_RMS) lastSignalAt = now
                if (now - lastStatusAt >= STATUS_INTERVAL_MS) {
                    val status = if (now - lastSignalAt > NO_SIGNAL_WARNING_MS) {
                        "Chưa nhận được tiếng phát từ TikTok Live; không chuyển sang nghe micro"
                    } else {
                        "Đang nhận audio playback của TikTok Live; không dùng micro"
                    }
                    mainHandler.post { TikTokAudioStatus.state(status) }
                    lastStatusAt = now
                }

                val activeRecognizer = recognizer ?: break
                val finalized = activeRecognizer.acceptWaveForm(pcm, pcm.size)
                val resultJson = if (finalized) activeRecognizer.result else activeRecognizer.partialResult
                val result = JSONObject(resultJson)
                val text = result.optString(if (finalized) "text" else "partial").trim()
                if (text.isNotEmpty()) {
                    mainHandler.post {
                        TikTokAudioStatus.transcript(text)
                        if (finalized) {
                            val number = VietnameseNumberParser.parseAll(text).lastOrNull()?.value
                            if (number != null) {
                                TikTokAudioStatus.number(number)
                                if (autoCommentEnabled) {
                                    TikTokAutoCommentService.autoComment(number.toString(), prefixAt)
                                }
                            }
                        }
                    }
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "TikTok playback audio capture failed", error)
            mainHandler.post {
                TikTokAudioStatus.state("Không nhận được audio TikTok Live: ${error.message ?: "lỗi capture/ASR"}; không dùng micro")
                TikTokAudioStatus.listening(false)
            }
            stopSelf()
        } finally {
            try { audioRecord?.stop() } catch (_: Exception) { }
            audioRecord?.release()
            audioRecord = null
            recognizer?.close()
            model?.close()
        }
    }

    private fun stopCapture(status: String, stopProjection: Boolean = true) {
        running = false
        try { audioRecord?.stop() } catch (_: Exception) { }
        val projection = mediaProjection
        mediaProjection = null
        if (stopProjection) {
            try { projection?.stop() } catch (_: Exception) { }
        }
        TikTokAudioStatus.listening(false)
        TikTokAudioStatus.state(status)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopCapture("Đã dừng nghe audio TikTok Live")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "TikTokPlaybackAudio"
        private const val CHANNEL_ID = "tiktok_playback_capture"
        private const val NOTIFICATION_ID = 3401
        private const val SAMPLE_RATE = 16_000
        private const val SAMPLES_PER_READ = 3_200
        private const val MIN_SIGNAL_RMS = 45.0
        private const val STATUS_INTERVAL_MS = 1_000L
        private const val NO_SIGNAL_WARNING_MS = 5_000L

        const val ACTION_START = "com.example.app.action.START_TIKTOK_AUDIO"
        const val ACTION_STOP = "com.example.app.action.STOP_TIKTOK_AUDIO"
        const val ACTION_UPDATE_SETTINGS = "com.example.app.action.UPDATE_COMMENT_SETTINGS"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_AUTO_COMMENT = "auto_comment_enabled"
        const val EXTRA_PREFIX_AT = "prefix_at"
    }
}

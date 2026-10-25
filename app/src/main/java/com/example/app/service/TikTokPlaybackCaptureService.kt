package com.example.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
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
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.example.app.parsing.VietnameseNumberParser
import com.example.app.speech.TikTokAudioStatus
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/** Captures only TikTok playback; offline Zipformer recognizes VAD speech segments. */
class TikTokPlaybackCaptureService : Service() {
    private data class AudioFrame(val samples: FloatArray, val generation: Int, val rms: Float)

    @Volatile private var running = false
    @Volatile private var autoCommentEnabled = true
    @Volatile private var autoSendEnabled = false
    @Volatile private var prefixAt = true
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var recognitionThread: Thread? = null
    private val audioFrames = ArrayBlockingQueue<AudioFrame>(FRAME_QUEUE_SIZE)
    private val audioGeneration = java.util.concurrent.atomic.AtomicInteger(0)
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
                autoSendEnabled = intent.getBooleanExtra(EXTRA_AUTO_SEND, autoSendEnabled)
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
        autoSendEnabled = intent.getBooleanExtra(EXTRA_AUTO_SEND, false)
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

            audioFrames.clear()
            audioGeneration.set(0)
            running = true
            TikTokAudioStatus.listening(true)
            TikTokAudioStatus.state("Đang nạp Zipformer-VI INT8 offline...")
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

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun captureTikTokAudio() {
        var recorder: AudioRecord? = null
        val recognizerReady = CountDownLatch(1)
        val recognitionStartupError = AtomicReference<Exception?>(null)
        try {
            check(checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                "Chưa được cấp quyền cần thiết để capture audio playback"
            }

            recognitionThread = Thread({
                var recognizer: OfflineRecognizer? = null
                var vad: Vad? = null
                try {
                    val asrThreads = recommendedAsrThreads()
                    Log.i(TAG, "Zipformer CPU threads=$asrThreads")
                    TikTokAudioStatus.state("Đang nạp Zipformer-VI INT8 và Silero VAD...")
                    recognizer = OfflineRecognizer(
                        assets,
                        OfflineRecognizerConfig(
                            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                            modelConfig = OfflineModelConfig(
                                transducer = OfflineTransducerModelConfig(
                                    encoder = "model/encoder.int8.onnx",
                                    decoder = "model/decoder.onnx",
                                    joiner = "model/joiner.int8.onnx",
                                ),
                                tokens = "model/tokens.txt",
                                modelType = "transducer",
                                modelingUnit = "cjkchar",
                                numThreads = asrThreads,
                                provider = "cpu",
                            ),
                        ),
                    )
                    vad = Vad(
                        assets,
                        VadModelConfig(
                            sileroVadModelConfig = SileroVadModelConfig(
                                model = "model/silero_vad.onnx",
                                threshold = 0.5f,
                                minSilenceDuration = VAD_MIN_SILENCE_SECONDS,
                                minSpeechDuration = 0.12f,
                                windowSize = VAD_WINDOW,
                                maxSpeechDuration = MAX_SPEECH_SECONDS.toFloat(),
                            ),
                            sampleRate = SAMPLE_RATE,
                            numThreads = 1,
                            provider = "cpu",
                        ),
                    )
                    recognizerReady.countDown()
                    if (running) {
                        TikTokAudioStatus.state("Zipformer-VI INT8 sẵn sàng · đang chờ audio TikTok")
                        processAudioFrames(recognizer, vad)
                    }
                } catch (error: Exception) {
                    recognitionStartupError.set(error)
                    recognizerReady.countDown()
                    if (running) reportCaptureFailure(error)
                } finally {
                    runCatching { vad?.release() }
                    runCatching { recognizer?.release() }
                }
            }, "tiktok-zipformer-asr").apply { start() }

            recognizerReady.await()
            recognitionStartupError.get()?.let { throw it }
            if (!running) return

            val tiktokUids = TikTokAutoCommentService.TIKTOK_PACKAGES.mapNotNull { packageName ->
                try {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(packageName, 0).uid
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }.distinct()
            check(tiktokUids.isNotEmpty()) { "Không tìm thấy gói TikTok được hỗ trợ trên điện thoại" }

            val projection = mediaProjection ?: error("Phiên MediaProjection đã kết thúc")
            val captureConfiguration = AudioPlaybackCaptureConfiguration.Builder(projection).apply {
                addMatchingUsage(AudioAttributes.USAGE_MEDIA)
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
            val createdRecorder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimumBufferSize * 2, SAMPLE_RATE))
                .setAudioPlaybackCaptureConfig(captureConfiguration)
                .build()
            check(createdRecorder.state == AudioRecord.STATE_INITIALIZED) {
                "Không khởi tạo được TikTok playback capture"
            }
            if (!running) {
                createdRecorder.release()
                return
            }
            recorder = createdRecorder
            audioRecord = createdRecorder
            createdRecorder.startRecording()
            TikTokAudioStatus.state("Đang nhận audio playback của TikTok Live; không dùng micro")

            val input = ShortArray(SAMPLES_PER_READ)
            val frame = FloatArray(VAD_WINDOW)
            var frameOffset = 0
            while (running) {
                val readCount = createdRecorder.read(input, 0, input.size, AudioRecord.READ_BLOCKING)
                if (readCount < 0) {
                    if (readCount == AudioRecord.ERROR_DEAD_OBJECT) error("Audio playback capture đã ngắt")
                    error("AudioRecord.read lỗi $readCount")
                }
                var cursor = 0
                while (cursor < readCount) {
                    val copyCount = minOf(VAD_WINDOW - frameOffset, readCount - cursor)
                    for (index in 0 until copyCount) {
                        val sample = input[cursor + index].toInt()
                        val normalized = sample / 32768.0f
                        frame[frameOffset + index] = normalized
                    }
                    frameOffset += copyCount
                    cursor += copyCount
                    if (frameOffset == VAD_WINDOW) {
                        val energy = frame.sumOf { sample ->
                            val rawSample = sample * 32768.0f
                            (rawSample * rawSample).toDouble()
                        }
                        val rms = sqrt(energy / VAD_WINDOW).toFloat()
                        enqueueFrame(AudioFrame(frame.copyOf(), audioGeneration.get(), rms))
                        frameOffset = 0
                    }
                }
            }
        } catch (error: Exception) {
            if (running) reportCaptureFailure(error)
        } finally {
            try { recorder?.stop() } catch (_: Exception) { }
            recorder?.release()
            if (audioRecord === recorder) audioRecord = null
            audioFrames.clear()
            recognitionThread?.interrupt()
        }
    }

    private fun enqueueFrame(frame: AudioFrame) {
        if (audioFrames.offer(frame)) return
        audioFrames.clear()
        val nextGeneration = audioGeneration.incrementAndGet()
        audioFrames.offer(frame.copy(generation = nextGeneration))
        mainHandler.post {
            TikTokAudioStatus.state("Zipformer xử lý chậm · bỏ audio cũ để tránh gửi số trễ")
        }
    }

    private fun processAudioFrames(recognizer: OfflineRecognizer, vad: Vad) {
        var activeGeneration = audioGeneration.get()
        var lastSignalAt = SystemClock.elapsedRealtime()
        var lastStatusAt = 0L
        while (running) {
            val frame = audioFrames.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (frame.generation != activeGeneration) {
                vad.reset()
                activeGeneration = frame.generation
            }
            val now = SystemClock.elapsedRealtime()
            if (frame.rms >= MIN_SIGNAL_RMS) lastSignalAt = now
            if (now - lastStatusAt >= STATUS_INTERVAL_MS) {
                val status = if (now - lastSignalAt > NO_SIGNAL_WARNING_MS) {
                    "Chưa nhận tiếng phát từ TikTok Live; không chuyển sang nghe micro"
                } else {
                    "Zipformer-VI đang nghe audio playback TikTok · không dùng micro"
                }
                mainHandler.post { TikTokAudioStatus.state(status) }
                lastStatusAt = now
            }

            vad.acceptWaveform(frame.samples)
            while (!vad.empty()) {
                val segment = vad.front()
                vad.pop()
                val tooLong = segment.samples.size >= SAMPLE_RATE * MAX_SPEECH_SECONDS
                val stream = recognizer.createStream()
                try {
                    stream.acceptWaveform(segment.samples, SAMPLE_RATE)
                    recognizer.decode(stream)
                    val text = recognizer.getResult(stream).text.trim()
                    if (text.isNotEmpty()) {
                        mainHandler.post {
                            TikTokAudioStatus.transcript(text)
                            val number = VietnameseNumberParser.parseAll(text)
                                .lastOrNull { it.value in 99L..999L }?.value
                            TikTokAudioStatus.number(number)
                            if (number != null) {
                                if (autoCommentEnabled) {
                                    TikTokAutoCommentService.autoComment(number.toString(), prefixAt, autoSendEnabled)
                                }
                            }
                            if (tooLong) {
                                TikTokAudioStatus.state("Câu nói quá dài; chỉ hiển thị nhận dạng, không tự comment")
                            }
                        }
                    }
                    if (tooLong) vad.reset()
                } finally {
                    runCatching { stream.release() }
                }
            }
        }
    }

    private fun reportCaptureFailure(error: Exception) {
        if (!running) return
        Log.e(TAG, "TikTok playback/Zipformer pipeline failed", error)
        running = false
        try { audioRecord?.stop() } catch (_: Exception) { }
        mainHandler.post {
            TikTokAudioStatus.state("Audio TikTok/Zipformer lỗi: ${error.message ?: "lỗi capture hoặc nhận dạng"}; không dùng micro")
            TikTokAudioStatus.listening(false)
            stopSelf()
        }
    }

    private fun stopCapture(status: String, stopProjection: Boolean = true) {
        running = false
        try { audioRecord?.stop() } catch (_: Exception) { }
        recognitionThread?.interrupt()
        audioFrames.clear()
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
        private const val SAMPLES_PER_READ = 512
        private const val VAD_WINDOW = 512
        private const val FRAME_QUEUE_SIZE = 64
        private const val VAD_MIN_SILENCE_SECONDS = 0.20f
        private const val MAX_SPEECH_SECONDS = 12
        private const val MIN_SIGNAL_RMS = 45f
        private const val STATUS_INTERVAL_MS = 1_000L
        private const val NO_SIGNAL_WARNING_MS = 5_000L

        const val ACTION_START = "com.example.app.action.START_TIKTOK_AUDIO"
        const val ACTION_STOP = "com.example.app.action.STOP_TIKTOK_AUDIO"
        const val ACTION_UPDATE_SETTINGS = "com.example.app.action.UPDATE_COMMENT_SETTINGS"
        const val EXTRA_RESULT_CODE = "projection_result_code"
        const val EXTRA_RESULT_DATA = "projection_result_data"
        const val EXTRA_AUTO_COMMENT = "auto_comment_enabled"
        const val EXTRA_AUTO_SEND = "auto_send_enabled"
        const val EXTRA_PREFIX_AT = "prefix_at"

        private fun recommendedAsrThreads(): Int =
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    }
}

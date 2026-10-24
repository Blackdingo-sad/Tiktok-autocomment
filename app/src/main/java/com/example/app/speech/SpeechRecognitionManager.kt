package com.example.app.speech

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object TikTokAudioStatus {
    private val _speechState = MutableStateFlow("Chưa bật nghe audio TikTok Live")
    val speechState: StateFlow<String> = _speechState.asStateFlow()

    private val _lastRecognizedText = MutableStateFlow("")
    val lastRecognizedText: StateFlow<String> = _lastRecognizedText.asStateFlow()

    private val _lastDetectedNumber = MutableStateFlow<Long?>(null)
    val lastDetectedNumber: StateFlow<Long?> = _lastDetectedNumber.asStateFlow()

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isPreparing = MutableStateFlow(false)
    val isPreparing: StateFlow<Boolean> = _isPreparing.asStateFlow()

    fun state(value: String) { _speechState.value = value }
    fun transcript(value: String) { _lastRecognizedText.value = value }
    fun number(value: Long?) { _lastDetectedNumber.value = value }
    fun listening(value: Boolean) { _isListening.value = value }
    fun preparing(value: Boolean) { _isPreparing.value = value }
}

class SpeechRecognitionManager(private val context: Context) {

    val speechState = TikTokAudioStatus.speechState
    val lastRecognizedText = TikTokAudioStatus.lastRecognizedText
    val lastDetectedNumber = TikTokAudioStatus.lastDetectedNumber
    val isListening = TikTokAudioStatus.isListening
    val isPreparing = TikTokAudioStatus.isPreparing

    var autoCommentEnabled: Boolean = true
    var prefixAt: Boolean = true

    suspend fun prepareModel() {
        TikTokAudioStatus.preparing(true)
        TikTokAudioStatus.state("Đang chuẩn bị model tiếng Việt lần đầu...")
        try {
            VietnameseVoskModel.ensureInstalled(context) { percent ->
                TikTokAudioStatus.state("Đang tải model ASR tiếng Việt: $percent%")
            }
            TikTokAudioStatus.state("Model ASR tiếng Việt đã sẵn sàng")
        } catch (error: Exception) {
            TikTokAudioStatus.state("Không tải được model ASR: ${error.message ?: "lỗi mạng"}")
            throw error
        } finally {
            TikTokAudioStatus.preparing(false)
        }
    }

    fun startListening(resultCode: Int, projectionData: Intent) {
        val serviceIntent = Intent(context, com.example.app.service.TikTokPlaybackCaptureService::class.java).apply {
            action = com.example.app.service.TikTokPlaybackCaptureService.ACTION_START
            putExtra(com.example.app.service.TikTokPlaybackCaptureService.EXTRA_RESULT_CODE, resultCode)
            putExtra(com.example.app.service.TikTokPlaybackCaptureService.EXTRA_RESULT_DATA, projectionData)
            putExtra(com.example.app.service.TikTokPlaybackCaptureService.EXTRA_AUTO_COMMENT, autoCommentEnabled)
            putExtra(com.example.app.service.TikTokPlaybackCaptureService.EXTRA_PREFIX_AT, prefixAt)
        }
        androidx.core.content.ContextCompat.startForegroundService(context, serviceIntent)
    }

    fun updateSettings() {
        if (!isListening.value) return
        val serviceIntent = Intent(context, com.example.app.service.TikTokPlaybackCaptureService::class.java).apply {
            action = com.example.app.service.TikTokPlaybackCaptureService.ACTION_UPDATE_SETTINGS
            putExtra(com.example.app.service.TikTokPlaybackCaptureService.EXTRA_AUTO_COMMENT, autoCommentEnabled)
            putExtra(com.example.app.service.TikTokPlaybackCaptureService.EXTRA_PREFIX_AT, prefixAt)
        }
        context.startService(serviceIntent)
    }

    fun stopListening() {
        context.startService(
            Intent(context, com.example.app.service.TikTokPlaybackCaptureService::class.java).apply {
                action = com.example.app.service.TikTokPlaybackCaptureService.ACTION_STOP
            }
        )
    }

    fun reportStatus(message: String) = TikTokAudioStatus.state(message)
}

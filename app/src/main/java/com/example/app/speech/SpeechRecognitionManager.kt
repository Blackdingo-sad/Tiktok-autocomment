package com.example.app.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.app.parsing.VietnameseNumberParser
import com.example.app.service.TikTokAutoCommentService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpeechRecognitionManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false

    private val _speechState = MutableStateFlow("Chưa bật nhận diện giọng nói")
    val speechState: StateFlow<String> = _speechState.asStateFlow()

    private val _lastRecognizedText = MutableStateFlow("")
    val lastRecognizedText: StateFlow<String> = _lastRecognizedText.asStateFlow()

    private val _lastDetectedNumber = MutableStateFlow<Long?>(null)
    val lastDetectedNumber: StateFlow<Long?> = _lastDetectedNumber.asStateFlow()

    var autoCommentEnabled: Boolean = true
    var prefixAt: Boolean = true

    fun startListening() {
        if (isListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _speechState.value = "Thiết bị không hỗ trợ nhận diện giọng nói SpeechRecognizer"
            return
        }

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "vi-VN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            _speechState.value = "Đang lắng nghe tiếng Việt..."
        } catch (e: Exception) {
            Log.e(TAG, "Error starting speech recognizer", e)
            _speechState.value = "Lỗi khi khởi động nhận diện: ${e.message}"
        }
    }

    fun stopListening() {
        isListening = false
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        _speechState.value = "Đã dừng lắng nghe."
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _speechState.value = "Sẵn sàng nhận diện giọng nói..."
        }

        override fun onBeginningOfSpeech() {
            _speechState.value = "Đang thu âm giọng nói..."
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            _speechState.value = "Đang xử lý âm thanh..."
        }

        override fun onError(error: Int) {
            Log.w(TAG, "SpeechRecognizer error code: $error")
            isListening = false
            if (error != SpeechRecognizer.ERROR_CLIENT) {
                _speechState.value = "Đang kết nối lại microphone..."
                startListening()
            }
        }

        override fun onResults(results: Bundle?) {
            processBundleResults(results, isFinal = true)
            isListening = false
            startListening()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            processBundleResults(partialResults, isFinal = false)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun processBundleResults(bundle: Bundle?, isFinal: Boolean) {
        val matches = bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return
        if (matches.isEmpty()) return

        val text = matches[0]
        _lastRecognizedText.value = text

        val numbers = VietnameseNumberParser.parseAll(text)
        if (numbers.isNotEmpty()) {
            val lastNum = numbers.last().value
            _lastDetectedNumber.value = lastNum

            if (isFinal && autoCommentEnabled) {
                TikTokAutoCommentService.autoComment(lastNum.toString(), prefixAt)
            }
        }
    }

    companion object {
        private const val TAG = "SpeechManager"
    }
}

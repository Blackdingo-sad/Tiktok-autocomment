package com.example.app.speech

import android.content.Context

/** Zipformer-VI INT8 and Silero VAD are packaged locally; no model download is needed. */
internal object ZipformerModelAssets {
    private val requiredFiles = listOf(
        "model/encoder.int8.onnx",
        "model/decoder.onnx",
        "model/joiner.int8.onnx",
        "model/tokens.txt",
        "model/silero_vad.onnx",
    )

    fun verify(context: Context) {
        requiredFiles.forEach { path ->
            context.assets.open(path).use { }
        }
    }
}

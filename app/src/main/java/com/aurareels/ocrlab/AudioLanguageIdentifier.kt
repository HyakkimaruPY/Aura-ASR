package com.aurareels.ocrlab

import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import java.io.Closeable

/** Independent acoustic language identification; never trusts the ASR's guessed words. */
class AudioLanguageIdentifier(private val repo: ModelRepository) : Closeable {
    private var detector: SpokenLanguageIdentification? = null
    /** Untouched string returned by the model (for the debug log). */
    @Volatile var lastRaw: String = ""; private set

    fun identify(pcm16k: FloatArray): String {
        require(repo.installed("lid")) { "Baixe o detector de áudio na aba Modelos" }
        SherpaNative.ensure()
        val current = detector ?: SpokenLanguageIdentification(
            SpokenLanguageIdentificationConfig.builder()
                .setWhisper(
                    SpokenLanguageIdentificationWhisperConfig.builder()
                        .setEncoder(repo.file("lid", "tiny-encoder.int8.onnx").absolutePath)
                        .setDecoder(repo.file("lid", "tiny-decoder.int8.onnx").absolutePath)
                        .build()
                ).setNumThreads(2).build()
        ).also { detector = it }
        val stream = current.createStream()
        return try {
            stream.acceptWaveform(pcm16k, 16000)
            // Canonical code ("zh-TW"/"yue" -> "zh"); "" when Whisper reports nothing usable.
            val raw = current.compute(stream) ?: ""
            lastRaw = raw
            LanguageMap.canonical(raw)
        } finally { stream.release() }
    }
    override fun close() { detector?.release(); detector = null }
}

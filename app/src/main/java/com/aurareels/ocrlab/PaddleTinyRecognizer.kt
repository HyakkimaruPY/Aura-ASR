package com.aurareels.ocrlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** PP-OCRv6 Small recognition-only ONNX engine. Expects the matching dictionary order. */
class PaddleTinyRecognizer(context: Context, private val modelDir: File) : Closeable {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(2)
        setInterOpNumThreads(1)
    }
    private val session: OrtSession
    private val chars: List<String>
    private val inputName: String

    init {
        val model = File(modelDir, "inference.onnx").readBytes()
        chars = File(modelDir, "rec_chars.txt").readLines()
        require(chars.size > 100) { "Dicionário do modelo está ausente ou incompleto" }
        session = env.createSession(model, opts)
        inputName = session.inputNames.first()
        // Keep the model resident; loading an ONNX session per frame is very expensive.
    }

    fun recognize(line: Bitmap): String {
        val targetHeight = 48
        val recWidth = max(1, (line.width * targetHeight.toFloat() / line.height).toInt())
        val inputWidth = (ceil(recWidth / 32.0).toInt() * 32).coerceIn(160, 1024)
        val paintedWidth = min(recWidth, inputWidth)
        val scaled = Bitmap.createBitmap(inputWidth, targetHeight, Bitmap.Config.ARGB_8888)
        Canvas(scaled).apply {
            drawColor(Color.WHITE)
            drawBitmap(line, null,
                android.graphics.Rect(0, 0, paintedWidth, targetHeight),
                Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val raw = IntArray(inputWidth * targetHeight)
        scaled.getPixels(raw, 0, inputWidth, 0, 0, inputWidth, targetHeight)
        val data = FloatArray(raw.size * 3)
        val plane = raw.size
        for (i in raw.indices) {
            val pixel = raw[i]
            // PaddleOCR RecResizeImg normalization: BGR / 255, then (value-0.5)/0.5.
            data[i] = ((pixel and 255) / 127.5f) - 1f
            data[plane + i] = (((pixel shr 8) and 255) / 127.5f) - 1f
            data[2 * plane + i] = (((pixel shr 16) and 255) / 127.5f) - 1f
        }
        scaled.recycle()
        val shape = longArrayOf(1, 3, targetHeight.toLong(), inputWidth.toLong())
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                val batch = result[0].value as Array<*>
                val steps = batch[0] as Array<*>
                val output = StringBuilder()
                var previous = -1
                for (entry in steps) {
                    val probabilities = entry as FloatArray
                    var best = 0
                    var bestScore = Float.NEGATIVE_INFINITY
                    for (i in probabilities.indices) {
                        if (probabilities[i] > bestScore) {
                            best = i
                            bestScore = probabilities[i]
                        }
                    }
                    // CTC blank = 0, chars index 1..N, remove consecutive duplicates.
                    if (best > 0 && best != previous && best - 1 < chars.size) {
                        output.append(chars[best - 1])
                    }
                    previous = best
                }
                return output.toString().trim()
            }
        }
    }

    override fun close() {
        session.close()
        opts.close()
        // OrtEnvironment is process-wide; it is not closed from this component.
    }
}

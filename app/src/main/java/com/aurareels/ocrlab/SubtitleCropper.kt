package com.aurareels.ocrlab

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Finds 1-3 subtitle lines in a broad ROI. No coordinates are hardcoded in pixels:
 * input can be 16:9 or 9:16. It scores dark ink next to a light/white background.
 * It intentionally ignores the lower area where the sample has English and a watermark.
 * The user can adjust top and bottom fractions in the UI.
 */
class SubtitleCropper {
    data class Extraction(val region: Bitmap, val lines: List<Bitmap>, val signature: Long)

    fun extract(frame: Bitmap, top: Float, bottom: Float): Extraction {
        val w = frame.width
        val h = frame.height
        val l = (w * 0.08f).toInt()
        val r = (w * 0.83f).toInt()
        val t = (h * top.coerceIn(0.2f, 0.9f)).toInt()
        val b = (h * bottom.coerceIn(top + 0.04f, 0.99f)).toInt()
        val rw = (r - l).coerceAtLeast(1)
        val rh = (b - t).coerceAtLeast(1)
        val region = Bitmap.createBitmap(frame, l, t, rw, rh)
        val pixels = IntArray(rw * rh)
        region.getPixels(pixels, 0, rw, 0, 0, rw, rh)

        // Light surrounds can be present beside dark strokes (black-on-white subtitles).
        val bright = BooleanArray(pixels.size)
        val dark = BooleanArray(pixels.size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val red = (p shr 16) and 255
            val green = (p shr 8) and 255
            val blue = p and 255
            val hi = max(red, max(green, blue))
            val lo = min(red, min(green, blue))
            bright[i] = lo > 215 && (hi - lo) < 38
            dark[i] = (red + green + blue) < 405
        }
        val ink = BooleanArray(pixels.size)
        val rowScore = IntArray(rh)
        for (y in 0 until rh) {
            for (x in 9 until rw - 9) {
                val i = y * rw + x
                if (dark[i] && (bright[i - 8] || bright[i - 4] ||
                            bright[i + 4] || bright[i + 8])) {
                    ink[i] = true
                    rowScore[y]++
                }
            }
        }
        val minInk = max(9, rw / 55)
        val activeRows = (0 until rh).filter { rowScore[it] >= minInk }
        val rowBands = groups(activeRows, 5)
            .filter { it.last() - it.first() + 1 >= max(8, h / 85) }
            .sortedByDescending { band -> (band.last() - band.first() + 1) * rowScore.slice(band.first()..band.last()).sum() }
            .take(3)
            .sortedBy { it.first() }

        val lineBitmaps = ArrayList<Bitmap>()
        for (band in rowBands) {
            val ys = max(0, band.first() - 7)
            val ye = min(rh, band.last() + 8)
            val colScores = IntArray(rw)
            for (y in ys until ye) for (x in 0 until rw) if (ink[y * rw + x]) colScores[x]++
            val activeCols = (0 until rw).filter { colScores[it] >= 3 }
            val colGroups = groups(activeCols, 22)
                .filter { it.last() - it.first() > max(55, rw / 7) }
            val best = colGroups.maxByOrNull { it.size } ?: continue
            val xs = max(0, best.first() - 13)
            val xe = min(rw, best.last() + 14)
            if (xe > xs && ye > ys) {
                lineBitmaps.add(Bitmap.createBitmap(region, xs, ys, xe - xs, ye - ys))
            }
        }

        // Signature uses only the cropped lines; movement elsewhere in the movie
        // does not repeatedly trigger OCR. One check runs at least every 2 seconds.
        var signature = 0xcbf29ce484222325UL.toLong()
        for (line in lineBitmaps) {
            val sx = 16
            val sy = 8
            for (iy in 0 until sy) for (ix in 0 until sx) {
                val pixel = line.getPixel(ix * (line.width - 1) / (sx - 1), iy * (line.height - 1) / (sy - 1))
                val v = (((pixel shr 16) and 255) + ((pixel shr 8) and 255) + (pixel and 255)) / 3
                signature = (signature xor (v / 20).toLong()) * 1099511628211L
            }
        }
        signature = signature xor lineBitmaps.size.toLong()
        return Extraction(region, lineBitmaps, signature)
    }

    private fun groups(input: List<Int>, maxGap: Int): List<List<Int>> {
        if (input.isEmpty()) return emptyList()
        val result = ArrayList<MutableList<Int>>()
        for (p in input) {
            if (result.isEmpty() || p - result.last().last() > maxGap) result.add(mutableListOf(p))
            else result.last().add(p)
        }
        return result
    }
}

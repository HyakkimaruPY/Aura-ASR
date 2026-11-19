package com.aurareels.ocrlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.TextureView
import android.view.View

/**
 * EXPERIMENTAL hard-subtitle concealment. Not a real inpainting model: the band is rebuilt from the
 * picture just above and just below it, blurred and cross-faded vertically. On smooth backgrounds
 * (sky, walls, skin, dark scenes) the text simply disappears; on busy textures a soft smear remains.
 */
object MaskPainter {
    private const val TINY_W = 40
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val tiny: Bitmap = Bitmap.createBitmap(TINY_W, 2, Bitmap.Config.ARGB_8888)
    private val tinyCanvas = Canvas(tiny)
    private val src = Rect()
    private val dst = Rect(0, 0, TINY_W, 1)
    private val dst2 = Rect(0, 1, TINY_W, 2)

    /** Draws the concealed band over [area] (where [frame] is drawn). Percentages are of the picture height. */
    fun draw(canvas: Canvas, frame: Bitmap, area: RectF, centerPercent: Int, heightPercent: Int) {
        val topF = ((centerPercent - heightPercent / 2f) / 100f).coerceIn(0f, 1f)
        val botF = ((centerPercent + heightPercent / 2f) / 100f).coerceIn(0f, 1f)
        if (botF - topF < 0.01f || frame.height < 8) return
        val fw = frame.width; val fh = frame.height
        val aboveY = ((topF * fh).toInt() - 3).coerceIn(0, fh - 2)
        val belowY = ((botF * fh).toInt() + 1).coerceIn(0, fh - 2)
        src.set(0, aboveY, fw, aboveY + 2); tinyCanvas.drawBitmap(frame, src, dst, paint)
        src.set(0, belowY, fw, belowY + 2); tinyCanvas.drawBitmap(frame, src, dst2, paint)
        val band = RectF(area.left, area.top + topF * area.height(), area.right, area.top + botF * area.height())
        canvas.drawBitmap(tiny, null, band, paint)
    }
}

/** Lives inside the player's content frame, between the picture and the translated subtitles. */
class SubtitleMaskView(context: Context, private val texture: TextureView) : View(context) {
    var centerPercent = 86
    var heightPercent = 12
    var active = false
        set(value) { field = value; visibility = if (value) VISIBLE else GONE; if (value) restart() }
    private var frame: Bitmap? = null
    private val area = RectF()
    private val loop = object : Runnable {
        override fun run() { if (!active) return; invalidate(); postDelayed(this, 80) }
    }
    init { visibility = GONE; isClickable = false }
    private fun restart() { removeCallbacks(loop); post(loop) }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (active) restart() }
    override fun onDetachedFromWindow() { removeCallbacks(loop); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        if (!active || !texture.isAvailable || texture.width <= 0 || texture.height <= 0) return
        val w = 160
        val h = (w * texture.height.toFloat() / texture.width).toInt().coerceIn(16, 512)
        var f = frame
        if (f == null || f.width != w || f.height != h) { f = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); frame = f }
        texture.getBitmap(f!!)
        area.set(0f, 0f, width.toFloat(), height.toFloat())
        MaskPainter.draw(canvas, f, area, centerPercent, heightPercent)
    }
}

/** Frame captured from the video with the concealment band drawn on it; drag vertically to move the band. */
class MaskPreviewView(context: Context, private val frame: Bitmap,
                      var centerPercent: Int, var heightPercent: Int,
                      private val onDrag: (Int) -> Unit) : View(context) {
    private val fit = RectF()
    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val outline = Paint().apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2f * resources.displayMetrics.density; isAntiAlias = true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val natural = (w.toFloat() * frame.height / frame.width).toInt()
        setMeasuredDimension(w, natural.coerceAtMost((resources.displayMetrics.heightPixels * 0.5f).toInt()))
    }

    override fun onDraw(canvas: Canvas) {
        val scale = minOf(width.toFloat() / frame.width, height.toFloat() / frame.height)
        val fw = frame.width * scale; val fh = frame.height * scale
        fit.set((width - fw) / 2f, (height - fh) / 2f, (width + fw) / 2f, (height + fh) / 2f)
        canvas.drawBitmap(frame, null, fit, framePaint)
        MaskPainter.draw(canvas, frame, fit, centerPercent, heightPercent)
        val top = fit.top + (centerPercent - heightPercent / 2f) / 100f * fit.height()
        val bottom = fit.top + (centerPercent + heightPercent / 2f) / 100f * fit.height()
        canvas.drawRect(fit.left + 1f, top, fit.right - 1f, bottom, outline)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
            val p = ((event.y - fit.top) / fit.height() * 100f).toInt().coerceIn(5, 98)
            onDrag(p)
        }
        return true
    }
}

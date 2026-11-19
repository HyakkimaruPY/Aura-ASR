package com.aurareels.ocrlab

import android.graphics.*
import android.graphics.drawable.Drawable

/** Three cached radial shaders and a black vignette; no blur, decoder or per-frame palette reads. */
class MusicBackdrop(private val palette: IntArray) : Drawable() {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var clouds=emptyList<Shader>()
    private var vignette: Shader?=null
    override fun onBoundsChange(bounds: Rect) {
        val w=bounds.width().toFloat(); val h=bounds.height().toFloat(); val r=maxOf(w,h)*0.7f
        if(r<=0) return
        val points=listOf(0.12f to 0.18f,0.92f to 0.36f,0.4f to 0.84f)
        clouds=points.mapIndexed { i,point -> RadialGradient(w*point.first,h*point.second,r,intArrayOf(withAlpha(palette[i%palette.size],100),Color.TRANSPARENT),null,Shader.TileMode.CLAMP) }
        vignette=RadialGradient(w/2,h/2,maxOf(w,h)*0.8f,intArrayOf(Color.TRANSPARENT,0xdd000000.toInt()),floatArrayOf(0.2f,1f),Shader.TileMode.CLAMP)
    }
    override fun draw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        clouds.forEach { paint.shader=it; canvas.drawRect(bounds,paint) }
        paint.shader=vignette; canvas.drawRect(bounds,paint); paint.shader=null
    }
    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Drawable API") override fun getOpacity()=PixelFormat.OPAQUE
    private fun withAlpha(color: Int,alpha: Int)=(color and 0xffffff) or (alpha shl 24)
    companion object {
        /** Called exactly once on the file worker for each newly loaded cover. */
        fun palette(bitmap: Bitmap?): IntArray {
            if(bitmap==null) return intArrayOf(0xff315e50.toInt(),0xff244b59.toInt(),0xff443054.toInt())
            val sample=Bitmap.createScaledBitmap(bitmap,48,48,true)
            try {
                val buckets=HashMap<Int,LongArray>(); val pixels=IntArray(48*48); sample.getPixels(pixels,0,48,0,0,48,48)
                pixels.forEach { c ->
                    val r=Color.red(c); val g=Color.green(c); val b=Color.blue(c)
                    if(maxOf(r,g,b)<24 || minOf(r,g,b)>235) return@forEach
                    val key=(r shr 5 shl 6) or (g shr 5 shl 3) or (b shr 5)
                    val sums=buckets.getOrPut(key) { LongArray(4) }; sums[0]+=r; sums[1]+=g; sums[2]+=b; sums[3]++
                }
                val colors=buckets.values.sortedByDescending { it[3] }.take(3).map { Color.rgb((it[0]/it[3]).toInt(),(it[1]/it[3]).toInt(),(it[2]/it[3]).toInt()) }
                return IntArray(3) { colors.getOrNull(it) ?: colors.firstOrNull() ?: 0xff315e50.toInt() }
            } finally { if(sample!==bitmap) sample.recycle() }
        }
    }
}

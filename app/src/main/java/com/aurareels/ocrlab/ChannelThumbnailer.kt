package com.aurareels.ocrlab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import android.view.ViewGroup
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.Closeable

/**
 * One bounded, silent HLS decoder. MetadataRetriever cannot reliably seek live manifests.
 * Starts at the live default position, waits for rendered pixels, and never caches black frames.
 * Call on main; closing/cancelling releases the player and its network connections.
 */
class ChannelThumbnailer(private val context: Context, private val host: ViewGroup, private val userAgent: String) : Closeable {
    private data class Cached(val bitmap: Bitmap, val at: Long)
    private data class Request(val url: String, val headers: Map<String, String>, val kind: String, val callbacks: MutableList<(Bitmap?) -> Unit>)

    companion object {
        private const val THUMB_W = 320
        private const val THUMB_H = 180
        private const val CACHE_TTL_MS = 5 * 60_000L
    }

    private val cache = android.util.LruCache<String, Cached>(48)
    private val queue = java.util.ArrayDeque<Request>()
    private val handler = Handler(Looper.getMainLooper())
    private val pixelScratch = IntArray(THUMB_W * THUMB_H)
    private var active: Request? = null
    private var player: ExoPlayer? = null
    private var surface: TextureView? = null
    private var closed = false
    private var paused = true
    private var serial = 0

    /** Instant RAM lookup so channel cards bind already-captured frames with zero icon flicker on refresh. */
    fun peek(url: String): Bitmap? =
        cache.get(url)?.takeIf { SystemClock.elapsedRealtime() - it.at < CACHE_TTL_MS && !it.bitmap.isRecycled }?.bitmap

    fun load(url: String, headers: Map<String, String> = emptyMap(), kind: String = "HLS", done: (Bitmap?) -> Unit) {
        peek(url)?.let { done(it); return }
        if (closed || paused) { done(null); return }
        val existing = active?.takeIf { it.url == url } ?: queue.firstOrNull { it.url == url }
        if (existing != null) { existing.callbacks.add(done); return }
        if (queue.size >= 24) { done(null); return }
        queue.addLast(Request(url, headers, kind, mutableListOf(done)))
        next()
    }

    fun setPaused(value: Boolean) {
        paused = value
        if (value) {
            queue.clear()
            if (active != null) finish(null)
        } else next()
    }

    private fun next() {
        if (closed || paused || active != null || queue.isEmpty()) return
        val request = queue.removeFirst(); active = request
        val token = ++serial
        val texture = TextureView(context).apply { alpha = 0.01f; isOpaque = false }
        surface = texture
        host.addView(texture, 0, ViewGroup.LayoutParams(THUMB_W, THUMB_H))
        val timeout = Runnable { if (token == serial) finish(null) }
        handler.postDelayed(timeout, 12_000)

        fun capture(attempt: Int = 0) {
            if (token != serial || closed) return
            val bitmap = runCatching { texture.getBitmap(THUMB_W, THUMB_H) }.getOrNull()
            if (bitmap != null && usable(bitmap)) {
                cache.put(request.url, Cached(bitmap, SystemClock.elapsedRealtime()))
                finish(bitmap)
            } else {
                bitmap?.recycle()
                if (attempt < 8) handler.postDelayed({ capture(attempt + 1) }, 700)
            }
        }

        fun prepare() {
            if (token != serial || player != null || closed) return
            runCatching {
                val http = DefaultHttpDataSource.Factory().setUserAgent(userAgent).setDefaultRequestProperties(request.headers)
                    .setConnectTimeoutMs(5000).setReadTimeoutMs(5000).setAllowCrossProtocolRedirects(true)
                val decoder = ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))).build()
                player = decoder
                decoder.volume = 0f
                decoder.trackSelectionParameters = decoder.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).setMaxVideoSize(640, 360).build()
                decoder.setVideoTextureView(texture)
                decoder.addListener(object : Player.Listener {
                    private var captured = false
                    override fun onRenderedFirstFrame() {
                        if (!captured) { captured = true; handler.postDelayed({ capture() }, 400) }
                    }
                    override fun onPlayerError(error: PlaybackException) { if (token == serial) finish(null) }
                })
                decoder.setMediaItem(MediaItem.Builder().setUri(request.url).apply {
                    when (request.kind) {
                        "HLS" -> setMimeType(MimeTypes.APPLICATION_M3U8)
                        "DASH" -> setMimeType(MimeTypes.APPLICATION_MPD)
                    }
                }.build())
                decoder.seekToDefaultPosition(); decoder.prepare(); decoder.playWhenReady = true
            }.onFailure { if (token == serial) finish(null) }
        }

        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) { prepare() }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {}
        }
        if (texture.isAvailable) prepare()
    }

    /** Single bulk JNI pixel copy instead of 640 individual `Bitmap.getPixel` JNI round-trips on the UI thread. */
    private fun usable(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w <= 0 || h <= 0) return false
        val buf = if (w * h <= pixelScratch.size) pixelScratch else IntArray(w * h)
        bitmap.getPixels(buf, 0, w, 0, 0, w, h)
        var bright = 0
        for (y in 0 until h step 9) {
            val rowOffset = y * w
            for (x in 0 until w step 10) {
                val pixel = buf[rowOffset + x]
                if (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) > 45) {
                    if (++bright >= 12) return true
                }
            }
        }
        return false
    }

    private fun finish(bitmap: Bitmap?) {
        val request = active ?: return
        serial++; handler.removeCallbacksAndMessages(null)
        player?.release(); player = null
        surface?.let { host.removeView(it) }; surface = null; active = null
        request.callbacks.forEach { it(bitmap) }
        next()
    }

    override fun close() {
        closed = true; queue.clear(); finish(null); cache.evictAll(); handler.removeCallbacksAndMessages(null)
    }
}

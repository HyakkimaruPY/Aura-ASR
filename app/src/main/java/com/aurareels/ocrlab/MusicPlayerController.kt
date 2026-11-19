package com.aurareels.ocrlab

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.TransitionDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.TextView
import androidx.media3.exoplayer.ExoPlayer

/**
 * Modular UI and notification controller for the **Músicas** tab:
 *  - Mini-player bar docked above the bottom edge.
 *  - Full-screen Spotify-style **TOCANDO AGORA** overlay (portrait & landscape responsive layout).
 *  - Stacked **LETRA** page (`LyricPageFrame` + `LyricsListView`) with dynamic `MusicBackdrop` crossfades.
 *  - `MusicBridge` / `MusicService` media notification synchronization.
 */
class MusicPlayerController(
    private val activity: Activity,
    private val rootFrame: FrameLayout,
    private val widgets: UiWidgets,
    private val pages: () -> ContentPageHost,
    private val queuePolicy: MusicQueuePolicy,
    private val activeTab: () -> Int,
    private val currentMusic: () -> LocalVideo?,
    private val musicPlayer: () -> ExoPlayer?,
    private val onTogglePlay: () -> Unit,
    private val onStep: (Int) -> Unit,
    private val onPrevious: () -> Unit,
    private val onStopMusic: () -> Unit,
    private val onRequestRefreshUi: () -> Unit
) {
    lateinit var musicBar: LinearLayout; private set
    private lateinit var musicMiniCover: ImageView
    private lateinit var musicMiniTitle: TextView
    private lateinit var musicMiniLyric: TextView
    private lateinit var musicMiniProgress: ProgressBar
    private lateinit var musicMiniPlay: ImageView

    private var nowOverlay: FrameLayout? = null
    val nowOpen: Boolean get() = nowOverlay != null
    private var lyricsOverlay: FrameLayout? = null
    val lyricsOpen: Boolean get() = lyricsOverlay != null
    private var lyricsList: LyricsListView? = null

    private var nowSeek: SeekBar? = null
    private var nowTime: TextView? = null
    private var nowPlay: ImageView? = null
    private var nowPreview: TextView? = null
    private var nowShuffle: ImageView? = null
    private var nowRepeat: ImageView? = null
    private var nowRepeatBadge: TextView? = null
    private var nowStatus: TextView? = null
    private var nowTitle: TextView? = null
    private var nowArtist: TextView? = null
    private var nowCover: ImageView? = null
    private var nowCoverShown: Bitmap? = null
    private var shownMiniCover: Bitmap? = null
    private var musicSeeking = false
    private var musicSig = ""
    private var currentPalette: IntArray = MusicBackdrop.palette(null)

    fun installBridgeCallbacks() {
        MusicBridge.onPlayPause = { onTogglePlay(); syncNotification(force = true) }
        MusicBridge.onNext = { onStep(1) }
        MusicBridge.onPrevious = { onPrevious() }
        MusicBridge.onStop = { onStopMusic() }
        MusicBridge.onSeek = { ms -> musicPlayer()?.seekTo(ms) }
    }

    fun buildMusicBar() {
        musicBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = widgets.ripple(0xf01c1c1c.toInt(), 16f, 0xff262626.toInt())
            setPadding(widgets.dp(8), widgets.dp(6), widgets.dp(6), widgets.dp(6))
            elevation = widgets.dp(8).toFloat()
            setOnClickListener { showNowPlaying() }
        }
        musicMiniCover = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = widgets.rounded(0xff303030.toInt(), 8f)
            clipToOutline = true
        }
        musicBar.addView(musicMiniCover, LinearLayout.LayoutParams(widgets.dp(44), widgets.dp(44)).apply { rightMargin = widgets.dp(10) })
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        musicMiniTitle = widgets.label("", 13f, true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        musicMiniLyric = widgets.label("", 12f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(0xff3DDC84.toInt())
        }
        texts.addView(musicMiniTitle)
        texts.addView(musicMiniLyric)
        musicMiniProgress = widgets.progressBar(0, 0)
        texts.addView(musicMiniProgress, LinearLayout.LayoutParams(-1, widgets.dp(2)).apply { topMargin = widgets.dp(4) })
        musicBar.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        musicMiniPlay = widgets.iconOnly(R.drawable.ic_play) { onTogglePlay() }.apply {
            contentDescription = "Tocar ou pausar"
        }
        musicBar.addView(musicMiniPlay, LinearLayout.LayoutParams(widgets.dp(48), widgets.dp(48)))
        musicBar.addView(
            widgets.iconOnly(R.drawable.ic_next) { onStep(1) }.apply { contentDescription = "Próxima" },
            LinearLayout.LayoutParams(widgets.dp(48), widgets.dp(48))
        )
        rootFrame.addView(
            musicBar,
            FrameLayout.LayoutParams(-1, widgets.dp(64), Gravity.BOTTOM).apply {
                setMargins(widgets.dp(8), 0, widgets.dp(8), widgets.dp(8))
            }
        )
    }

    fun resetTrackVisuals() {
        shownMiniCover = null
        musicSig = ""
    }

    fun updatePalette(palette: IntArray) {
        currentPalette = palette
        applyMusicBackdrop(nowOverlay)
        applyMusicBackdrop(lyricsOverlay)
    }

    fun hideAndCloseAll() {
        musicSig = ""
        if (::musicBar.isInitialized) musicBar.visibility = View.GONE
        closeNowPlaying()
    }

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private var currentTitleText = ""
    private var currentArtistText = ""
    private var currentBitmap: Bitmap? = null

    fun syncNotification(
        titleText: String = currentTitleText,
        artistText: String = currentArtistText,
        artwork: Bitmap? = currentBitmap,
        force: Boolean = false
    ) {
        currentTitleText = titleText
        currentArtistText = artistText
        currentBitmap = artwork
        val player = musicPlayer() ?: return
        if (currentMusic() == null) return
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val signature = "$titleText|$artistText|${player.isPlaying}|$duration|${System.identityHashCode(artwork)}"
        if (!force && signature == musicSig) return
        musicSig = signature
        MusicBridge.state = MusicBridge.State(
            titleText,
            artistText,
            player.isPlaying,
            player.currentPosition.coerceAtLeast(0),
            duration,
            artwork
        )
        MusicService.poke()
    }

    fun render(
        titleText: String,
        artistText: String,
        artwork: Bitmap?,
        cues: List<SpeechCue>,
        index: Int,
        synced: Boolean,
        position: Long,
        duration: Long,
        playing: Boolean,
        status: String,
        preview: String
    ) {
        currentTitleText = titleText
        currentArtistText = artistText
        currentBitmap = artwork
        musicBar.visibility = View.VISIBLE
        musicMiniTitle.text = titleText + if (artistText.isBlank()) "" else " · $artistText"
        musicMiniLyric.text = preview.ifEmpty { status }
        musicMiniLyric.visibility = if (musicMiniLyric.text.isEmpty()) View.GONE else View.VISIBLE
        if (musicMiniPlay.tag != playing) {
            musicMiniPlay.tag = playing
            musicMiniPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        }
        musicMiniProgress.progress = if (duration > 0) (position.toDouble() / duration * 1000).toInt().coerceIn(0, 1000) else 0
        if (shownMiniCover !== artwork) {
            shownMiniCover = artwork
            musicMiniCover.setImageBitmap(artwork)
        }
        if (nowOpen) {
            nowTitle?.let { if (it.text.toString() != titleText) it.text = titleText }
            nowArtist?.let {
                if (it.text.toString() != artistText) {
                    it.text = artistText
                    it.visibility = if (artistText.isBlank()) View.GONE else View.VISIBLE
                }
            }
            nowStatus?.let {
                if (it.text.toString() != status) {
                    it.text = status
                    it.visibility = if (status.isEmpty()) View.GONE else View.VISIBLE
                }
            }
            if (nowCoverShown !== artwork) {
                nowCoverShown = artwork
                nowCover?.setImageBitmap(artwork)
            }
            nowSeek?.let {
                if (!musicSeeking) {
                    it.max = (duration / 1000).toInt()
                    it.progress = (position / 1000).toInt()
                }
            }
            nowTime?.text = "${clock(position)} / ${clock(duration)}"
            nowPreview?.let {
                if (it.text.toString() != preview) it.text = preview
                it.visibility = if (preview.isEmpty()) View.INVISIBLE else View.VISIBLE
            }
            nowPlay?.let {
                if (it.tag != playing) {
                    it.tag = playing
                    it.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                }
            }
            nowShuffle?.let {
                val on = queuePolicy.shuffle
                if (it.tag != on) {
                    it.tag = on
                    it.setColorFilter(if (on) 0xff3DDC84.toInt() else Color.WHITE)
                }
            }
            nowRepeat?.let {
                val mode = queuePolicy.repeat
                if (it.tag != mode) {
                    it.tag = mode
                    it.setColorFilter(if (mode == RepeatMode.OFF) Color.WHITE else 0xff3DDC84.toInt())
                    it.contentDescription = when (mode) {
                        RepeatMode.OFF -> "Repetir: desligado"
                        RepeatMode.QUEUE -> "Repetir: pasta"
                        RepeatMode.TRACK -> "Repetir: faixa atual"
                    }
                    nowRepeatBadge?.visibility = if (mode == RepeatMode.TRACK) View.VISIBLE else View.GONE
                }
            }
            lyricsList?.update(cues, index, synced)
        }
        syncNotification(titleText, artistText, artwork)
    }

    private fun applyMusicBackdrop(view: View?) {
        view ?: return
        val next = MusicBackdrop(currentPalette)
        val old = view.background
        if (old == null) {
            view.background = next
        } else {
            view.background = TransitionDrawable(arrayOf(old, next)).apply {
                isCrossFadeEnabled = true
                startTransition(1000)
            }
        }
    }

    fun closeNowPlaying(animate: Boolean = false) {
        val overlay = nowOverlay ?: return
        closeLyricsPage()
        nowOverlay = null; nowSeek = null; nowTime = null; nowPlay = null; nowStatus = null; nowPreview = null
        nowShuffle = null; nowRepeat = null; nowRepeatBadge = null
        nowTitle = null; nowArtist = null; nowCover = null; nowCoverShown = null
        if (animate) {
            overlay.animate().translationY(activity.resources.displayMetrics.heightPixels.toFloat()).setDuration(200)
                .withEndAction { (overlay.parent as? ViewGroup)?.removeView(overlay) }.start()
        } else {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
    }

    fun clearLyricsPage() {
        lyricsOverlay = null
        lyricsList = null
    }

    fun closeLyricsPage(animate: Boolean = false) {
        val overlay = lyricsOverlay ?: return
        clearLyricsPage()
        val host = pages()
        if (host.topKey == "lyrics") host.pop(animate = animate) else (overlay.parent as? ViewGroup)?.removeView(overlay)
    }

    fun showNowPlaying() {
        if (currentMusic() == null) return
        closeNowPlaying()
        val screen = activity.resources.displayMetrics
        val green = 0xff3DDC84.toInt()
        val overlay = FrameLayout(activity).apply {
            background = MusicBackdrop(currentPalette)
            isClickable = true
            elevation = widgets.dp(16).toFloat()
        }
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(widgets.dp(8), widgets.dp(8), widgets.dp(8), 0)
        }
        top.addView(
            widgets.iconOnly(R.drawable.ic_back) { closeNowPlaying(animate = true) }.apply {
                rotation = -90f
                contentDescription = "Minimizar o player"
            },
            LinearLayout.LayoutParams(widgets.dp(48), widgets.dp(48))
        )
        top.addView(
            widgets.label("TOCANDO AGORA", 11f, true).apply {
                gravity = Gravity.CENTER
                setTextColor(0xffbdbdbd.toInt())
                letterSpacing = 0.12f
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        top.addView(Space(activity), LinearLayout.LayoutParams(widgets.dp(48), widgets.dp(48)))
        column.addView(top)

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(widgets.dp(24), widgets.dp(8), widgets.dp(24), widgets.dp(8))
        }
        val side = minOf(screen.widthPixels - widgets.dp(48), (screen.heightPixels * 0.42f).toInt(), widgets.dp(380))
        val cover = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = widgets.rounded(0xff202020.toInt(), 20f)
            clipToOutline = true
            elevation = widgets.dp(8).toFloat()
            isClickable = true
            setOnClickListener { showLyricsPage() }
        }
        content.addView(
            cover,
            LinearLayout.LayoutParams(side, side).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = widgets.dp(8)
                bottomMargin = widgets.dp(22)
            }
        )
        val title = widgets.label("", 22f, true).apply { maxLines = 2 }
        val artist = widgets.muted("", 14f)
        val status = widgets.label("", 12f).apply { setTextColor(green); setPadding(0, widgets.dp(8), 0, 0) }
        val information = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        information.addView(title); information.addView(artist); information.addView(status)
        content.addView(information)

        val previewRow = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, widgets.dp(14), 0, 0)
            isClickable = true
            setOnClickListener { showLyricsPage() }
        }
        val preview = widgets.label("", 15f, true).apply { maxLines = 4; setTextColor(green) }
        previewRow.addView(preview, LinearLayout.LayoutParams(0, -2, 1f))
        val lyricsChip = TextView(activity).apply {
            text = "Letra"
            textSize = 12f
            setTextColor(0xccffffff.toInt())
            gravity = Gravity.CENTER
            setPadding(widgets.dp(14), widgets.dp(7), widgets.dp(14), widgets.dp(7))
            isClickable = true
            isFocusable = true
            background = GradientDrawable().apply {
                cornerRadius = widgets.dp(16).toFloat()
                setColor(0x14ffffff)
                setStroke(widgets.dp(1), 0x33ffffff)
            }
            setOnClickListener { showLyricsPage() }
        }
        previewRow.addView(lyricsChip, LinearLayout.LayoutParams(-2, -2).apply { leftMargin = widgets.dp(12) })
        information.addView(previewRow)
        content.gravity=Gravity.CENTER_VERTICAL
        val body = ScrollView(activity).apply { addView(content); isVerticalScrollBarEnabled = false; isFillViewport=true }
        column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(widgets.dp(24), widgets.dp(8), widgets.dp(24), widgets.dp(20))
        }
        val seek = SeekBar(activity).apply {
            progressTintList = ColorStateList.valueOf(green)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) nowTime?.text = clock(progress * 1000L)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) { musicSeeking = true }
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    musicPlayer()?.seekTo((seekBar?.progress ?: 0) * 1000L)
                    musicSeeking = false
                }
            })
        }
        val time = widgets.muted("0:00 / 0:00", 12f).apply { gravity = Gravity.CENTER }
        controls.addView(seek); controls.addView(time)

        val buttons = LinearLayout(activity).apply { gravity = Gravity.CENTER; setPadding(0, widgets.dp(6), 0, 0) }
        fun transport(icon: Int, description: String, click: () -> Unit) = ImageView(activity).apply {
            setImageResource(icon)
            setColorFilter(Color.WHITE)
            contentDescription = description
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(widgets.dp(12), widgets.dp(12), widgets.dp(12), widgets.dp(12))
            background = widgets.ripple(0x00000000, 24f)
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
        val shuffle = transport(R.drawable.ic_shuffle, "Aleatório") {
            queuePolicy.setShuffle(!queuePolicy.shuffle, currentMusic()?.uri)
            onRequestRefreshUi()
        }
        val repeatButton = transport(R.drawable.ic_repeat, "Repetir: desligado") {
            queuePolicy.cycleRepeat()
            onRequestRefreshUi()
        }
        val badge = TextView(activity).apply {
            text = "1"
            textSize = 9f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(green) }
            visibility = View.GONE
        }
        val repeatBox = FrameLayout(activity).apply {
            addView(repeatButton, FrameLayout.LayoutParams(-1, -1))
            addView(badge, FrameLayout.LayoutParams(widgets.dp(14), widgets.dp(14), Gravity.TOP or Gravity.END).apply {
                topMargin = widgets.dp(6); rightMargin = widgets.dp(5)
            })
        }
        fun gap() = Space(activity)
        buttons.addView(shuffle, LinearLayout.LayoutParams(widgets.dp(48), widgets.dp(48)))
        buttons.addView(gap(), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.addView(transport(R.drawable.ic_prev, "Anterior") { onPrevious() }, LinearLayout.LayoutParams(widgets.dp(56), widgets.dp(56)))
        buttons.addView(gap(), LinearLayout.LayoutParams(0, 1, 1f))
        val play = ImageView(activity).apply {
            setImageResource(R.drawable.ic_play)
            setColorFilter(Color.BLACK)
            contentDescription = "Tocar ou pausar"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(widgets.dp(20), widgets.dp(20), widgets.dp(20), widgets.dp(20))
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
            isClickable = true
            isFocusable = true
            setOnClickListener { onTogglePlay() }
        }
        buttons.addView(play, LinearLayout.LayoutParams(widgets.dp(64), widgets.dp(64)))
        buttons.addView(gap(), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.addView(transport(R.drawable.ic_next, "Próxima") { onStep(1) }, LinearLayout.LayoutParams(widgets.dp(56), widgets.dp(56)))
        buttons.addView(gap(), LinearLayout.LayoutParams(0, 1, 1f))
        buttons.addView(repeatBox, LinearLayout.LayoutParams(widgets.dp(48), widgets.dp(48)))
        controls.addView(buttons)

        if (activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            content.removeView(cover); content.removeView(information); body.removeView(content); column.removeView(body)
            val landscape = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(widgets.dp(24), 0, widgets.dp(24), 0)
            }
            val artworkSide = minOf(screen.heightPixels - widgets.dp(100), screen.widthPixels / 3).coerceAtLeast(widgets.dp(100))
            landscape.addView(cover, LinearLayout.LayoutParams(artworkSide, artworkSide).apply { rightMargin = widgets.dp(16) })
            val right = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
            right.addView(information); right.addView(controls)
            landscape.addView(ScrollView(activity).apply { addView(right); isFillViewport = true }, LinearLayout.LayoutParams(0, -1, 1f))
            column.addView(landscape, LinearLayout.LayoutParams(-1, 0, 1f))
        } else {
            column.addView(controls)
        }

        overlay.addView(column, FrameLayout.LayoutParams(-1, -1))
        rootFrame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        overlay.translationY = screen.heightPixels.toFloat()
        overlay.animate().translationY(0f).setDuration(240).start()
        nowOverlay = overlay; nowSeek = seek; nowTime = time; nowPlay = play; nowStatus = status; nowPreview = preview
        nowShuffle = shuffle; nowRepeat = repeatButton; nowRepeatBadge = badge
        nowTitle = title; nowArtist = artist; nowCover = cover; nowCoverShown = null
        onRequestRefreshUi()
    }

    fun showLyricsPage(animate: Boolean = true) {
        if (currentMusic() == null || nowOverlay == null || lyricsOpen) return
        val overlay = LyricPageFrame(activity) { closeLyricsPage(animate = true) }.apply {
            background = MusicBackdrop(currentPalette)
            isClickable = true
            elevation = widgets.dp(24).toFloat()
        }
        val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(activity).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(widgets.dp(8), widgets.dp(8), widgets.dp(8), 0)
        }
        top.addView(
            widgets.iconOnly(R.drawable.ic_back) { closeLyricsPage(animate = true) }.apply {
                contentDescription = "Voltar ao player"
            },
            LinearLayout.LayoutParams(widgets.dp(48), widgets.dp(48))
        )
        top.addView(
            widgets.label("LETRA", 11f, true).apply {
                gravity = Gravity.CENTER
                setTextColor(0xffbdbdbd.toInt())
                letterSpacing = 0.12f
            },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        top.addView(Space(activity), LinearLayout.LayoutParams(widgets.dp(48), widgets.dp(48)))
        column.addView(top)
        val list = LyricsListView(activity, { at -> musicPlayer()?.seekTo(at) }) {
            activity.startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                },
                317
            )
        }
        column.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        overlay.addView(column, FrameLayout.LayoutParams(-1, -1))
        overlay.scroll = list
        pages().open("lyrics", overlay, activeTab(), animate = animate) { clearLyricsPage(); true }
        lyricsOverlay = overlay
        lyricsList = list
        onRequestRefreshUi()
    }
}

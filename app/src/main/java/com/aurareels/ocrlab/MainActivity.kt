package com.aurareels.ocrlab

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.Cue as SubCue
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import kotlin.math.max
import kotlin.math.min

/**
 * Orchestrator Activity for Aura OCR/ASR Lab.
 * Delegates dark-theme widgets, Live Channels + Playlists, Settings/Models/Vault/History sheets,
 * and the Music Now-Playing/Lyrics UI to [UiWidgets], [ChannelBrowserController],
 * [SubtitleSettingsSheet], and [MusicPlayerController].
 */
class MainActivity : Activity() {
    companion object {
        private const val EXPORT_REQUEST = 310
        private const val MUSIC_LEAD_MS = 10_000L
        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    }
    private enum class LabMode { OCR, ASR }
    private val ui = Handler(Looper.getMainLooper())
    private val widgets by lazy { UiWidgets(this) }
    private lateinit var settingsSheet: SubtitleSettingsSheet
    private lateinit var channelBrowser: ChannelBrowserController
    private lateinit var musicController: MusicPlayerController

    private lateinit var url: EditText
    private lateinit var referer: EditText
    private lateinit var status: TextView
    private lateinit var stats: TextView
    private lateinit var caption: TextView
    private lateinit var originalLine: TextView
    private lateinit var scoutPreview: TextureView
    private lateinit var translatedSubtitleView: androidx.media3.ui.SubtitleView
    private lateinit var mainView: PlayerView
    private lateinit var videoBox: FrameLayout
    private lateinit var rootFrame: FrameLayout
    private lateinit var mainScroll: ScrollView
    private lateinit var debugLabel: TextView
    private lateinit var debugButton: Button
    private lateinit var debugPage: LinearLayout
    private lateinit var debugBody: LinearLayout
    private lateinit var debugTitle: TextView
    private var debugOpen = false
    private var debugOrigin = 0
    @Volatile private var debugSession = 0
    private var debugDirty = false
    private var debugRenderedAt = 0L
    private var pendingLiveSeekAt = 0L
    private val debugEvents = java.util.ArrayDeque<String>()
    private val debugLogWorker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "aura-debug-log") }
    private val debugLogFile by lazy { java.io.File(cacheDir, "debug_current_session.log") }
    private lateinit var channelThumbnailer: ChannelThumbnailer
    private lateinit var horizontalFillButton: ImageView
    private lateinit var fillButton: ImageView
    private var maskView: SubtitleMaskView? = null
    private var sheet: View? = null
    private var sheetOnClose: (() -> Unit)? = null
    private lateinit var prefs: AppPrefs
    private lateinit var history: LinkHistory
    private var fullscreen = false
    private var videoParent: ViewGroup? = null
    private var videoIndex = 0
    private var videoParams: ViewGroup.LayoutParams? = null
    private var shownKey = ""
    private var afterSeek = false
    private lateinit var delaySeek: SeekBar
    private lateinit var topSeek: SeekBar
    private lateinit var bottomSeek: SeekBar
    private lateinit var ocrPreviewHost: LinearLayout
    private lateinit var ocrSection: LinearLayout
    private lateinit var outputSpinner: Spinner
    private lateinit var sourceSpinner: Spinner
    private lateinit var sourceLabel: TextView
    private val sourceCodes: List<String?> = listOf(null) + LanguageCatalog.entries.map { it.code }
    private var lastSourcePos = 0
    private var manualSourceOwner: String? = null
    private var chooserShown = false
    private lateinit var modeIndicator: TextView
    private lateinit var pipeline: OcrPipeline
    private lateinit var models: ModelRepository
    private lateinit var packages: SubtitlePackageStore
    private lateinit var pages: ContentPageHost
    private var packageId: String? = null
    private var packageLanguage = "und"
    private var packageConsumed = false
    private var packageSavedAt = 0L
    private var packageCoverage = emptyList<AudioInterval>()
    private var packageDone = false
    private var packageOpenRequest = 0
    private var availablePackages = emptySet<String>()
    private var availableOnlinePackages = emptyMap<String, String>()
    private lateinit var onlineSubtitleAction: Button
    private var episodeReload: (() -> Unit)? = null
    @Volatile private var closing = false
    private var editorTranslator: SubtitlePackageTranslator? = null
    private var editor: SubtitleEditorPage? = null
    private var editorId: String? = null
    private var draftSrt: String? = null
    private var manualFill = false
    private lateinit var timeline: SubtitleTimeline
    private lateinit var translations: GoogleTranslateBridge
    private lateinit var speech: SpeechBridge
    private var mode = LabMode.ASR
    private var mainPlayer: ExoPlayer? = null
    private var scoutPlayer: ExoPlayer? = null
    private var started = false
    private var mainStarted = false
    private var tickerActive = false
    private var tickScheduled = false
    private var sampleMode = false
    private var videoRatio = 16f / 9f
    private var bufferMs = 6000L
    private var exportMode = LabMode.ASR
    private var currentUrl = ""
    private var currentHeaders: Map<String, String> = emptyMap()
    private var startupRecovery = false
    private var startupTranslationLogged = false
    private var probeStartedElapsed = 0L
    private var probeStartedAt = 0L
    private var autoPaused = false
    private var lastAutoCmdAt = 0L
    private var inBackground = false
    private lateinit var asrButton: Button
    private lateinit var ocrButton: Button
    private lateinit var preparation: TextView
    private val pacer = AsrPacer()
    private var stalls = 0
    private var stallStarted = 0L
    private var stallTotal = 0L
    private var stallMax = 0L
    private val stallReasons = HashMap<String, Int>()
    private var pendingModelImport: String? = null
    private var noSubtitleChoice = false

    private lateinit var localMedia: LocalMedia
    private lateinit var localDecoder: LocalAudioDecoder
    private lateinit var localPanel: LinearLayout
    private lateinit var onlinePanel: LinearLayout
    private lateinit var channelPanel: LinearLayout
    private lateinit var channelCatalog: ChannelCatalog
    private var activeTab = 0
    private var activeChannelId: Int? = null
    private var scoutTap: PcmTap? = null
    private var scoutLive: Pair<Long, Long>? = null
    private var mainWindowStart: Long = C.TIME_UNSET
    /** Live HLS: wall-clock epoch (ms) that equals ASR clock 0. All live clocks are epoch-based so they survive the sliding window. */
    private var liveEpoch0: Long = C.TIME_UNSET
    /** Shared monotonic reference for channels without EXT-X-PROGRAM-DATE-TIME. */
    private var liveFallbackOriginElapsedMs = 0L
    /** Period time (ms) mapped to LiveSubtitleClock.FALLBACK_BASE_MS when no PROGRAM-DATE-TIME exists. */
    private var liveFallbackPeriodOrigin = Long.MIN_VALUE
    private var scoutPlayingSince = 0L
    private var lastSubtitleRenderLogAt = 0L
    private var lastSubtitleRenderKey = ""
    private var liveSeekGuardUntil = 0L
    private var pendingLiveStart: Long? = null
    private var scoutRecoveryAt = 0L
    /** True until the live scout has been moved to the live edge (so ASR leads the visible player). */
    private var scoutEdgePending = true
    private var resumeVideoOnStart = false
    private lateinit var menuControls: LinearLayout
    private lateinit var tabs: LinearLayout
    private lateinit var preparationBox: LinearLayout
    private lateinit var restartBox: LinearLayout
    private lateinit var nextEpisode: LinearLayout
    private var queueSheetOpen = false
    private var localTab = true
    private var currentLocal: LocalVideo? = null
    private var localEntries: List<LocalVideo> = emptyList()
    private var localFilterQuery = ""
    private val episodeQueue = ArrayList<LocalVideo>()
    private var startPosition = 0L
    private lateinit var subtitleCache: LocalSubtitleCache
    private var cachedLocal = false
    private var cachedLanguage: String? = null
    private var cachedCount = -1
    private var cacheSavedAt = 0L
    private var localOpenRequest = 0

    // Músicas (aba 4) e playlists Online
    private lateinit var musicPanel: LinearLayout
    private lateinit var musicMedia: LocalMedia
    private lateinit var lyricsCache: LocalSubtitleCache
    private lateinit var playlists: PlaylistStore
    private lateinit var playlistGrid: LinearLayout
    private lateinit var playlistPanel: LinearLayout
    private var musicEntries: List<LocalVideo> = emptyList()
    private var musicFilterQuery = ""
    private val musicQueue = ArrayList<LocalVideo>()
    private var musicPlayer: ExoPlayer? = null
    private var currentMusic: LocalVideo? = null
    private var musicRevision = 0
    private var musicBrowserRevision = 0
    // One timeline for everything: ASR cues, imported/embedded lyrics and cached lyrics all live in `timeline`.
    private var musicSynced = false
    private var musicAsr = false
    private var musicAsrAt = 0L
    private var musicPreferLyrics = false
    private var musicSource = ""
    private var musicStatus = ""
    private var musicGate = false
    private var musicGateUntil = 0L
    private var musicLang: String? = null
    private var musicPolicy = "lyrics"
    private var musicTitleText = ""
    private var musicArtistText = ""
    private var musicBitmap: android.graphics.Bitmap? = null
    private var musicCachedCount = -1
    private var musicCacheSavedAt = 0L
    private val queuePolicy = MusicQueuePolicy()
    private var notificationAsked = false
    private var musicLanguageHint: String? = null

    private val onlineQueue = ArrayList<PlaylistItem>()
    private var onlineQueueCurrent = ""
    private fun subtitlePolicy() = "${prefs.noiseLevel}:${prefs.laughterMode}:sessions-v3-timed"
    private var progressSavedAt = 0L
    private var restartUntil = 0L
    private var browserRevision = 0
    private var playbackRevision = 0
    private val streamWorker = java.util.concurrent.Executors.newSingleThreadExecutor {
        Thread(it, "aura-stream-resolver").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val streamCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, ResolvedStream>>()
    private var currentResolved: ResolvedStream? = null
    private val fileWorker = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "aura-local-files") }
    private val thumbnails = java.util.concurrent.ThreadPoolExecutor(
        1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS,
        java.util.concurrent.ArrayBlockingQueue<Runnable>(96),
        { Thread(it, "aura-thumbnails") },
        java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
    )
    private val thumbCache = android.util.LruCache<String, android.graphics.Bitmap>(36)

    private fun ensureTickerScheduled() {
        if (!tickerActive || tickScheduled || isDestroyed) return
        tickScheduled = true
        ui.post(tick)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        models = ModelRepository(applicationContext)
        prefs = AppPrefs(applicationContext)
        history = LinkHistory(applicationContext)
        channelCatalog = ChannelCatalog(applicationContext)
        history.removeChannelEntries(channelCatalog.knownUrls())
        localMedia = LocalMedia(applicationContext)
        musicMedia = LocalMedia(applicationContext, MediaKind.AUDIO)
        lyricsCache = LocalSubtitleCache(java.io.File(filesDir, "music_lyrics"))
        playlists = PlaylistStore(java.io.File(filesDir, "online_playlists.json"))
        subtitleCache = LocalSubtitleCache(java.io.File(filesDir, "local_subtitles"))
        localDecoder = LocalAudioDecoder(applicationContext)
        packages = SubtitlePackageStore(applicationContext)
        timeline = SubtitleTimeline()
        translations = GoogleTranslateBridge(applicationContext) { log(it) }
        speech = SpeechBridge(models, timeline, translations, ::log) { language, msg ->
            val music = musicAsr
            if (!music && (!started || mode != LabMode.ASR)) return@SpeechBridge
            if (language == null) {
                log(msg); showLanguageChooser(msg)
            } else if (music) {
                val item = currentMusic ?: return@SpeechBridge
                val revision = musicRevision
                history.rememberLanguage(item.uri, language); musicLang = language
                speech.begin(language, selectedTarget(language), musicAsrAt) { ready ->
                    if (revision != musicRevision || !musicAsr) return@begin
                    if (!ready) musicAsrFailed(speech.phase) else if (localDecoder.ended) speech.finish()
                }
            } else {
                rememberCurrentLanguage(language)
                if (scoutPlayer?.isCurrentMediaItemLive != true) scoutPlayer?.pause()
                speech.begin(language, selectedTarget(language), startPosition) { ready ->
                    if (ready && started && mode == LabMode.ASR) {
                        if (currentLocal != null && localDecoder.ended) speech.finish()
                        scoutPlayer?.play()
                        log("ASR preparado · iniciando lookahead antes do player principal")
                    } else if (!ready && started) {
                        stopVideo()
                        showMessage("ASR indisponível", "Falha ao carregar o reconhecimento de ${LanguageMap.displayName(language)}.\n${speech.phase}")
                    }
                }
            }
        }
        speech.strictness = prefs.noiseLevel
        speech.laughterMode = prefs.laughterMode
        speech.onUndecided = { msg -> if (asrSessionActive()) showLanguageChooser(msg) }
        settingsSheet = SubtitleSettingsSheet(
            activity = this,
            ui = ui,
            widgets = widgets,
            prefs = prefs,
            models = models,
            translations = translations,
            speech = speech,
            history = history,
            openSheet = { title, maxFraction, onClose, fill -> openSheet(title, maxFraction, onClose, side = false, fill = fill) },
            closeSheet = ::closeSheet,
            showMessage = ::showMessage,
            currentSheet = { sheet },
            isStarted = { started },
            hasMainPlayer = { mainPlayer != null },
            mainPlayerView = { if (::mainView.isInitialized) mainView else null },
            applyDebug = ::applyDebug,
            applySubtitleStyle = ::applySubtitleStyle,
            resetShownCaptionKey = { shownKey = "" },
            showCaption = ::showCaption,
            applyMask = ::applyMask,
            resetLetterboxZoom = { setLetterboxZoom(1f) },
            scrollVideoIntoView = ::scrollVideoIntoView,
            refreshModelsSummary = ::refreshModelsSummary,
            onRequestModelArchiveImport = { pendingModelImport = it },
            onPlayHistoryEntry = { entry ->
                url.setText(entry.url)
                referer.setText(entry.referer)
                selectTab(false)
                startVideo()
            },
            log = ::log
        )
        models.ensureBundledLid { ok, msg -> log(msg); if (!ok) refreshModelsSummary() }
        makeUi()
        refreshPackageButtons()
        pipeline = OcrPipeline(applicationContext, models, ::log)
        tickerActive = true
        ensureTickerScheduled()
    }

    private fun makeUi() {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK); isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        scroll.addView(body)
        mainScroll = scroll
        rootFrame = FrameLayout(this).apply { addView(scroll, FrameLayout.LayoutParams(-1, -1)) }
        setContentView(rootFrame)
        pages = ContentPageHost(rootFrame)
        channelThumbnailer = ChannelThumbnailer(this, rootFrame, UA)

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(iconButton(R.drawable.ic_menu, "MENU") { prefs.menuHintSeen = true; showNavigation() }, LinearLayout.LayoutParams(dp(130), dp(48)))
        header.addView(android.widget.Space(this), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(iconOnly(R.drawable.ic_gear) { settingsSheet.showSettings() }.apply { contentDescription = "Ajustes" }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { rightMargin = dp(6) })
        header.addView(label("AURA LAB", 19f, true).apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            letterSpacing = 0.1f
        }, LinearLayout.LayoutParams(-2, dp(48)))
        body.addView(header)
        body.addView(label("Legendas por áudio e imagem · vídeos, canais e músicas", 12f).apply {
            setTextColor(0xffa0a0a0.toInt()); setPadding(0, dp(6), 0, dp(14))
        })
        tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buildTabs()
        body.addView(tabs, LinearLayout.LayoutParams(-1, -2))
        body.addView(View(this).apply { setBackgroundColor(0xff262626.toInt()) }, LinearLayout.LayoutParams(-1, dp(1)))
        debugButton = button("DEBUG") { openDebug() }
        body.addView(debugButton, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8); bottomMargin = dp(8) })
        buildDebugPage()
        localPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(localPanel)
        onlinePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        body.addView(onlinePanel)

        channelBrowser = ChannelBrowserController(
            activity = this,
            ui = ui,
            widgets = widgets,
            catalog = channelCatalog,
            thumbnailer = channelThumbnailer,
            streamWorker = streamWorker,
            resolveStream = ::resolveStream,
            openSheet = { title, maxFraction, onClose, fill -> openSheet(title, maxFraction, onClose, side = false, fill = fill) },
            closeSheet = ::closeSheet,
            showMessage = ::showMessage,
            confirmAction = ::confirmAction,
            isChannelTabActive = { activeTab == 2 },
            isPlaybackBusy = { started || currentMusic != null },
            isDebugOrBackground = { debugOpen || inBackground },
            onPlayChannel = { channel ->
                changeMode(LabMode.ASR)
                startVideo(channel.url, channelId = channel.id)
            }
        )
        channelPanel = channelBrowser.buildPanel()
        body.addView(channelPanel)

        musicPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        body.addView(musicPanel)
        channelBrowser.refresh()
        channelCatalog.onChange = {
            ui.post { if (::channelBrowser.isInitialized) channelBrowser.onCatalogChanged() }
        }

        menuControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        asrButton = button("ASR · ÁUDIO") { changeMode(LabMode.ASR) }
        ocrButton = button("OCR · IMAGEM") { changeMode(LabMode.OCR) }
        modes.addView(asrButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        modes.addView(ocrButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        menuControls.addView(modes)
        modeIndicator = label("Modo OCR · chinês gravado no vídeo", 12f, true).apply {
            setTextColor(0xff8accc1.toInt()); setPadding(0, dp(10), 0, dp(12))
        }
        menuControls.addView(modeIndicator)
        sourceLabel = label("IDIOMA FALADO NO VÍDEO (ASR)", 11f, true)
        menuControls.addView(sourceLabel)
        sourceSpinner = Spinner(this)
        sourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sourceSpinnerLabels())
        sourceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == lastSourcePos) return
                lastSourcePos = position
                val code = sourceCodes[position]
                manualSourceOwner = if (started) currentUrl else null
                if (started && mode == LabMode.ASR) {
                    if (code != null) applyManualLanguage(code)
                    else { forgetRememberedLanguage(currentUrl, activeChannelId != null); log("Automático vale a partir da próxima reprodução") }
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        menuControls.addView(sourceSpinner, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(9) })
        menuControls.addView(label("IDIOMA DA LEGENDA GERADA", 11f, true))
        outputSpinner = Spinner(this)
        outputSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("Português (pt-BR)"))
        menuControls.addView(outputSpinner, LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(9) })

        val urlHeader = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        urlHeader.addView(sectionLabel("LINK DO VÍDEO · HLS / DASH / MP4").apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        url = input("Cole a URL do vídeo ou manifesto .m3u8", "")
        urlHeader.addView(widgets.urlQuickActionsRow(url))
        onlinePanel.addView(urlHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        onlinePanel.addView(url, LinearLayout.LayoutParams(-1, dp(58)).apply { bottomMargin = dp(10) })
        onlinePanel.addView(sectionLabel("REFERER DO SITE (OPCIONAL)").apply { setPadding(0, 0, 0, dp(6)) })
        referer = input("https://site-do-video.com/", "")
        onlinePanel.addView(referer, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(12) })
        widgets.onImeDone(url) { startVideo() }
        widgets.onImeDone(referer) { startVideo() }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(iconButton(R.drawable.ic_play, "REPRODUZIR") { widgets.hideKeyboard(url); startVideo() }, LinearLayout.LayoutParams(0, dp(48), 2f).apply { rightMargin = dp(8) })
        actions.addView(iconButton(R.drawable.ic_stop, "PARAR") { stopVideo(); log("Reprodução interrompida") }, LinearLayout.LayoutParams(0, dp(48), 1f))
        onlinePanel.addView(actions)
        onlineSubtitleAction = button("Legenda") { availableOnlinePackages[url.text.toString().trim()]?.let { openSubtitlePackage(it, "Legenda") } }.apply { visibility = View.GONE }
        onlinePanel.addView(onlineSubtitleAction, LinearLayout.LayoutParams(-1, dp(48)))
        url.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                onlineSubtitleAction.visibility = if (s.toString().trim() in availableOnlinePackages) View.VISIBLE else View.GONE
            }
        })
        val linkTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        linkTools.addView(button("＋ ADICIONAR À PLAYLIST") { addLinkToPlaylist() }, LinearLayout.LayoutParams(0, dp(48), 2f).apply { rightMargin = dp(8) })
        linkTools.addView(iconButton(R.drawable.ic_history, "HISTÓRICO") { settingsSheet.showHistory(::watched, SubtitleSettingsSheet::shortUrl) }, LinearLayout.LayoutParams(0, dp(48), 1.3f))
        onlinePanel.addView(linkTools, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        videoBox = FrameLayout(this).apply { background = rounded(0xff101010.toInt(), 15f) }
        debugBody.addView(videoBox, LinearLayout.LayoutParams(-1, dp(250)).apply { topMargin = dp(13) })
        playlistGrid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        playlistPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, dp(12), 0, 0) }
        playlistPanel.addView(sectionLabel("PLAYLISTS").apply { setPadding(0, 0, 0, dp(2)) })
        playlistPanel.addView(playlistGrid)
        playlistPanel.addView(button("CRIAR PLAYLIST") { promptPlaylistName(null) }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })
        body.addView(playlistPanel)
        refreshPlaylists()
        mainView = (layoutInflater.inflate(R.layout.player_view, null) as PlayerView).apply {
            setBackgroundColor(Color.BLACK)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            useController = true
            controllerAutoShow = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            setFullscreenButtonClickListener {
                if (fullscreen) exitVideo() else if (started) setFullscreen(true)
            }
        }
        videoBox.addView(mainView, FrameLayout.LayoutParams(-1, -1))
        mainView.subtitleView?.visibility=View.GONE
        translatedSubtitleView = androidx.media3.ui.SubtitleView(this)
        videoBox.addView(translatedSubtitleView, FrameLayout.LayoutParams(-1, -1))
        preparation = label("", 14f, true).apply {
            gravity = Gravity.CENTER; background = rounded(0xcc000000.toInt(), 10f)
            setPadding(dp(14), dp(12), dp(14), dp(12)); visibility = View.GONE
        }
        preparationBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; visibility = View.GONE
            background = rounded(0xcc000000.toInt(), 10f); setPadding(dp(16), dp(10), dp(16), dp(10))
            addView(android.widget.ProgressBar(this@MainActivity).apply {
                isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(0xff3ddc84.toInt())
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            addView(preparation.apply { visibility = View.VISIBLE; background = null })
        }
        videoBox.addView(preparationBox, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        mainView.subtitleView?.setApplyEmbeddedStyles(false)
        (mainView.videoSurfaceView as? TextureView)?.let { tex ->
            (tex.parent as? ViewGroup)?.let { frame ->
                val mask = SubtitleMaskView(this, tex)
                frame.addView(mask, frame.indexOfChild(tex) + 1, ViewGroup.LayoutParams(-1, -1))
                maskView = mask
            }
        }
        applyMask()
        mainView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.setOnClickListener { settingsSheet.showPlayerSettings() }
        mainView.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
            controllerShown = visibility == View.VISIBLE; updateFillButton(); updateChromeArrows()
        })
        applySubtitleStyle()
        caption = label("", 17f, true).apply {
            setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setShadowLayer(4f, 1f, 1f, Color.BLACK)
            setPadding(dp(9), dp(5), dp(9), dp(5))
            background = rounded(0xcb000000.toInt(), 8f)
            visibility = View.GONE
        }
        videoBox.addView(caption, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(14), 0, dp(14), dp(45)) })
        originalLine = label("", 11f).apply {
            setTextColor(0xffe0e0e0.toInt()); gravity = Gravity.CENTER
            setPadding(dp(8), dp(3), dp(8), dp(3)); background = rounded(0xa0000000.toInt(), 5f)
            visibility = View.GONE
        }
        videoBox.addView(originalLine, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(14), dp(8), dp(14), 0) })
        fillButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_fill); setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Preencher tela em vídeo vertical"
            background = rounded(0xaa000000.toInt(), 24f); visibility = View.GONE
            setOnClickListener { prefs.fillVertical = !prefs.fillVertical; applyResize(); updateFillButton() }
        }
        videoBox.addView(fillButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(16), dp(12), 0) })
        horizontalFillButton = ImageView(this).apply {
            setImageResource(R.drawable.ic_fill_horizontal); setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = "Preencher largura; corta topo e base"; isClickable = true; isFocusable = true; visibility = View.GONE
            setOnClickListener { manualFill = !manualFill; letterboxTracker.reset(); setLetterboxZoom(1f); applyResize(); updateFillButton() }
        }
        val settings = mainView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)
        val controls = settings?.parent as? LinearLayout
        if (controls != null) controls.addView(horizontalFillButton, controls.indexOfChild(settings), LinearLayout.LayoutParams(dp(48), dp(48)))
        else videoBox.addView(horizontalFillButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { rightMargin = dp(64); topMargin = dp(12) })

        ocrSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        menuControls.addView(ocrSection)
        ocrSection.addView(label("PRÉVIA DO DECODIFICADOR OCR · lookahead", 10f, true).apply {
            setTextColor(0xffa4a4a4.toInt()); setPadding(0, dp(14), 0, dp(7))
        })
        scoutPreview = TextureView(this).apply { isOpaque = true }
        ocrPreviewHost = LinearLayout(this).apply { addView(scoutPreview, LinearLayout.LayoutParams(dp(220), dp(124))) }
        rootFrame.addView(ocrPreviewHost, 0, FrameLayout.LayoutParams(dp(220), dp(124)))
        ocrSection.addView(label("BUFFER INICIAL / LOOKAHEAD", 11f, true).apply { setPadding(0, dp(16), 0, dp(1)) })
        val bufferLabel = label("6 segundos", 12f)
        delaySeek = SeekBar(this).apply {
            max = 12; progress = 6
            setOnSeekBarChangeListener(seekListener { p ->
                bufferMs = max(2, p).toLong() * 1000
                bufferLabel.text = "${bufferMs / 1000} segundos"
            })
        }
        ocrSection.addView(bufferLabel); ocrSection.addView(delaySeek)
        ocrSection.addView(label("REGIÃO DINÂMICA OCR · 1 a 3 linhas", 11f, true))
        val topLabel = label("Início: 58% da altura", 12f)
        topSeek = SeekBar(this).apply {
            max = 40; progress = 13
            setOnSeekBarChangeListener(seekListener { p ->
                val percent = 45 + p; topLabel.text = "Início: $percent% da altura"
                if (::pipeline.isInitialized) pipeline.topFraction = percent / 100f
            })
        }
        ocrSection.addView(topLabel); ocrSection.addView(topSeek)
        val bottomLabel = label("Fim: 94% da altura", 12f)
        bottomSeek = SeekBar(this).apply {
            max = 14; progress = 9
            setOnSeekBarChangeListener(seekListener { p ->
                val percent = 85 + p; bottomLabel.text = "Fim: $percent% da altura"
                if (::pipeline.isInitialized) pipeline.bottomFraction = percent / 100f
            })
        }
        ocrSection.addView(bottomLabel); ocrSection.addView(bottomSeek)
        val tests = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        tests.addView(button("TESTAR FRAME") { testFrame() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        tests.addView(button("EXPORTAR SRT") { exportSrt() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        menuControls.addView(tests)
        stats = label("Sem modelos instalados. Abra MODELOS.", 12f).apply {
            setTextColor(0xffbdbdbd.toInt()); setPadding(dp(10), dp(10), dp(10), dp(10))
            background = rounded(0xff141414.toInt(), 9f)
        }
        addDebugCard("STATUS", stats)
        debugLabel = label("EVENTOS / DEBUG", 11f, true)
        status = label("", 11f).apply { setTextColor(0xffc8c8c8.toInt()); setTextIsSelectable(true) }
        addDebugCard("EVENTOS / DEBUG", status, debugLabel)
        resetDebug(activeTab)
        applyDebug()
        applyModeUi(mode)
        addEpisodeControls()
        refreshLocal(restore = true)

        musicController = MusicPlayerController(
            activity = this,
            rootFrame = rootFrame,
            widgets = widgets,
            pages = { pages },
            queuePolicy = queuePolicy,
            activeTab = { activeTab },
            currentMusic = { currentMusic },
            musicPlayer = { musicPlayer },
            onTogglePlay = ::toggleMusic,
            onStep = { delta -> musicStep(delta) },
            onPrevious = ::musicPrevious,
            onStopMusic = ::stopMusic,
            onRequestRefreshUi = ::renderMusic
        )
        musicController.installBridgeCallbacks()
        musicController.buildMusicBar()

        if (prefs.lastTab != 0) selectTabIndex(prefs.lastTab, restore = true)
        if (!prefs.menuHintSeen) ui.post { showMenuHint(header) }
    }

    private fun selectTab(local: Boolean) = selectTabIndex(if (local) 0 else 1)

    private val tabNames = listOf("Local", "Online", "Canais", "Músicas")

    private fun buildTabs() {
        tabs.removeAllViews()
        prefs.tabOrder.forEachIndexed { position, id ->
            val view = TextView(this).apply {
                text = tabNames[id]; textSize = 13f; gravity = Gravity.CENTER; tag = id
                minHeight = dp(48); minimumHeight = dp(48)
                setPadding(dp(4), dp(14), dp(4), dp(14)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                contentDescription = tabNames[id]
                setOnClickListener { selectTabIndex(id) }
            }
            reorderable(view,"tab:$id") { from, to -> moveTab(from.toInt(), to.toInt()) }
            tabs.addView(view, LinearLayout.LayoutParams(0, -2, 1f).apply { if (position > 0) leftMargin = dp(6) })
        }
        paintTabs()
    }

    private fun paintTabs() {
        for (i in 0 until tabs.childCount) (tabs.getChildAt(i) as? TextView)?.apply {
            val selected = tag == activeTab
            background = if (selected) rounded(0xff1d2b29.toInt(), 12f).apply { setStroke(dp(1), 0xff8accc1.toInt()) }
            else ripple(0xff202020.toInt(), 12f)
            setTextColor(if (selected) 0xff8accc1.toInt() else 0xffa0a0a0.toInt())
            setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
    }

    private fun moveTab(fromId: Int, toId: Int) {
        val order = prefs.tabOrder.toMutableList()
        val from = order.indexOf(fromId); val to = order.indexOf(toId)
        if (from < 0 || to < 0 || from == to) return
        order.add(to, order.removeAt(from)); prefs.tabOrder = order; buildTabs()
    }

    private fun reorderable(view: View, key: String, onDrop: (String, String) -> Unit) =
        widgets.reorderable(ui, view, key, onDrop)

    private fun selectTabIndex(index: Int, restore: Boolean = false) {
        val previous = activeTab
        if (!restore && previous != index) {
            widgets.hideKeyboard(rootFrame)
            if (previous == 3) stopMusic() else if (started) stopVideo()
            closeSheet()
            closeDebug(stopPlayback = false, animate = false)
            resetDebug(index)
        }
        activeTab = index; prefs.lastTab = index
        if (restore) debugOrigin = index
        if (::channelThumbnailer.isInitialized) channelThumbnailer.setPaused(index != 2)
        localTab = index == 0
        localPanel.visibility = if (index == 0) View.VISIBLE else View.GONE
        onlinePanel.visibility = if (index == 1) View.VISIBLE else View.GONE
        playlistPanel.visibility = if (index == 1) View.VISIBLE else View.GONE
        channelPanel.visibility = if (index == 2) View.VISIBLE else View.GONE
        musicPanel.visibility = if (index == 3) View.VISIBLE else View.GONE
        paintTabs(); updatePlayerChrome()
        if (index == 0 && !restore) refreshLocal()
        if (index == 1) refreshPlaylists()
        if (index == 2 && ::channelBrowser.isInitialized) channelBrowser.refresh()
        if (index == 3) refreshMusic(restore = restore || musicMedia.root.isEmpty() || musicMedia.path.isEmpty())
    }

    private fun updateChannelDiscovery() {
        if (!::channelCatalog.isInitialized) return
        val suspended = inBackground || started || musicPlayer != null
        if (::channelBrowser.isInitialized) channelBrowser.updateDiscoverySuspension(suspended)
        else channelCatalog.setSuspended(suspended)
    }

    private fun showMenuHint(header: View) {
        if (prefs.menuHintSeen || isFinishing) return
        val hint = FrameLayout(this).apply { setBackgroundColor(0xbb000000.toInt()); isClickable = true }
        val button = iconButton(R.drawable.ic_menu, "Vá para as configurações") {
            prefs.menuHintSeen = true; rootFrame.removeView(hint); showNavigation()
        }
        hint.addView(button, FrameLayout.LayoutParams(-2, dp(64), Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(16); topMargin = header.top + dp(8)
        })
        rootFrame.addView(hint, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showNavigation() {
        closeSheet()
        val scrim = FrameLayout(this).apply { setBackgroundColor(0x99000000.toInt()); setOnClickListener { closeSheet() } }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(16))
            setBackgroundColor(0xff141414.toInt()); isClickable = true
        }
        card.addView(button("FECHAR  ×") { closeSheet() })
        card.addView(button("MODELOS PARA BAIXAR") { settingsSheet.showModels() })
        card.addView(button("CONFIGURAR LEGENDA") { settingsSheet.showSubtitleSettings() })
        card.addView(toggle("Modo debug", prefs.debugMode) { prefs.debugMode = it; applyDebug() })
        card.addView(button("IDIOMA DE SAÍDA  ▾") {
            outputSpinner.visibility = if (outputSpinner.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        outputSpinner.visibility = View.GONE
        (menuControls.parent as? ViewGroup)?.removeView(menuControls)
        card.addView(menuControls)
        card.addView(muted("Dica: toque e segure uma aba, playlist ou episódio para mudar a ordem.", 11f).apply { setPadding(0, dp(14), 0, 0) })
        val scroller = ScrollView(this).apply { addView(card); isClickable = true }
        val width = minOf(dp(360), (resources.displayMetrics.widthPixels * 0.9).toInt())
        scrim.addView(scroller, FrameLayout.LayoutParams(width, -1, Gravity.START))
        rootFrame.addView(scrim, FrameLayout.LayoutParams(-1, -1)); sheet = scrim
        scroller.translationX = -width.toFloat(); scroller.animate().translationX(0f).setDuration(220).start()
    }

    private fun refreshLocal(restore: Boolean = false, authorize: Uri? = null) {
        if (authorize != null) localFilterQuery = ""
        val request = ++browserRevision
        localPanel.removeAllViews(); localPanel.addView(muted("Carregando pasta…"))
        fileWorker.execute {
            val result = runCatching {
                if (authorize != null) localMedia.authorize(authorize)
                else if (restore) localMedia.restore()
                localMedia.children()
            }
            ui.post {
                if (isFinishing || isDestroyed || request != browserRevision) return@post
                localEntries = result.getOrDefault(emptyList())
                showLocalFiles(result.exceptionOrNull()?.message)
            }
        }
    }

    private fun selectFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }, 315)
    }

    /**
     * One folder browser for videos and music: permission state, path, instant filter bar, ".." row, subfolders, empty state.
     * Only the file row differs, so each tab passes its own itemView.
     */
    private fun renderFolder(
        panel: LinearLayout,
        media: LocalMedia,
        entries: List<LocalVideo>,
        error: String?,
        permissionText: String,
        emptyText: String,
        help: String?,
        filterQuery: String,
        onFilterChanged: (String) -> Unit,
        authorize: () -> Unit,
        reload: () -> Unit,
        itemView: (LocalVideo) -> View
    ) {
        panel.removeAllViews()
        if (media.root.isEmpty() || error != null) {
            panel.addView(label(if (error == null) permissionText else "Não foi possível acessar a pasta. Autorize novamente.\n$error", 14f).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(40), 0, dp(20))
            })
            panel.addView(button("PERMITIR ACESSO À PASTA") { authorize() }); return
        }
        val pathRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        pathRow.addView(label(media.path.joinToString(" / ") { it.name }, 12f).apply {
            setTextColor(0xffbdbdbd.toInt()); setPadding(dp(12), dp(12), dp(12), dp(12))
            background = ripple(0xff141414.toInt(), 12f, 0xff262626.toInt()); maxLines = 2
            setOnClickListener {
                openSheet("Caminho") { body ->
                    media.path.forEachIndexed { index, item ->
                        body.addView(button(item.name) {
                            closeSheet()
                            onFilterChanged("")
                            fileWorker.execute { runCatching { media.up(index) }; ui.post { reload() } }
                        })
                    }
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (help != null) pathRow.addView(button("?") { showMessage("Ajuda", help) }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        panel.addView(pathRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
        panel.addView(button("ESCOLHER OUTRA PASTA") { authorize() }.apply { textSize = 11f; setTextColor(0xffbdbdbd.toInt()) }, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(8) })
        fun folderCard(glyph: String, name: String, tint: Int, click: () -> Unit) = cardRow(click).apply {
            addView(label(glyph, 18f, true).apply { setTextColor(tint); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(30), -2))
            addView(label(name, 14f).apply { setTextColor(if (tint == 0xffa0a0a0.toInt()) tint else Color.WHITE); maxLines = 2 }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val gap = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        val itemsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun populateList(query: String) {
            itemsContainer.removeAllViews()
            if (media.path.size > 1 && query.isBlank()) itemsContainer.addView(folderCard("‹", "..", 0xffa0a0a0.toInt()) {
                onFilterChanged("")
                fileWorker.execute { runCatching { media.up(media.path.size - 2) }; ui.post { reload() } }
            }, gap)
            val needle = query.trim().lowercase()
            val visible = if (needle.isEmpty()) entries else entries.filter { it.name.lowercase().contains(needle) }
            if (visible.isEmpty()) {
                val msg = if (entries.isEmpty()) emptyText else "Nenhum item corresponde a “${query.trim()}”."
                itemsContainer.addView(muted(msg).apply { setPadding(0, dp(16), 0, dp(16)); gravity = Gravity.CENTER })
                return
            }
            visible.forEach { item ->
                if (item.directory) itemsContainer.addView(folderCard("▸", item.name, 0xff8accc1.toInt()) {
                    onFilterChanged("")
                    fileWorker.execute {
                        val failure = runCatching { media.enter(item) }.exceptionOrNull()
                        ui.post { if (failure != null) showMessage("Pasta", failure.message.orEmpty()) else reload() }
                    }
                }, gap) else itemsContainer.addView(itemView(item), gap)
            }
        }
        if (entries.size >= 6 || filterQuery.isNotEmpty()) {
            panel.addView(
                widgets.filterBar("Filtrar nesta pasta…", filterQuery) { q ->
                    onFilterChanged(q)
                    populateList(q)
                },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }
            )
        }
        panel.addView(itemsContainer)
        populateList(filterQuery)
    }

    private fun showLocalFiles(error: String? = null) = renderFolder(
        localPanel, localMedia, localEntries, error,
        "Dê a permissão à pasta onde seus vídeos se encontrarão",
        "Esta pasta não contém vídeos ou subpastas.",
        "Toque em uma pasta para entrar e em “..” (ou no caminho) para voltar. A navegação fica dentro da pasta autorizada. Toque em um vídeo para continuar de onde parou. A fila do player permite mudar a ordem dos próximos episódios.",
        localFilterQuery, { localFilterQuery = it },
        { selectFolder() }, { refreshLocal() }
    ) { item ->
        val progress = localMedia.progress(item.uri)
        val row = cardRow { openLocal(item) }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; background = rounded(0xff101010.toInt(), 10f)
            clipToOutline = true; outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        }
        row.addView(image, LinearLayout.LayoutParams(dp(84), dp(56)).apply { rightMargin = dp(12) }); thumbnail(item, image)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(label(item.name, 14f).apply { maxLines = 2 })
        if (watched(progress)) column.addView(label("assistido", 11f).apply { setTextColor(0xff8accc1.toInt()) })
        column.addView(progressBar(progress.first, progress.second))
        row.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
        subtitleButton(SubtitlePackageStore.local(item), item.name)?.let { row.addView(it, LinearLayout.LayoutParams(-2, dp(48)).apply { gravity = Gravity.TOP }) }
        row
    }

    private fun watched(progress: Pair<Long, Long>) = progress.second > 0 && progress.first.toDouble() / progress.second >= 0.95

    private fun thumbnail(item: LocalVideo, image: ImageView) {
        val key = "${item.uri}:${item.size}:${item.modified}"; image.tag = key
        thumbCache.get(key)?.let { image.setImageBitmap(it); return }
        thumbnails.execute {
            val retriever = android.media.MediaMetadataRetriever()
            val bitmap = try {
                retriever.setDataSource(this, Uri.parse(item.uri))
                retriever.getScaledFrameAtTime(0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 100)
            } catch (_: Exception) { null } finally { runCatching { retriever.release() } }
            if (bitmap != null) {
                thumbCache.put(key, bitmap)
                ui.post { if (!isDestroyed && image.tag == key) image.setImageBitmap(bitmap) }
            }
        }
    }

    private fun openLocal(item: LocalVideo, preserveQueue: Boolean = false, removeFromQueue: Boolean = false) {
        if (!preserveQueue) {
            val files = localMedia.ordered(localEntries.filter { !it.directory })
            episodeQueue.clear(); episodeQueue.addAll(files.drop(files.indexOfFirst { it.uri == item.uri } + 1))
        }
        if (mode != LabMode.ASR) changeMode(LabMode.ASR)
        val request = ++localOpenRequest
        val language = manualSource(item.uri) ?: history.languageFor(item.uri)
        val policy = subtitlePolicy()
        fileWorker.execute {
            val saved = language?.let { subtitleCache.load(item, it, "pt", policy) }
            ui.post {
                if (isDestroyed || request != localOpenRequest) return@post
                startVideo(item.uri, item, saved)
                if (removeFromQueue) { episodeQueue.remove(item); refreshNextEpisode() }
            }
        }
    }

    /** Decodes a file's audio into the shared ASR. Used by local videos and by music (Canais use the scout player). */
    private fun decodeForAsr(item: LocalVideo, at: Long, alive: () -> Boolean, onFailure: (String) -> Unit) {
        fun finishInput() {
            if (!alive()) return
            if (speech.language == null && speech.backlogMs > 0) { ui.postDelayed({ finishInput() }, 100); return }
            if (speech.language == null && !speech.bypass) showLanguageChooser("O áudio terminou sem confirmar o idioma. Escolha o idioma falado.")
            else speech.finish()
        }
        val audioEpoch = speech.sessionEpoch
        localDecoder.start(Uri.parse(item.uri), at, { pcm, rate, time, current ->
            if (!alive()) false else speech.localPcm(audioEpoch, pcm, rate, time, current)
        }, { ui.post { finishInput() } }, { message -> ui.post { if (alive()) onFailure(message) } }, { message ->
            ui.post { if (alive() && speech.sessionEpoch == audioEpoch) log(message) }
        })
    }

    private fun startLocalAudio(at: Long) {
        val item = currentLocal ?: return
        val revision = playbackRevision
        decodeForAsr(item, at, { revision == playbackRevision && currentLocal?.uri == item.uri && started }) { message ->
            showMessage("Áudio local", message + "\nVocê pode continuar sem legendas.")
            speech.continueWithoutSubtitles(); noSubtitleChoice = true
        }
    }

    /** Cached cues go back into the shared timeline/translation flow (local video and music). */
    private fun restoreCues(language: String, cues: List<SpeechCue>, complete: Boolean = true) {
        translations.configure(language, "pt"); timeline.restore(cues); translations.registerSourceCues(cues, complete = complete)
        cues.forEach { translations.translate(it) }
    }

    private fun snapshotCues(cues: List<SpeechCue>) = cues.map { it.snapshot() }
    private fun asrSessionActive() = (started && mode == LabMode.ASR) || musicAsr

    private fun refreshPackageButtons() {
        fileWorker.execute {
            val ids = runCatching { packages.availableIds() }.getOrDefault(emptySet())
            val links = getSharedPreferences("aura_vod_ids", MODE_PRIVATE).all
                .mapNotNull { (address, value) -> (value as? String)?.let { address to SubtitlePackageStore.online(it, address) } }
                .toMap().filterValues { it in ids }
            ui.post {
                if (!isDestroyed) {
                    availablePackages = ids; availableOnlinePackages = links; episodeReload?.invoke()
                    if (::onlineSubtitleAction.isInitialized) onlineSubtitleAction.visibility = if (url.text.toString().trim() in links) View.VISIBLE else View.GONE
                    if (!started && activeTab == 0) refreshLocal()
                    if (activeTab == 1) refreshPlaylists()
                    if (activeTab == 3) showMusicFiles()
                }
            }
        }
    }

    private fun mediaPackageId(address: String, local: LocalVideo? = null, episodeIdentity: String? = null): String {
        if (local != null) return SubtitlePackageStore.local(local)
        val item = playlists.all().flatMap { it.items }.firstOrNull { it.url == address }
        val registry = getSharedPreferences("aura_vod_ids", MODE_PRIVATE)
        val identity = episodeIdentity ?: item?.id ?: registry.getString(address, null)
            ?: java.util.UUID.randomUUID().toString().also { registry.edit().putString(address, it).apply() }
        return SubtitlePackageStore.online(identity, address)
    }

    private fun checkpointPackage(force: Boolean = false) {
        val id = packageId ?: return
        if (!::speech.isInitialized || (activeChannelId != null && currentMusic == null) || mainPlayer?.isCurrentMediaItemLive == true || (currentMusic != null && !musicSynced)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - packageSavedAt < 5000) return
        packageSavedAt = now
        val language = (if (currentMusic != null) musicLang ?: speech.language else cachedLanguage ?: speech.language) ?: packageLanguage
        if (language != "und") packageLanguage = language
        val snapshot = timeline.all()
        val ranges = AudioCoverage.merge(packageCoverage + speech.coverageSnapshot())
        packageCoverage = ranges
        val duration = (musicPlayer ?: mainPlayer)?.duration?.takeIf { it > 0 } ?: 0L
        val fullCoverage = duration > 0 && AudioCoverage.firstGap(0, ranges) >= duration - 250
        val done = packageDone || (fullCoverage && speech.processedThroughMs >= Long.MAX_VALUE / 8 && (localDecoder.ended || scoutPlayer?.playbackState == Player.STATE_ENDED))
        packageDone = done
        val consumed = packageConsumed; val asr = "sherpa-1.13.8:${speech.engineName}"
        fileWorker.execute {
            runCatching { packages.checkpoint(id, language, asr, snapshotCues(snapshot), ranges, consumed, done) }
                .onFailure { log("Pacote de legenda: ${it.message}") }
            if (editorId == id) {
                val updated = runCatching { packages.load(id) }.getOrNull()
                ui.post { if (editorId == id && updated != null) editor?.merge(updated) }
            }
        }
    }

    private fun openSubtitlePackage(id: String, title: String) {
        if (pages.topKey == "subtitle:$id") return
        fileWorker.execute {
            val pack = runCatching { packages.load(id) }.getOrNull()
            ui.post {
                if (isDestroyed || pages.topKey == "subtitle:$id" || pack == null || !pack.consumed || pack.cues.none { !it.suppressed }) return@post
                val page = SubtitleEditorPage(this, pack, title, { pages.back() }, { edits, timings, done ->
                    fileWorker.execute {
                        val ok = runCatching { packages.saveEdits(id, edits, timings) }.isSuccess
                        ui.post {
                            if (ok) editorTranslator?.applyEdits(edits, timings)
                            if (ok && packageId == id) {
                                timeline.all().forEach { c -> edits.firstOrNull { it.cueId == c.id }?.let { c.applyHuman(it.text, it.deleted) } }
                                timeline.applyTimings(timings)
                            }
                            if (!isDestroyed) { done(ok); if (ok) refreshPackageButtons() }
                        }
                    }
                }, { text ->
                    draftSrt = text
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE); type = "application/x-subrip"; putExtra(Intent.EXTRA_TITLE, "aura_legendas.srt")
                    }, 318)
                }, { cue, text, apply ->
                    openSheet("Editar legenda") { body ->
                        body.addView(muted(cue.source))
                        val field = widgets.textInput("Tradução", text).apply {
                            isSingleLine = false; minLines = 3; maxLines = 8
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                        }
                        body.addView(field, LinearLayout.LayoutParams(-1, -2))
                        body.addView(button("Aplicar") { apply(field.text.toString()); closeSheet() })
                        body.addView(button("Cancelar") { closeSheet() })
                        widgets.focusAndShowKeyboard(field)
                    }
                }, { cue, start, end, onApply -> showTimingSheet(cue, start, end, onApply) }, ::confirmAction)
                editor = page; editorId = id
                editorTranslator?.close(); editorTranslator = null
                if (packageId != id && pack.language != "und" && pack.cues.any { !TranslationCoverage.complete(it, pack.language, "pt") }) {
                    editorTranslator = SubtitlePackageTranslator(applicationContext, packages, fileWorker, pack) { updated ->
                        if (editorId == id) editor?.merge(updated)
                    }
                }
                pages.open("subtitle:$id", page, activeTab) {
                    val close = page.requestClose()
                    if (close) { editorTranslator?.close(); editorTranslator = null; editor = null; editorId = null }
                    close
                }
            }
        }
    }

    private fun showTimingSheet(cue: SpeechCue, start: Long, end: Long, onApply: (Long, Long) -> Unit) {
        openSheet("Tempo da legenda") { body ->
            body.addView(muted(cue.source))
            val startField = input("Início (mm:ss.mmm)", SubtitleTimeText.format(start)).apply { inputType = InputType.TYPE_CLASS_TEXT }
            val endField = input("Fim (mm:ss.mmm)", SubtitleTimeText.format(end)).apply { inputType = InputType.TYPE_CLASS_TEXT }
            val error = muted("").apply { setTextColor(0xffe08a8a.toInt()) }
            fun shift(field: EditText, delta: Long) {
                SubtitleTimeText.parse(field.text.toString())?.let { field.setText(SubtitleTimeText.format((it + delta).coerceAtLeast(0))) }
            }
            fun nudges(title: String, onNudge: (Long) -> Unit) {
                body.addView(muted(title))
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                listOf("−0,5 s" to -500L, "−0,1 s" to -100L, "+0,1 s" to 100L, "+0,5 s" to 500L).forEach { (name, delta) ->
                    row.addView(button(name) { onNudge(delta) }, LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
                }
                body.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            body.addView(muted("Início")); body.addView(startField, LinearLayout.LayoutParams(-1, -2))
            nudges("Ajustar início") { shift(startField, it) }
            body.addView(muted("Fim")); body.addView(endField, LinearLayout.LayoutParams(-1, -2))
            nudges("Ajustar fim") { shift(endField, it) }
            nudges("Mover legenda inteira (início e fim)") { shift(startField, it); shift(endField, it) }
            body.addView(error)
            body.addView(button("Aplicar") {
                val newStart = SubtitleTimeText.parse(startField.text.toString())
                val newEnd = SubtitleTimeText.parse(endField.text.toString())
                val problem = SubtitleTimeText.validate(newStart, newEnd)
                if (problem != null) error.text = problem else { onApply(newStart!!, newEnd!!); closeSheet() }
            })
            body.addView(button("Cancelar") { closeSheet() })
        }
    }

    private fun subtitleButton(id: String, title: String): View? =
        if (id !in availablePackages) null else button("Legenda") { openSubtitlePackage(id, title) }

    private fun saveSubtitleCache(force: Boolean = false) {
        val item = currentLocal ?: return
        val language = cachedLanguage ?: speech.language ?: return
        if (!cachedLocal && (startPosition != 0L || speech.processedThroughMs < Long.MAX_VALUE / 8)) return
        val cues = timeline.all()
        val count = cues.count { it.output != null }
        if (count == cachedCount || (!force && System.currentTimeMillis() - cacheSavedAt < 5000)) return
        cachedCount = count; cacheSavedAt = System.currentTimeMillis()
        val policy = subtitlePolicy()
        val snapshot = cues
        fileWorker.execute { runCatching { subtitleCache.save(item, language, "pt", policy, snapshotCues(snapshot)) }.onFailure { log("Cache de legenda: ${it.message}") } }
    }

    private fun savePlaybackProgress() {
        val player = mainPlayer ?: return
        if (!started) return
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0)
        currentLocal?.let { localMedia.saveProgress(it.uri, position, duration) }
            ?: if (activeChannelId == null) history.progress(currentUrl, position, duration) else Unit
        progressSavedAt = System.currentTimeMillis()
    }

    private lateinit var exitButton: View
    private lateinit var queueButton: View

    private fun updatePlayerChrome() {
        if (::videoBox.isInitialized) videoBox.visibility = if (fullscreen || (debugOpen && started && debugOrigin == activeTab)) View.VISIBLE else View.GONE
        updateChromeArrows()
    }

    private var controllerShown = false
    private fun updateChromeArrows() {
        if (!::exitButton.isInitialized) return
        val chrome = controllerShown || !started || (mode == LabMode.ASR && (!mainStarted || autoPaused))
        exitButton.visibility = if (activeTab <= 2 && chrome) View.VISIBLE else View.GONE
        queueButton.visibility = if (activeTab == 0 && currentLocal != null && chrome) View.VISIBLE else View.GONE
    }

    private fun addEpisodeControls() {
        exitButton = iconOnly(R.drawable.ic_back) { exitVideo() }
        exitButton.contentDescription = "Sair do vídeo"
        videoBox.addView(exitButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START))
        queueButton = iconOnly(R.drawable.ic_queue) { if (currentLocal != null) showEpisodeQueue() }
        queueButton.contentDescription = "Próximos episódios"
        videoBox.addView(queueButton, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { rightMargin = dp(62) })
        updatePlayerChrome()
        restartBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            addView(button("×") { visibility = View.GONE }.apply { contentDescription = "Ocultar reiniciar" }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(button("Reiniciar") { mainPlayer?.seekTo(0); visibility = View.GONE }, LinearLayout.LayoutParams(-2, dp(48)).apply { leftMargin = dp(4) })
        }
        videoBox.addView(restartBox, FrameLayout.LayoutParams(-2, dp(48), Gravity.BOTTOM or Gravity.START).apply { bottomMargin = dp(56); leftMargin = dp(8) })
        nextEpisode = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE; background = rounded(0xdd141414.toInt(), 10f)
            setOnClickListener { playNext() }
        }
        videoBox.addView(nextEpisode, FrameLayout.LayoutParams(dp(180), dp(64), Gravity.BOTTOM or Gravity.END).apply { bottomMargin = dp(56); rightMargin = dp(8) })
    }

    private fun refreshNextEpisode() {
        nextEpisode.removeAllViews()
        val item = episodeQueue.firstOrNull() ?: return
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        nextEpisode.addView(image, LinearLayout.LayoutParams(dp(64), dp(60))); thumbnail(item, image)
        nextEpisode.addView(label("Próximo episódio\n${item.name}", 11f), LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun playNext() {
        if (currentLocal == null) return
        val item = episodeQueue.firstOrNull() ?: return
        closeSheet()
        openLocal(item, preserveQueue = true, removeFromQueue = true)
    }

    private fun showEpisodeQueue() {
        val horizontal = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        openSheet("Próximos episódios", maxFraction = 0.76f, side = horizontal, onClose = { queueSheetOpen = false }) { body ->
            if (!prefs.queueHintSeen) {
                body.addView(muted("Você pode clicar e segurar em um vídeo para mover para cima ou para baixo.", 13f))
                prefs.queueHintSeen = true
            }
            if (episodeQueue.isEmpty()) body.addView(muted("Não há próximos episódios nesta pasta."))
            fun persistOrder() {
                val pending = episodeQueue.map { it.uri }.toSet()
                localMedia.saveQueue(localMedia.ordered(localEntries.filter { !it.directory }).filter { it.uri !in pending } + episodeQueue)
                refreshNextEpisode()
            }
            fun rebuild() { showEpisodeQueue() }
            episodeQueue.toList().forEach { item ->
                val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)); tag = item.uri }
                val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                row.addView(image, LinearLayout.LayoutParams(dp(60), dp(48))); thumbnail(item, image)
                val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                text.addView(label(item.name, 12f)); val progress = localMedia.progress(item.uri)
                text.addView(progressBar(progress.first, progress.second))
                text.setOnClickListener {
                    closeSheet(); openLocal(item, preserveQueue = true, removeFromQueue = true)
                }
                row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                fun startDrag(): Boolean {
                    row.startDragAndDrop(ClipData.newPlainText("episode", item.uri), View.DragShadowBuilder(row), item.uri, 0); return true
                }
                row.setOnLongClickListener { startDrag() }
                row.addView(iconOnly(R.drawable.ic_menu) {}.apply {
                    contentDescription = "Segure para mover ${item.name}"; setPadding(dp(14), dp(12), dp(14), dp(12))
                    setOnLongClickListener { startDrag() }
                })
                row.setOnDragListener { _, event ->
                    when (event.action) {
                        android.view.DragEvent.ACTION_DRAG_STARTED -> event.localState is String
                        android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                            val scroller = body.parent as? ScrollView
                            if (scroller != null) {
                                val y = event.y + row.top - scroller.scrollY
                                if (y < dp(60)) scroller.smoothScrollBy(0, -dp(20))
                                else if (y > scroller.height - dp(60)) scroller.smoothScrollBy(0, dp(20))
                            }
                            true
                        }
                        android.view.DragEvent.ACTION_DRAG_ENTERED -> { row.alpha = 0.5f; true }
                        android.view.DragEvent.ACTION_DRAG_EXITED -> { row.alpha = 1f; true }
                        android.view.DragEvent.ACTION_DROP -> {
                            val from = episodeQueue.indexOfFirst { it.uri == event.localState }
                            val to = episodeQueue.indexOfFirst { it.uri == item.uri }
                            if (from >= 0 && to >= 0 && from != to) { episodeQueue.add(to, episodeQueue.removeAt(from)); persistOrder(); ui.post { rebuild() } }
                            row.alpha = 1f; true
                        }
                        android.view.DragEvent.ACTION_DRAG_ENDED -> { row.alpha = 1f; true }
                        else -> true
                    }
                }
                body.addView(row)
            }
        }
        queueSheetOpen = true
    }

    private fun changeMode(next: LabMode) {
        if (mode == next) return
        stopVideo(); sampleMode = false
        mode = next
        applyModeUi(next)
        log("Modo alterado para ${next.name}")
    }

    private fun applyModeUi(next: LabMode) {
        modeIndicator.text = if (next == LabMode.OCR) "Modo OCR · chinês gravado no vídeo" else
            "Modo ASR · preparando legendas antes de iniciar o vídeo"
        sourceLabel.visibility = if (next == LabMode.ASR) View.VISIBLE else View.GONE
        sourceSpinner.visibility = if (next == LabMode.ASR) View.VISIBLE else View.GONE
        ocrPreviewHost.visibility = if (next == LabMode.OCR) View.VISIBLE else View.GONE
        ocrSection.visibility = if (next == LabMode.OCR) View.VISIBLE else View.GONE
        for ((b, selected) in listOf(asrButton to (next == LabMode.ASR), ocrButton to (next == LabMode.OCR))) {
            val dot = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setSize(dp(9), dp(9))
                setColor(if (selected) 0xff3ddc84.toInt() else Color.TRANSPARENT)
                setStroke(dp(1), if (selected) 0xff3ddc84.toInt() else 0xff555555.toInt())
                setBounds(0, 0, dp(9), dp(9))
            }
            b.setCompoundDrawables(dot, null, null, null); b.compoundDrawablePadding = dp(5)
            b.contentDescription = "${b.text}, ${if (selected) "selecionado" else "não selecionado"}"
        }
    }

    // ------------------------------------------------------------------ themed panels (no system dialogs)

    private fun confirmAction(message: String, negative: String, positive: String, confirmed: () -> Unit) {
        openSheet(message) { body ->
            body.addView(button(negative) { closeSheet() }, LinearLayout.LayoutParams(-1, dp(48)))
            body.addView(button(positive) { closeSheet(); confirmed() }, LinearLayout.LayoutParams(-1, dp(48)))
        }
    }

    private fun openSheet(title: String, maxFraction: Float = 0.66f, onClose: () -> Unit = {}, side: Boolean = false, fill: (LinearLayout) -> Unit): View {
        closeSheet()
        val host: ViewGroup = if (fullscreen) window.decorView as ViewGroup else rootFrame
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(0x99000000.toInt()); isClickable = true
            setOnClickListener { closeSheet() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xff141414.toInt(), 18f)
            setPadding(dp(16), dp(6), dp(8), dp(12)); isClickable = true
        }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(title, 16f, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(iconOnly(R.drawable.ic_close) { closeSheet() }.apply { contentDescription = "Fechar" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        card.addView(head)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(8), 0) }
        fill(body)
        val maxHeight = (resources.displayMetrics.heightPixels * maxFraction).toInt()
        val scroller = object : ScrollView(this@MainActivity) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) =
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        }
        scroller.addView(body)
        card.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        scrim.addView(card, FrameLayout.LayoutParams(
            if (side) minOf(dp(420), resources.displayMetrics.widthPixels * 2 / 3) else -1,
            if (side) -1 else -2,
            if (side) Gravity.END else Gravity.BOTTOM
        ).apply { setMargins(dp(10), dp(10), dp(10), dp(14)) })
        if (side) { card.translationX = dp(420).toFloat(); card.animate().translationX(0f).setDuration(220).start() }
        else { card.translationY = dp(160).toFloat(); card.animate().translationY(0f).setDuration(220).start() }
        scrim.elevation = dp(24).toFloat()
        host.addView(scrim, ViewGroup.LayoutParams(-1, -1))
        sheet = scrim; sheetOnClose = onClose
        return scrim
    }

    private fun closeSheet() {
        val open = sheet ?: return
        sheet = null
        widgets.hideKeyboard(open)
        (open.parent as? ViewGroup)?.removeView(open)
        val callback = sheetOnClose; sheetOnClose = null
        callback?.invoke()
    }

    private fun showMessage(title: String, message: String) {
        openSheet(title) { body ->
            body.addView(label(message, 13f).apply { setPadding(0, dp(6), 0, dp(14)) })
            body.addView(button("OK") { closeSheet() }, LinearLayout.LayoutParams(-1, dp(48)))
        }
    }

    private fun scrollVideoIntoView() {
        if (fullscreen || !::mainScroll.isInitialized) return
        mainScroll.post { mainScroll.smoothScrollTo(0, (videoBox.top - dp(12)).coerceAtLeast(0)) }
    }

    private fun selectedTarget(original: String): String = "pt"

    private fun manualSource(mediaUrl: String): String? {
        if (manualSourceOwner != null && manualSourceOwner != mediaUrl) {
            lastSourcePos = 0; sourceSpinner.setSelection(0); manualSourceOwner = null
        }
        return sourceCodes[sourceSpinner.selectedItemPosition]
    }

    private fun rememberedVideoLanguage(mediaUrl: String, channelId: Int?): String? {
        val code = if (channelId != null) getSharedPreferences("aura_channel_languages", MODE_PRIVATE).getString(mediaUrl, null) else history.languageFor(mediaUrl)
        return code?.takeIf { it != "pt" && LanguageCatalog.get(it) != null && LanguageMap.pickInstalled(it, models.asrLanguages()) != null }
    }

    private fun forgetRememberedLanguage(mediaUrl: String, channel: Boolean) {
        if (channel) getSharedPreferences("aura_channel_languages", MODE_PRIVATE).edit().remove(mediaUrl).apply() else history.forgetLanguage(mediaUrl)
    }

    private fun rememberCurrentLanguage(language: String) {
        if (activeChannelId != null) getSharedPreferences("aura_channel_languages", MODE_PRIVATE).edit().putString(currentUrl, language).apply()
        else history.rememberLanguage(currentUrl, language)
    }

    private fun resolveStream(address: String, headers: Map<String, String>): ResolvedStream {
        val key = address + "|" + headers.toSortedMap().toString()
        streamCache[key]?.takeIf { android.os.SystemClock.elapsedRealtime() - it.first < 300_000 }?.let { return it.second }
        val transport = ChannelHttpTransport(headers, allowHttp = true)
        try {
            val result = StreamResolver { target, limit, offset -> transport.fetch(target, limit, offset) }.resolve(address)
            if (streamCache.size > 64) streamCache.clear()
            streamCache[key] = android.os.SystemClock.elapsedRealtime() to result
            return result
        } finally { transport.cancel() }
    }

    private fun startVideo(mediaUrl: String = url.text.toString().trim(), local: LocalVideo? = null, saved: LocalSubtitleCache.Entry? = null, channelId: Int? = null, episodeIdentity: String? = null) {
        val request = ++packageOpenRequest
        val channel = channelId?.let { id -> channelCatalog.channels().firstOrNull { it.id == id } }
        val headers = channel?.headers ?: referer.text.toString().trim().takeIf { it.isNotBlank() }?.let { mapOf("Referer" to it) } ?: emptyMap()
        if (local == null && !StreamResolver.httpUrl(mediaUrl)) { showMessage("Stream", "Informe uma URL HTTP(S) válida."); return }
        streamWorker.execute {
            val result = if (local == null) runCatching { resolveStream(mediaUrl, headers) } else null
            if (result?.isFailure == true || result?.getOrNull()?.kind in setOf(StreamKind.UNSUPPORTED, StreamKind.CATALOG)) {
                ui.post {
                    if (!isDestroyed && request == packageOpenRequest) {
                        showMessage("Stream", result?.getOrNull()?.issue ?: result?.exceptionOrNull()?.message ?: "Importe listas M3U pela aba Canais.")
                    }
                }
                return@execute
            }
            if (closing) return@execute
            fileWorker.execute {
                if (closing) return@execute
                val id = if (channelId == null) mediaPackageId(mediaUrl, local, episodeIdentity) else null
                val pack = id?.let { runCatching { packages.load(it) }.getOrNull() }
                ui.post {
                    if (isDestroyed || request != packageOpenRequest) return@post
                    startVideoReady(mediaUrl, local, saved, channelId, pack, id, result?.getOrNull())
                }
            }
        }
    }

    private fun startVideoReady(mediaUrl: String, local: LocalVideo?, legacy: LocalSubtitleCache.Entry?, channelId: Int?, pack: SubtitlePackageStore.Package?, id: String?, resolved: ResolvedStream?) {
        val saved = pack?.takeIf { it.transcriptDone }?.let { LocalSubtitleCache.Entry(it.language, it.cues) } ?: legacy
        if (local == null && !mediaUrl.startsWith("https://", true) && !mediaUrl.startsWith("http://", true)) {
            log("Informe URL HTTP(S) válida do HLS"); return
        }
        val selectedSource = manualSource(mediaUrl)
        val remembered = if (mode == LabMode.ASR && selectedSource == null && saved == null && pack?.language?.takeIf { it != "und" } == null)
            rememberedVideoLanguage(mediaUrl, channelId) else null
        stopMusic()
        ui.post { updatePlayerChrome() }
        if (mode == LabMode.ASR && saved == null) {
            val manual = selectedSource ?: pack?.language?.takeIf { it != "und" } ?: remembered
            if (!models.anyAsrInstalled()) {
                showMessage("Modelos necessários", "Baixe o VAD + SenseVoice (japonês/chinês/coreano/inglês) e/ou Whisper (árabe, turco e outros) em MODELOS. Os pacotes Vosk continuam como alternativa."); return
            }
            if (manual != null) {
                if (models.asrBackendFor(manual) == null && LanguageMap.pickInstalled(manual, models.asrLanguages()) == null) {
                    showMessage("Modelo ausente", "Nenhum modelo de reconhecimento de ${LanguageMap.displayName(manual)} instalado. Baixe VAD + SenseVoice/Whisper em MODELOS ou use o modo Automático."); return
                }
            } else if (!models.installed("lid")) {
                val msg = if (models.bundledLidAvailable()) "O detector embutido ainda está sendo preparado. Tente de novo em instantes."
                else "O detector de idioma não está neste APK. Baixe-o em MODELOS ou escolha o idioma manualmente."
                showMessage("Detector de idioma", msg); return
            }
        } else if (mode == LabMode.OCR && !models.installed("ocr")) {
            showMessage("OCR não instalado", "Baixe PP-OCRv6 Small em MODELOS."); return
        }
        stopVideo()
        activeChannelId = channelId; currentResolved = resolved
        packageId = id; packageLanguage = pack?.language ?: "und"; packageConsumed = pack?.consumed ?: false
        packageCoverage = pack?.coverage.orEmpty(); packageDone = pack?.transcriptDone ?: false; packageSavedAt = 0L
        manualFill = false
        if (selectedSource != null) manualSourceOwner = mediaUrl
        channelThumbnailer.setPaused(true)
        resetDebug(activeTab)
        currentLocal = local; updatePlayerChrome(); cachedLocal = saved != null; cachedLanguage = saved?.language; cachedCount = -1
        val progress = if (local != null) localMedia.progress(local.uri) else if (activeChannelId != null) 0L to 0L else history.all().firstOrNull { it.url == mediaUrl }?.let { it.positionMs to it.durationMs } ?: (0L to 0L)
        startPosition = PlaybackStartup.resumePosition(progress.first, progress.second)
        scoutLive = null; mainWindowStart = C.TIME_UNSET; scoutTap = null; liveEpoch0 = C.TIME_UNSET; liveFallbackOriginElapsedMs = 0L; liveFallbackPeriodOrigin = Long.MIN_VALUE; pendingLiveStart = null; scoutRecoveryAt = 0L
        lastSubtitleRenderLogAt = 0L; lastSubtitleRenderKey = ""
        timeline.reset()
        pipeline.reset()
        speech.strictness = prefs.noiseLevel
        speech.musicMode = false; speech.languageHint = null
        speech.laughterMode = prefs.laughterMode
        if (mode == LabMode.OCR) pipeline.prepareModel() else if (saved == null) { speech.startProbe(); speech.preserveTimeline = true }
        if (saved != null) { packageDone = true; restoreCues(saved.language, saved.cues) }
        else if (pack != null) {
            timeline.restore(pack.cues); speech.preserveTimeline = true; speech.restoredCoverage = pack.coverage
        }
        sampleMode = false; started = true; mainStarted = false; exportMode = mode; afterSeek = false
        ensureTickerScheduled()
        updateChannelDiscovery()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (local == null && activeChannelId == null) history.add(mediaUrl, referer.text.toString())
        val requestHeaders = mutableMapOf("Accept" to "*/*")
        val ref = if (local == null && activeChannelId == null) referer.text.toString().trim() else ""
        if (ref.isNotBlank()) {
            requestHeaders["Referer"] = ref
            val uri = Uri.parse(ref)
            if (uri.scheme in listOf("http", "https") && uri.authority != null) requestHeaders["Origin"] = "${uri.scheme}://${uri.authority}"
        }
        channelId?.let { cid -> channelCatalog.channels().firstOrNull { it.id == cid }?.headers?.let { requestHeaders.putAll(it) } }
        currentUrl = mediaUrl; currentHeaders = requestHeaders
        beginStartupClock()
        probeStartedAt = System.currentTimeMillis(); autoPaused = false; chooserShown = false
        preparationBox.visibility = if (mode == LabMode.ASR) View.VISIBLE else View.GONE
        preparation.text = "Preparando legendas…"
        noSubtitleChoice = false; pacer.reset(); stalls = 0; stallTotal = 0; stallMax = 0; stallStarted = 0; stallReasons.clear()
        try {
            mainPlayer = buildPlayer(false, startPosition).apply {
                playWhenReady = false
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        log("PLAYER: ${error.errorCodeName} ${error.message}")
                        val failingPlayer = mainPlayer
                        if (failingPlayer != null && LiveSubtitleClock.shouldRecoverBehindLive(
                                error.errorCode, PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW,
                                failingPlayer.isCurrentMediaItemLive)) {
                            pendingLiveStart = null
                            liveSeekGuardUntil = android.os.SystemClock.elapsedRealtime() + 3000
                            failingPlayer.seekToDefaultPosition()
                            failingPlayer.prepare()
                            failingPlayer.playWhenReady = mainStarted && !autoPaused
                            log("LIVE_MAIN_RECOVERY reason=BEHIND_LIVE_WINDOW clock=${clockOf(failingPlayer)}")
                            return
                        }
                        activeChannelId?.let {
                            val failedUrl = currentUrl
                            channelCatalog.revalidate(failedUrl) { started && !inBackground && currentUrl == failedUrl }
                        }
                    }
                    override fun onRenderedFirstFrame() {
                        log("STARTUP_FIRST_FRAME mediaId=${packageId ?: activeChannelId} epoch=${speech.sessionEpoch} ms=${android.os.SystemClock.elapsedRealtime() - probeStartedElapsed}")
                        activeChannelId?.let { fileWorker.execute { channelCatalog.markPlayed(mediaUrl) } }
                    }
                    override fun onVideoSizeChanged(videoSize: VideoSize) { ui.post { onMainVideoSize() } }
                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        if (timeline.isEmpty) return
                        val w = timeline.getWindow(0, androidx.media3.common.Timeline.Window())
                        if (w.isLive()) mainWindowStart = w.windowStartTimeMs
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED && mainPlayer?.isCurrentMediaItemLive == true) {
                            mainPlayer?.seekToDefaultPosition(); mainPlayer?.prepare()
                            if (mainStarted && !autoPaused) mainPlayer?.play()
                            log("LIVE_MAIN_RECOVERY reason=ENDED")
                            return
                        }
                        if (state == Player.STATE_ENDED) {
                            if (activeChannelId == null && mainPlayer?.isCurrentMediaItemLive != true) { packageConsumed = true; checkpointPackage(force = true); refreshPackageButtons() }
                            savePlaybackProgress()
                            if (currentLocal != null) {
                                val revision = playbackRevision
                                ui.post { if (revision == playbackRevision && started) playNext() }
                            } else {
                                if (mode == LabMode.ASR) speech.finish()
                                if (activeChannelId == null && onlineQueue.isNotEmpty() && onlineQueueCurrent == currentUrl) {
                                    val revision = playbackRevision
                                    ui.post { if (revision == playbackRevision && started) playNextOnline() }
                                }
                            }
                        }
                    }
                    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                        if (playWhenReady && started && !mainStarted) mainPlayer?.pause()
                        if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST &&
                            System.currentTimeMillis() - lastAutoCmdAt > 800) autoPaused = false
                    }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (mode == LabMode.ASR && mainStarted && reason == Player.DISCONTINUITY_REASON_SEEK &&
                            mainPlayer?.isCurrentMediaItemLive != true &&
                            android.os.SystemClock.elapsedRealtime() >= liveSeekGuardUntil) {
                            mainPlayer?.pause(); mainStarted = false; afterSeek = true
                            restartScoutAt(newPosition.positionMs)
                        }
                    }
                })
            }
            mainView.player = mainPlayer
            if (local != null) {
                if (saved == null && pack == null) startLocalAudio(startPosition)
                setFullscreen(true)
            } else {
                if (pack == null && saved == null) startScout(startPosition)
                setFullscreen(true)
            }
            restartUntil = android.os.SystemClock.elapsedRealtime() + 20_000
            restartBox.visibility = View.VISIBLE
            refreshNextEpisode()
            if (mode == LabMode.ASR && saved == null && pack?.language?.takeIf { it != "und" } != null) {
                val resumeAt = AudioCoverage.firstGap(startPosition, pack.coverage)
                speech.forceLanguage(pack.language, selectedTarget(pack.language), resumeAt) { ok ->
                    if (ok && started && packageId == id) {
                        timeline.restore(pack.cues); translations.registerSourceCues(pack.cues, complete = pack.transcriptDone)
                        pack.cues.filter { !it.humanEdited }.forEach { translations.translate(it) }
                        if (local != null) startLocalAudio(resumeAt) else startScout(resumeAt)
                    }
                }
            } else if (mode == LabMode.ASR && saved == null) {
                if (pack != null) { if (local != null) startLocalAudio(startPosition) else startScout(startPosition) }
                (selectedSource ?: remembered)?.let { applyManualLanguage(it) }
            }
            log(if (mode == LabMode.ASR) (if (remembered != null) "Preparando legendas · idioma lembrado: ${LanguageMap.displayName(remembered)}" else if (sourceCodes[sourceSpinner.selectedItemPosition] == null) "Preparando legendas · detectando o idioma pelo áudio" else "Idioma escolhido manualmente") else "OCR HLS · lookahead ${bufferMs / 1000}s")
        } catch (error: Exception) {
            stopVideo(); log("Falha ao abrir HLS: ${error.message}")
        }
    }

    private fun beginStartupClock() {
        probeStartedElapsed = android.os.SystemClock.elapsedRealtime(); startupRecovery = false; startupTranslationLogged = false
    }

    private fun startScout(startMs: Long) {
        scoutEdgePending = true
        scoutPlayer = buildPlayer(true, startMs).apply { configureScout(this); playWhenReady = true }
    }

    private fun buildPlayer(scout: Boolean, startMs: Long): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(UA).setDefaultRequestProperties(currentHeaders)
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(12000).setReadTimeoutMs(12000)
        val player = if (mode == LabMode.ASR && scout) {
            val audioEpoch = speech.sessionEpoch
            val firstPcm = java.util.concurrent.atomic.AtomicBoolean(true)
            val tap = PcmTap(startMs) { samples, rate, at ->
                if (firstPcm.compareAndSet(true, false)) log("PCM_CLOCK first=$at source=CODEC_PTS epoch=$audioEpoch")
                speech.scoutPcm(audioEpoch, samples, rate, at)
            }
            scoutTap = tap
            ExoPlayer.Builder(this, TapRenderersFactory(this, tap))
                .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder()
                    .setBufferDurationsMs(6000, 24000, 500, 2000).setTargetBufferBytes(2 * 1024 * 1024)
                    .setPrioritizeTimeOverSizeThresholds(true).build()).build().also {
                    it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .setMaxAudioChannelCount(2).setMaxAudioBitrate(128_000)
                        .setForceLowestBitrate(true)
                        .setMaxVideoSize(426, 240)
                        .build()
                }
        } else ExoPlayer.Builder(this)
            .setLoadControl(androidx.media3.exoplayer.DefaultLoadControl.Builder().setBufferDurationsMs(40_000, 60_000, 1_500, 5_000).build())
            .build()
        // Embedded/CEA text must not overwrite the app's translated SubtitleView cues.
        player.trackSelectionParameters=player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,true).build()
        val mediaItem = MediaItem.Builder().setUri(currentResolved?.url ?: currentUrl).apply {
            currentResolved?.kind?.mime?.let { setMimeType(it) }
        }.build()
        val source = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)).createMediaSource(mediaItem)
        if (startMs > 0) player.setMediaSource(source, startMs) else player.setMediaSource(source)
        player.prepare()
        return player
    }

    private fun clockOf(player: ExoPlayer): Long {
        val raw = player.currentPosition
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return raw
        val w = timeline.getWindow(player.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
        if (!w.isLive()) return raw
        if (liveEpoch0 != C.TIME_UNSET && w.windowStartTimeMs != C.TIME_UNSET)
            return w.windowStartTimeMs + raw - liveEpoch0
        if (w.defaultPositionMs == C.TIME_UNSET) return raw
        if (liveFallbackOriginElapsedMs == 0L) {
            liveFallbackOriginElapsedMs = android.os.SystemClock.elapsedRealtime()
            log("LIVE_CLOCK_FALLBACK reason=NO_PROGRAM_DATE_TIME")
        }
        return LiveSubtitleClock.stableLiveTime(w.positionInFirstPeriodMs + raw, fallbackPeriodOrigin(w))
    }

    private fun fallbackPeriodOrigin(w: androidx.media3.common.Timeline.Window): Long {
        if (liveFallbackPeriodOrigin == Long.MIN_VALUE) liveFallbackPeriodOrigin = w.positionInFirstPeriodMs
        return liveFallbackPeriodOrigin
    }

    /** Absolute subtitle clock at the first millisecond of this player's live window. */
    private fun liveWindowFirst(player: ExoPlayer, w: androidx.media3.common.Timeline.Window): Long? {
        if (liveEpoch0 != C.TIME_UNSET && w.windowStartTimeMs != C.TIME_UNSET)
            return w.windowStartTimeMs - liveEpoch0
        if (w.defaultPositionMs == C.TIME_UNSET || liveFallbackOriginElapsedMs == 0L) return null
        return LiveSubtitleClock.stableLiveTime(w.positionInFirstPeriodMs, fallbackPeriodOrigin(w))
    }

    private fun seekLiveTo(player: ExoPlayer, clockMs: Long): Boolean {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return false
        val w = timeline.getWindow(player.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
        if (w.durationMs == C.TIME_UNSET) return false
        val first = liveWindowFirst(player, w) ?: return false
        val target = clockMs - first
        if (target < 0 || target > w.durationMs - 300) { pendingLiveStart = null; return false }
        val pending = pendingLiveStart
        if (pending != null && kotlin.math.abs(clockMs - pending) < 300 && kotlin.math.abs(clockOf(player) - pending) < 300 && player.playbackState == Player.STATE_READY) {
            pendingLiveStart = null; return true
        }
        if (kotlin.math.abs(clockOf(player) - clockMs) < 300 && pendingLiveStart == null) return true
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (pendingLiveStart != null && elapsed - pendingLiveSeekAt < 2500) return false
        pendingLiveStart = clockMs; pendingLiveSeekAt = elapsed
        liveSeekGuardUntil = android.os.SystemClock.elapsedRealtime() + 2000
        player.seekTo(target)
        log("LIVE_SEEK requested=$clockMs windowPos=$target actual=${clockOf(player)}")
        return false
    }

    private fun configureScout(player: ExoPlayer) {
        player.volume = 0f
        if (mode == LabMode.OCR) player.setVideoTextureView(scoutPreview)
        player.addListener(object : Player.Listener {
            private var anchored = false
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (mode != LabMode.ASR || timeline.isEmpty) return
                val w = timeline.getWindow(player.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
                if (!w.isLive()) { scoutTap?.setPeriodOffset(0); return }
                if (scoutEdgePending && w.durationMs != C.TIME_UNSET) {
                    val edge = LiveSubtitleClock.scoutEdgeTarget(w.durationMs, w.defaultPositionMs)
                    if (edge != null) {
                        scoutEdgePending = false
                        scoutTap?.reset()
                        player.seekTo(edge)
                        log("LIVE_SCOUT_EDGE windowPos=$edge window=${w.durationMs} default=${w.defaultPositionMs}")
                    } else if (w.durationMs > 0) scoutEdgePending = false
                }
                if (w.windowStartTimeMs == C.TIME_UNSET) {
                    if (!anchored) { anchored = true; log("LIVE_ANCHOR sem PROGRAM-DATE-TIME: alinhamento relativo à borda ao vivo") }
                    return
                }
                if (liveEpoch0 == C.TIME_UNSET) { liveEpoch0 = w.windowStartTimeMs; log("LIVE_ANCHOR base=$liveEpoch0 source=PCM_PTS") }
                scoutTap?.setPeriodOffset(LiveSubtitleClock.periodOffset(w.windowStartTimeMs, w.positionInFirstPeriodMs, liveEpoch0))
                anchored = true
            }
            override fun onPlayerError(error: PlaybackException) {
                log("SCOUT: ${error.errorCodeName} ${error.message}")
                if (started && mode == LabMode.ASR && error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    log("LIVE_SCOUT_RECOVERY reason=BEHIND_LIVE_WINDOW")
                    scoutEdgePending = true; player.seekToDefaultPosition(); player.prepare(); player.play(); return
                }
                if (mode == LabMode.ASR && started && speech.language == null && speech.probing) {
                    showMessage("Falha ao ler o áudio", "O stream não pôde ser lido (${error.errorCodeName}). Confira URL e Referer.")
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && mode == LabMode.ASR && player.isCurrentMediaItemLive && started) {
                    scoutEdgePending = true; player.seekToDefaultPosition(); player.prepare(); player.play()
                    log("LIVE_SCOUT_RECOVERY reason=ENDED")
                    return
                }
                if (state == Player.STATE_ENDED && mode == LabMode.ASR) {
                    if (speech.language == null && started && !speech.bypass) showLanguageChooser("O áudio terminou antes de confirmar um idioma. Escolha o idioma ou continue sem legendas.")
                    else speech.finish()
                }
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    videoRatio = (videoSize.width.toFloat() * videoSize.pixelWidthHeightRatio) / videoSize.height
                    ui.post { updateScoutSize() }
                }
            }
        })
    }

    private fun applyManualLanguage(code: String) {
        if (!started || mode != LabMode.ASR) return
        val at = mainPlayer?.let { clockOf(it) } ?: 0L
        cachedLocal = false; cachedLanguage = null; playbackRevision++; startPosition = at
        beginStartupClock()
        preparationBox.visibility = View.VISIBLE; preparation.text = "Preparando legendas · carregando motor…"
        lastAutoCmdAt = System.currentTimeMillis(); mainPlayer?.pause(); mainStarted = false
        afterSeek = true; autoPaused = false; noSubtitleChoice = false
        localDecoder.cancel()
        scoutPlayer?.run { stop(); release() }; scoutPlayer = null
        val expectedRevision = playbackRevision
        speech.forceLanguage(code, selectedTarget(code), at) { ready ->
            if (!started || mode != LabMode.ASR || playbackRevision != expectedRevision) return@forceLanguage
            if (ready) {
                if (currentLocal != null) startLocalAudio(at) else startScout(if (mainPlayer?.isCurrentMediaItemLive == true) 0L else at)
                rememberCurrentLanguage(code)
                log("ASR em ${LanguageMap.displayName(code)} preparado")
            } else {
                showMessage("ASR indisponível", speech.phase + "\nA reprodução continua sem legendas.")
                noSubtitleChoice = true; speech.continueWithoutSubtitles()
            }
        }
    }

    private fun showLanguageChooser(message: String) {
        if (!asrSessionActive() || chooserShown) return
        if (sheet != null) { ui.postDelayed({ showLanguageChooser(message) }, 3000); return }
        chooserShown = true
        val installed = models.asrLanguages()
        if (installed.isEmpty()) { showMessage("Idioma não identificado", message); return }
        openSheet(if (musicAsr) "Escolha o idioma da música" else "Escolha o idioma do vídeo") { body ->
            body.addView(label(message + "\n\nEscolha um idioma ou continue sem legendas.", 13f).apply { setPadding(0, dp(6), 0, dp(6)) })
            for (code in installed) body.addView(button(LanguageMap.displayName(code).replaceFirstChar { it.uppercase() }) {
                closeSheet(); if (musicAsr) applyManualMusicLanguage(code) else applyManualLanguage(code)
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
            body.addView(button(if (musicAsr) "TOCAR SEM LETRA" else "CONTINUAR SEM LEGENDA") {
                closeSheet()
                if (musicAsr) { stopMusicAsr(); musicStatus = "Sem letra gerada para esta música."; releaseMusicGate() }
                else { noSubtitleChoice = true; speech.continueWithoutSubtitles(); log("Seguindo sem legendas") }
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        }
    }

    private fun restartScoutAt(positionMs: Long) {
        if (cachedLocal) return
        checkpointPackage(force = true)
        playbackRevision++
        localDecoder.cancel()
        startPosition = positionMs
        val resumeAt = AudioCoverage.firstGap(positionMs, packageCoverage)
        beginStartupClock()
        scoutPlayer?.run { stop(); release() }
        scoutPlayer = null
        speech.restoredCoverage = packageCoverage
        val revision = playbackRevision
        speech.seek(resumeAt) { ok ->
            if (ok && started && mode == LabMode.ASR && revision == playbackRevision) {
                timeline.all().forEach { translations.translate(it) }
                if (currentLocal != null) startLocalAudio(resumeAt) else startScout(if (mainPlayer?.isCurrentMediaItemLive == true) 0L else resumeAt)
            } else if (!ok) log("Falha ao reiniciar o ASR após seek")
        }
    }

    private fun stopVideo() {
        checkpointPackage(force = true); packageId = null; manualFill = false
        saveSubtitleCache(force = true); savePlaybackProgress(); playbackRevision++; localOpenRequest++
        if (::localDecoder.isInitialized) localDecoder.cancel()
        currentLocal = null; cachedLocal = false; cachedLanguage = null
        if (::restartBox.isInitialized) restartBox.visibility = View.GONE
        if (::nextEpisode.isInitialized) nextEpisode.visibility = View.GONE
        started = false; mainStarted = false
        ui.removeCallbacks(letterboxTick); letterboxTracker.reset()
        if (::mainView.isInitialized) setLetterboxZoom(1f)
        if (::preparationBox.isInitialized) preparationBox.visibility = View.GONE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (fullscreen) setFullscreen(false)
        speech.stop()
        if (::mainView.isInitialized) mainView.player = null
        scoutPlayer?.run { clearVideoTextureView(scoutPreview); stop(); release() }
        mainPlayer?.run { stop(); release() }
        mainPlayer = null; scoutPlayer = null
        liveEpoch0 = C.TIME_UNSET; liveFallbackOriginElapsedMs = 0L; liveFallbackPeriodOrigin = Long.MIN_VALUE
        pendingLiveStart = null; pendingLiveSeekAt = 0L
        updateChannelDiscovery()
        if (::caption.isInitialized) { showCaption(""); originalLine.visibility = View.GONE }
        updatePlayerChrome()
        if (activeTab == 2 && ::channelThumbnailer.isInitialized && !isDestroyed) {
            channelThumbnailer.setPaused(false)
            ui.post { if (!started && activeTab == 2 && !isDestroyed && ::channelBrowser.isInitialized) channelBrowser.refresh() }
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            tickScheduled = false
            if (!tickerActive) return
            if (inBackground && !started && musicPlayer == null && !sampleMode) return
            if (started && mode == LabMode.OCR) {
                val scout = scoutPlayer
                val player = mainPlayer
                if (scout != null && player != null) {
                    val position = scout.currentPosition
                    if (scout.isPlaying && scoutPreview.isAvailable && pipeline.accepting()) try {
                        val width = if (videoRatio < 0.8f) 560 else 768
                        val height = (width / videoRatio).toInt().coerceIn(192, 1280)
                        scoutPreview.getBitmap(width, height)?.let { pipeline.offer(it, position) }
                    } catch (e: Exception) { log("Frame OCR: ${e.message}") }
                    val ocrWaitMs = android.os.SystemClock.elapsedRealtime() - probeStartedElapsed
                    val ocrReady = position >= startPosition + bufferMs && pipeline.processedThroughMs >= startPosition + max(0L, bufferMs - 1000L)
                    if (!mainStarted && player.playbackState == Player.STATE_READY && (ocrReady || PlaybackStartup.expired(ocrWaitMs))) {
                        mainStarted = true; player.play(); log(if (ocrReady) "OCR pronto · player liberado" else "OCR liberado por tempo limite (${ocrWaitMs}ms)")
                    }
                    if (mainStarted && !inBackground && scout.playbackState != Player.STATE_ENDED) {
                        val ahead = scout.currentPosition - player.currentPosition
                        if (ahead < 1600 && player.isPlaying) {
                            autoPaused = true; lastAutoCmdAt = System.currentTimeMillis(); player.pause()
                        } else if (autoPaused && ahead >= max(2200L, bufferMs - 1300) && !player.isPlaying &&
                            player.playbackState == Player.STATE_READY) {
                            autoPaused = false; lastAutoCmdAt = System.currentTimeMillis(); player.play()
                        }
                    }
                    renderOcrCue(player.currentPosition)
                }
            } else if (started && mode == LabMode.ASR) {
                val player = mainPlayer
                val scout = scoutPlayer
                if (player != null && !inBackground) {
                    if (currentLocal != null) controlAsr(player, null) else if (scout != null) controlAsr(player, scout)
                }
                player?.let { renderSpeechCue(clockOf(it)) }
            } else if (sampleMode) renderOcrCue(1500)
            renderMusic()
            if (started || currentMusic != null) checkpointPackage()
            if (started) {
                if (android.os.SystemClock.elapsedRealtime() >= restartUntil) restartBox.visibility = View.GONE
                if (System.currentTimeMillis() - progressSavedAt > 3000) savePlaybackProgress()
                saveSubtitleCache()
                nextEpisode.visibility = if (currentLocal != null && episodeQueue.isNotEmpty() &&
                    (cachedLocal || speech.processedThroughMs >= Long.MAX_VALUE / 8) &&
                    (mainPlayer?.currentPosition ?: 0) >= (timeline.lastEndMs() ?: Long.MAX_VALUE)) View.VISIBLE else View.GONE
            }
            val debugNow = android.os.SystemClock.elapsedRealtime()
            if (prefs.debugMode && debugOpen && debugNow-debugRenderedAt>=1000) {
                debugRenderedAt = debugNow
                if (debugDirty) { status.text = debugEvents.toList().takeLast(55).asReversed().joinToString("\n"); debugDirty = false }
                val s = pipeline.snapshot()
                stats.text = if (mode == LabMode.OCR) buildString {
                    append(pipeline.engineName).append('\n')
                    append(pipeline.translatorState).append('\n')
                    append("Frames ${s.samples} · OCR ${s.ocrRuns} · média ${s.averageOcrMs}ms\n")
                    append("Traduções ${s.translated} · processado ${s.processedMs}ms")
                } else buildString {
                    append("ASR: ").append(speech.phase).append('\n')
                    append("Motor: ").append(speech.engineName).append(" · modelos: ").append(modelsSummary).append('\n')
                    append("Tradução: ").append(translations.state).append('\n')
                    append(translations.cacheStats).append('\n')
                    append("Pausas: $stalls · total ${stallTotal / 1000.0}s · maior ${stallMax / 1000.0}s · ${stallReasons.maxByOrNull { it.value }?.key ?: "—"}\n")
                    append("RTF: ${"%.2f".format(speech.rtfEwma)} · fase ${speech.phaseCode}\n")
                    append("Cues: ").append(timeline.size())
                    val main = mainPlayer; val scoutNow = scoutPlayer
                    if (started && main != null && scoutNow != null) {
                        append(" · áudio à frente ").append(signedSeconds((scoutTap?.latestTimeMs?.takeIf { it != Long.MIN_VALUE } ?: clockOf(scoutNow)) - clockOf(main)))
                        val ready = speech.processedThroughMs
                        if (speech.language != null && ready < Long.MAX_VALUE / 8)
                            append(" · ASR processado ").append(signedSeconds(ready - clockOf(main)))
                    }
                    append(" · fila ASR ").append(speech.backlogMs / 1000).append("s · descartes ").append(speech.packetsDropped)
                    append(" · traduções pendentes ").append(translations.pendingTranslations)
                    append(" · busca de canais ").append(if (channelCatalog.isScanning) "ativa" else "pausada")
                }
            }
            tickScheduled = true
            ui.postDelayed(this, 350L)
        }
    }

    private var modelsSummary = ""
    /** "+2s" / "-2s" / "0s" — avoids the "+-2s" artefact when the ASR is behind the picture. */
    private fun signedSeconds(ms: Long): String = (if (ms > 0) "+" else "") + "${ms / 1000}s"
    private fun sourceSpinnerLabels(): List<String> =
        listOf("Automático (detectar pelo áudio)") + LanguageCatalog.entries.map {
            it.namePt + if (models.asrBackendFor(it.code) != null) " · instalado"
            else if (it.code in LanguageCatalog.senseVoiceLanguages) " · baixar SenseVoice/VAD"
            else " · baixar Whisper/VAD"
        }

    private fun refreshModelsSummary() {
        modelsSummary = models.asrLanguages().joinToString { LanguageMap.displayName(it) }.ifEmpty { "nenhum" } +
            (if (models.installed("vad")) " · VAD ok" else " · sem VAD")
        if (::sourceSpinner.isInitialized) {
            val pos = sourceSpinner.selectedItemPosition.coerceAtLeast(0)
            lastSourcePos = pos
            sourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, sourceSpinnerLabels())
            sourceSpinner.setSelection(pos, false)
        }
    }

    private fun applySubtitleStyle() {
        val view = if (::translatedSubtitleView.isInitialized) translatedSubtitleView else null
        view?.apply {
            val alpha = prefs.backgroundPercent * 255 / 100
            val background = alpha shl 24
            val edge = if (prefs.backgroundPercent < 30) CaptionStyleCompat.EDGE_TYPE_OUTLINE else CaptionStyleCompat.EDGE_TYPE_NONE
            setStyle(CaptionStyleCompat(Color.WHITE, background, Color.TRANSPARENT, edge, Color.BLACK, null))
            setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.textSizeSp.toFloat())
        }
        shownKey = ""
    }

    private fun showCaption(text: String) {
        val view = if (::translatedSubtitleView.isInitialized) translatedSubtitleView else null
        if (view != null) {
            val key = "${prefs.positionPercent}|$text"
            if (key == shownKey) return
            shownKey = key
            view.setCues(if (text.isBlank()) emptyList() else listOf(
                SubCue.Builder().setText(text)
                    .setLine(prefs.positionPercent / 100f, SubCue.LINE_TYPE_FRACTION)
                    .setLineAnchor(SubCue.ANCHOR_TYPE_END)
                    .setPosition(0.5f).setPositionAnchor(SubCue.ANCHOR_TYPE_MIDDLE).setSize(0.92f)
                    .build()
            ))
            caption.visibility = View.GONE
        } else {
            caption.text = text
            caption.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }
    }

    private fun showOriginal(text: String) {
        val visible = text.isNotBlank() && prefs.showOriginal
        originalLine.text = text
        originalLine.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun controlAsr(player: ExoPlayer, scout: ExoPlayer?) {
        if (scout != null && scout.isCurrentMediaItemLive && liveEpoch0 == C.TIME_UNSET) {
            val tap = scoutTap
            if (tap != null && !tap.clockReady && tap.rawPacketTimeMs != Long.MIN_VALUE) {
                val ref = clockOf(scout)
                if (liveFallbackOriginElapsedMs != 0L && !scout.currentTimeline.isEmpty) {
                    val w = scout.currentTimeline.getWindow(scout.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
                    if (w.defaultPositionMs != C.TIME_UNSET) {
                        val offset = LiveSubtitleClock.FALLBACK_BASE_MS - fallbackPeriodOrigin(w)
                        tap.setRelativeLiveOffset(offset)
                        log("LIVE_PCM_CALIBRATE raw=${tap.rawPacketTimeMs} offset=$offset scout=$ref")
                    }
                }
            }
        }
        if (!startupTranslationLogged && timeline.all().any { !it.output.isNullOrBlank() && TranslationCoverage.playable(it, cachedLanguage ?: speech.language ?: packageLanguage, "pt") }) {
            startupTranslationLogged = true; log("STARTUP_FIRST_TRANSLATION mediaId=${packageId ?: activeChannelId} epoch=${speech.sessionEpoch} ms=${android.os.SystemClock.elapsedRealtime() - probeStartedElapsed}")
        }
        val now = System.currentTimeMillis()
        val liveClock = scout != null && player.isCurrentMediaItemLive &&
            (liveEpoch0 != C.TIME_UNSET || liveFallbackOriginElapsedMs != 0L)
        val waitMs = android.os.SystemClock.elapsedRealtime() - probeStartedElapsed
        val realtime = scout != null && (scout.isCurrentMediaItemLive || player.isCurrentMediaItemLive)
        val mainPos = if (liveClock) clockOf(player) else player.currentPosition
        val scoutPos = if (scout == null) localDecoder.decodedThroughMs else if (liveClock) scoutTap?.latestTimeMs?.takeIf { it != Long.MIN_VALUE } ?: clockOf(scout) else scout.currentPosition
        val liveInput = liveClock && speech.language != null && speech.isActive
        // Keep live playbackPosition anchored inside the active HLS window even before MainAction.START so live cues are never deferred at 0ms.
        if (liveClock && !mainStarted && !speech.bypass && !noSubtitleChoice && !player.currentTimeline.isEmpty) {
            val wPre = player.currentTimeline.getWindow(player.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
            val preFirst = liveWindowFirst(player, wPre)
            if (preFirst != null && wPre.durationMs != C.TIME_UNSET && wPre.durationMs > 1000) {
                val preAnchor = PlaybackStartup.liveAnchor(preFirst, preFirst + wPre.durationMs, speech.processedThroughMs, maxOf(prefs.delaySeconds * 1000L, 8000L))
                if (preAnchor != null) translations.updatePlaybackPosition(preAnchor,false)
            }
        }
        val decision = pacer.decide(PacerInput(
            mainPos, scoutPos,
            maxOf(speech.processedThroughMs, AudioCoverage.firstGap(mainPos, packageCoverage)), speech.backlogMs, speech.language != null, speech.isActive,
            speech.rtfEwma, mainStarted, player.isPlaying, afterSeek, prefs.delaySeconds * 1000L,
            autoPaused, scout?.playWhenReady ?: true, scout?.let { it.playbackState == Player.STATE_ENDED } ?: localDecoder.ended,
            player.playbackState == Player.STATE_READY, speech.bypass || noSubtitleChoice || cachedLocal, prefs.smoothPauses, live = realtime, startupWaitMs=waitMs, recoveryPlayback = startupRecovery
        ))
        if (scout != null) {
            val maxLiveLead = maxOf(26_000L, prefs.delaySeconds * 1000L + 15_000L)
            val liveAhead = scoutPos - mainPos
            val scoutPlay = if (realtime) !speech.bypass &&
                (if (scout.playWhenReady) speech.backlogMs < 12_000 && (!liveClock || speech.language == null || liveAhead < maxLiveLead)
                 else speech.backlogMs < 4_000 && (!liveClock || speech.language == null || liveAhead < maxLiveLead - 5_000))
                else decision.scoutPlay
            val scoutSpeed = if (realtime) 1f else decision.scoutSpeed
            if (scout.playWhenReady != scoutPlay) { scout.playWhenReady = scoutPlay; if (scoutPlay) scoutPlayingSince = android.os.SystemClock.elapsedRealtime() }
            if (scout.playbackParameters.speed != scoutSpeed) scout.setPlaybackSpeed(scoutSpeed)
        }
        if (realtime && !speech.bypass) {
            val liveScout = scout ?: return
            val last = scoutTap?.lastPacketElapsedMs ?: 0L
            val elapsed = android.os.SystemClock.elapsedRealtime()
            if (liveScout.playWhenReady && last > 0 && elapsed - maxOf(last, scoutPlayingSince) > 10_000 && elapsed - scoutRecoveryAt > 15_000) {
                scoutRecoveryAt = elapsed; log("LIVE_SCOUT_RECOVERY state=${liveScout.playbackState} pcmAgeMs=${elapsed - last}")
                scoutEdgePending = true; liveScout.seekToDefaultPosition(); if (liveScout.playerError != null || liveScout.playbackState == Player.STATE_ENDED) liveScout.prepare(); liveScout.play()
            }
        }
        if (player.playbackParameters.speed != decision.mainSpeed) player.setPlaybackSpeed(decision.mainSpeed)
        preparationBox.visibility = if (!mainStarted || autoPaused) View.VISIBLE else View.GONE
        val preparationText = if (speech.language == null && !speech.bypass) "Preparando legendas · identificando idioma…"
        else "Preparando…" + if (decision.reason == "RTF_SLOW") "\nO reconhecimento precisa de mais tempo neste aparelho" else ""
        if (preparation.text.toString() != preparationText) preparation.text = preparationText
        val action = if (liveInput && !mainStarted && player.playbackState == Player.STATE_READY) MainAction.START else decision.main
        when(action) {
            MainAction.START -> {
                var startupNeed = decision.needMs
                if (liveClock && !speech.bypass && !noSubtitleChoice) {
                    val w = player.currentTimeline.getWindow(player.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
                    val first = liveWindowFirst(player, w)
                    if (first != null && w.durationMs != C.TIME_UNSET && w.durationMs > 1000) {
                        val end = first + w.durationMs
                        val translated = timeline.all().filter { TranslationCoverage.playable(it, speech.language ?: "und", "pt") && !it.output.isNullOrBlank() }
                            .map { LiveSubtitleClock.Span(it.startMs, it.endMs) }
                        startupNeed = maxOf(prefs.delaySeconds * 1000L, 8000L)
                        val anchor = PlaybackStartup.liveAnchor(first, end, speech.processedThroughMs, startupNeed)
                        if (anchor != null) translations.updatePlaybackPosition(anchor,false)
                        val target = PlaybackStartup.liveTarget(first, end, speech.processedThroughMs, startupNeed, translated, waitMs, pendingLiveStart)
                        if (target == null || !seekLiveTo(player, target)) {
                            if (waitMs < PlaybackStartup.MAX_WAIT_MS + 5000) {
                                preparationBox.visibility = View.VISIBLE
                                preparation.text = "Preparando legendas ao vivo · alinhando áudio e vídeo…"
                                return
                            }
                            log("STARTUP_RECOVERY live=true reason=SEEK_TIMEOUT")
                            pendingLiveStart = null; player.seekToDefaultPosition()
                        }
                    } else if (!PlaybackStartup.expired(waitMs)) return
                }
                if (PlaybackStartup.expired(waitMs)) log("STARTUP_RECOVERY phase=${speech.phaseCode} local=${currentLocal != null} pending=${translations.pendingTranslations}")
                startupRecovery = PlaybackStartup.expired(waitMs)
                mainStarted = true; afterSeek = false; autoPaused = false
                lastAutoCmdAt = now; player.play(); mainView.hideController(); preparationBox.visibility = View.GONE
                log("PLAYER_START t=${clockOf(player)} lead=${speech.processedThroughMs - clockOf(player)} need=$startupNeed waitMs=$waitMs lang=${speech.language} cues=${timeline.size()}")
            }
            MainAction.PAUSE -> {
                autoPaused = true; lastAutoCmdAt = now; player.pause(); mainView.hideController()
                stalls++; stallStarted = now; pacer.noteStall(clockOf(player))
                stallReasons[decision.reason] = (stallReasons[decision.reason] ?: 0) + 1
                log("STALL t=${clockOf(player)} lead=${speech.processedThroughMs - clockOf(player)} backlog=${speech.backlogMs} known=${speech.language != null} engine=${speech.engineName} rtfEwma=${speech.rtfEwma} scoutAhead=${scoutPos - mainPos} phase=${speech.phaseCode} reason=${decision.reason}")
            }
            MainAction.RESUME -> {
                val duration = if (stallStarted > 0) now - stallStarted else 0
                stallTotal += duration; stallMax = maxOf(stallMax, duration); stallStarted = 0
                autoPaused = false; lastAutoCmdAt = now; player.play(); mainView.hideController()
                log("STALL_RESUME durationMs=$duration reason=${decision.reason}")
            }
            MainAction.NONE -> Unit
        }
        updateChromeArrows()
    }

    // ------------------------------------------------------------------ fullscreen

    private fun setFullscreen(on: Boolean) {
        if (on == fullscreen || !::videoBox.isInitialized) return
        closeSheet()
        fullscreen = on
        videoBox.visibility = View.VISIBLE
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            videoParent = videoBox.parent as? ViewGroup
            videoIndex = videoParent?.indexOfChild(videoBox) ?: 0
            videoParams = videoBox.layoutParams
            videoParent?.removeView(videoBox)
            (window.decorView as ViewGroup).addView(videoBox, ViewGroup.LayoutParams(-1, -1))
            videoBox.setBackgroundColor(Color.BLACK)
            val size = mainPlayer?.videoSize
            val landscape = size == null || size.width == 0 || size.width >= size.height
            requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            manualFill = false
            (videoBox.parent as? ViewGroup)?.removeView(videoBox)
            val home = videoParent
            val params = videoParams
            if (home != null && params != null) home.addView(videoBox, videoIndex, params) else home?.addView(videoBox)
            videoBox.background = rounded(0xff101010.toInt(), 15f)
            requestedOrientation = if (debugOpen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        shownKey = ""
        applyResize(); updateFillButton(); updatePlayerChrome()
    }

    @Deprecated("Back exits fullscreen first")
    override fun onBackPressed() {
        if (sheet != null) closeSheet()
        else if (::pages.isInitialized && pages.isOpen) pages.back()
        else if (::musicController.isInitialized && musicController.lyricsOpen) musicController.closeLyricsPage(animate = true)
        else if (::musicController.isInitialized && musicController.nowOpen) musicController.closeNowPlaying()
        else if (fullscreen) exitVideo()
        else if (debugOpen) closeDebug()
        else if (started) exitVideo()
        else if (!browseUp()) {
            if (musicPlayer != null) moveTaskToBack(true) else super.onBackPressed()
        }
    }

    private fun browseUp(): Boolean {
        if (started) return false
        val media = when (activeTab) { 0 -> localMedia; 3 -> musicMedia; else -> return false }
        if (media.root.isEmpty() || media.path.size <= 1) return false
        val tab = activeTab
        if (tab == 0) localFilterQuery = "" else musicFilterQuery = ""
        fileWorker.execute { runCatching { media.up(media.path.size - 2) }; ui.post { if (tab == 0) refreshLocal() else refreshMusic() } }
        return true
    }

    private fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
        while (true) {
            val n = read(chunk); if (n < 0) break
            require(out.size() + n <= limit) { "Arquivo excede limite" }
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    private fun buildDebugPage() {
        debugPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK); visibility = View.GONE }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(12), dp(8)) }
        header.addView(iconOnly(R.drawable.ic_back) { closeDebug() }.apply { contentDescription = "Voltar do debug" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        debugTitle = label("Debug vídeos locais", 18f, true)
        header.addView(debugTitle, LinearLayout.LayoutParams(0, -2, 1f))
        debugPage.addView(header)
        debugBody = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), 0, dp(16), dp(24)) }
        debugPage.addView(ScrollView(this).apply { addView(debugBody); isFillViewport = true }, LinearLayout.LayoutParams(-1, 0, 1f))
        rootFrame.addView(debugPage, FrameLayout.LayoutParams(-1, -1))
    }

    private fun addDebugCard(title: String, text: TextView, heading: TextView = label(title, 11f, true)) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = rounded(0xff141414.toInt(), 10f); setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(iconOnly(R.drawable.ic_copy) {
            val token = debugSession
            fun copy(contents: String) {
                if (isDestroyed || token != debugSession) return
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(title, contents))
                android.widget.Toast.makeText(this, "Log copiado", android.widget.Toast.LENGTH_SHORT).show()
            }
            if (text === status) debugLogWorker.execute {
                val contents = runCatching { debugLogFile.readText() }.getOrDefault("")
                ui.post { copy(contents) }
            } else copy(text.text.toString())
        }.apply { contentDescription = "Copiar $title" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        card.addView(row); card.addView(text, LinearLayout.LayoutParams(-1, -2))
        debugBody.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
    }

    private fun resetDebug(origin: Int) {
        debugSession++; debugOrigin = origin; debugEvents.clear()
        debugLogWorker.execute { runCatching { debugLogFile.writeText("") } }
        if (::status.isInitialized) status.text = ""
        if (::stats.isInitialized) stats.text = "Aguardando reprodução."
    }

    private fun openDebug() {
        if (!prefs.debugMode || debugOpen || !::debugPage.isInitialized) return
        closeSheet()
        debugOpen = true
        debugTitle.text = when (debugOrigin) { 0 -> "Debug vídeos locais"; 1 -> "Debug online"; 2 -> "Debug canais"; else -> "Debug músicas" }
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        debugPage.animate().cancel(); debugPage.visibility = View.VISIBLE; debugPage.bringToFront()
        debugPage.translationX = resources.displayMetrics.widthPixels.toFloat()
        debugPage.animate().translationX(0f).setDuration(220).start()
        updatePlayerChrome()
    }

    private fun closeDebug(stopPlayback: Boolean = true, animate: Boolean = true) {
        if (!debugOpen) return
        debugOpen = false
        if (stopPlayback && started) stopVideo()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        debugPage.animate().cancel()
        if (animate) debugPage.animate().translationX(resources.displayMetrics.widthPixels.toFloat()).setDuration(220)
            .withEndAction { if (!debugOpen) debugPage.visibility = View.GONE }.start()
        else debugPage.visibility = View.GONE
        updatePlayerChrome()
    }

    private fun exitVideo() {
        packageOpenRequest++
        if (prefs.debugMode && started) {
            if (fullscreen) setFullscreen(false)
            openDebug()
        } else {
            val tab = activeTab
            stopVideo()
            if (tab == 0) refreshLocal()
            if (pages.topKey?.startsWith("episodes:") == true) episodeReload?.invoke()
        }
    }

    private fun applyDebug() {
        if (!::debugButton.isInitialized) return
        debugButton.visibility = if (prefs.debugMode) View.VISIBLE else View.GONE
        if (!prefs.debugMode) {
            if (debugOpen) closeDebug(animate = false)
            else if (started && !fullscreen) stopVideo()
        }
        updatePlayerChrome()
    }

    private fun applyMask() {
        maskView?.let {
            it.centerPercent = prefs.maskCenter; it.heightPercent = prefs.maskHeight
            it.active = prefs.maskEnabled; it.invalidate()
        }
    }

    // ---- automatic zoom for baked-in black bars (see LetterboxDetector)
    private val letterboxPixels = IntArray(LetterboxDetector.W * LetterboxDetector.H)
    private val letterboxTracker = LetterboxDetector.Tracker()
    private var letterboxZoom = 1f
    private val letterboxTick = object : Runnable {
        override fun run() {
            if (!started || isDestroyed) return
            scanLetterbox()
            ui.postDelayed(this, 1500)
        }
    }

    private fun scanLetterbox() {
        val tex = mainView.videoSurfaceView as? TextureView
        val player = mainPlayer
        val eligible = !manualFill && prefs.autoZoomBars && fullscreen && mainStarted && player?.isPlaying == true && player.playbackState != Player.STATE_IDLE && !inBackground &&
            mode == LabMode.ASR && !prefs.maskEnabled && !isVerticalVideo() && tex != null && tex.isAvailable
        if (!eligible) { letterboxTracker.reset(); if (letterboxZoom != 1f) setLetterboxZoom(1f); return }
        val bitmap = runCatching { tex!!.getBitmap(LetterboxDetector.W, LetterboxDetector.H) }.getOrNull() ?: return
        bitmap.getPixels(letterboxPixels, 0, LetterboxDetector.W, 0, 0, LetterboxDetector.W, LetterboxDetector.H)
        bitmap.recycle()
        val bars = letterboxTracker.push(LetterboxDetector.measure(letterboxPixels))
        val surface = tex!!
        val zoom = if (bars <= 0f || surface.width == 0 || surface.height == 0) 1f else
            min(mainView.width.toFloat() / surface.width, mainView.height.toFloat() / (surface.height * (1f - bars))).coerceAtLeast(1f)
        if (kotlin.math.abs(zoom - letterboxZoom) > 0.01f) setLetterboxZoom(zoom)
    }

    private fun setLetterboxZoom(zoom: Float) {
        letterboxZoom = zoom
        val surface = mainView.videoSurfaceView ?: return
        surface.animate().cancel()
        surface.pivotX = surface.width / 2f; surface.pivotY = surface.height / 2f
        surface.animate().scaleX(zoom).scaleY(zoom).setDuration(300).start()
    }

    private fun isVerticalVideo(): Boolean = correctedVideoRatio() < 1f

    private fun correctedVideoRatio(): Float {
        val size = mainPlayer?.videoSize ?: return 16f / 9f
        if (size.width <= 0 || size.height <= 0) return 16f / 9f
        val ratio = size.width * size.pixelWidthHeightRatio / size.height
        return if (size.unappliedRotationDegrees % 180 != 0) 1f / ratio else ratio
    }

    private fun applyResize() {
        if (!::mainView.isInitialized) return
        mainView.resizeMode = if (fullscreen && (manualFill || (isVerticalVideo() && prefs.fillVertical)))
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    private fun updateFillButton() {
        if (::horizontalFillButton.isInitialized) {
            val ratio = correctedVideoRatio()
            horizontalFillButton.visibility = if (fullscreen && resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE && kotlin.math.abs(ratio - 16f / 9f) < 0.12f && mainView.width > mainView.height * ratio + dp(4) && mainView.isControllerFullyVisible) View.VISIBLE else View.GONE
            horizontalFillButton.isSelected = manualFill
            horizontalFillButton.background = rounded(if (manualFill) 0xff285147.toInt() else 0x88000000.toInt(), 12f)
        }
        if (!::fillButton.isInitialized) return
        fillButton.visibility = if (fullscreen && isVerticalVideo() && mainView.isControllerFullyVisible) View.VISIBLE else View.GONE
        fillButton.setColorFilter(if (prefs.fillVertical) 0xff8accc1.toInt() else Color.WHITE)
    }

    private fun onMainVideoSize() {
        val size = mainPlayer?.videoSize
        if (size == null || size.width == 0 || size.height == 0) return
        val vertical = isVerticalVideo()
        letterboxTracker.reset(); setLetterboxZoom(1f)
        ui.removeCallbacks(letterboxTick); ui.postDelayed(letterboxTick, 800)
        if (vertical != prefs.vertical) { prefs.vertical = vertical; applySubtitleStyle(); applyMask() }
        if (fullscreen && currentLocal != null) requestedOrientation = if (vertical) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        applyResize(); updateFillButton()
    }

    private fun renderOcrCue(positionMs: Long) {
        pipeline.updatePlaybackPosition(positionMs, mainStarted && mainPlayer?.isPlaying == true)
        val cue = pipeline.cueAt(positionMs)
        if (cue == null) { showCaption(""); showOriginal(""); return }
        when (selectedTarget("zh")) {
            "pt" -> {
                pipeline.ensureTranslated(positionMs)
                val pt = SubtitleTiming.display(cue.translation).takeIf { it.isNotEmpty() }
                showCaption(if (pt != null) SubtitleTiming.wrap(pt, prefs.vertical) else "")
                showOriginal(if (pt != null) cue.chinese else "")
            }
            else -> { showCaption(cue.chinese); showOriginal("") }
        }
    }

    private fun renderSpeechCue(positionMs: Long) {
        if(mainStarted || mainPlayer?.isCurrentMediaItemLive!=true ||
            (liveEpoch0==C.TIME_UNSET && liveFallbackOriginElapsedMs==0L))
            translations.updatePlaybackPosition(positionMs, mainStarted && mainPlayer?.isPlaying == true)
        val live = mainPlayer?.isCurrentMediaItemLive == true && currentLocal == null
        val cue = if (live) timeline.atLive(positionMs, cachedLanguage ?: speech.language ?: packageLanguage, "pt")
            else timeline.at(positionMs)
        if (cue == null) {
            logSubtitleRender(positionMs, null, live)
            showCaption(""); showOriginal(""); return
        }
        translations.ensure(cue)
        val translated = cue.output
        logSubtitleRender(positionMs, cue, live)
        showCaption(SubtitleTiming.wrap(SubtitleTiming.display(cue), prefs.vertical))
        showOriginal(if (!translated.isNullOrEmpty() && translated != cue.source) cue.source else "")
    }

    private fun logSubtitleRender(positionMs: Long, cue: SpeechCue?, live: Boolean) {
        if (!live || !prefs.debugMode) return
        val key = "${cue?.id ?: "none"}:${!cue?.output.isNullOrBlank()}"
        val now = android.os.SystemClock.elapsedRealtime()
        if (key == lastSubtitleRenderKey && now - lastSubtitleRenderLogAt < 4000) return
        lastSubtitleRenderKey = key; lastSubtitleRenderLogAt = now
        log("SUBTITLE_RENDER playerMs=$positionMs cueStartMs=${cue?.startMs ?: -1} " +
            "cueEndMs=${cue?.endMs ?: -1} cueFound=${cue != null} " +
            "translationReady=${!cue?.output.isNullOrBlank()} captionVisible=${cue != null && !cue.output.isNullOrBlank() && TranslationCoverage.playable(cue, cachedLanguage ?: speech.language ?: packageLanguage, "pt")} " +
            "clock=${if (liveEpoch0 != C.TIME_UNSET) "PDT" else "FALLBACK"}")
    }

    private fun testFrame() {
        if (mode != LabMode.OCR) { log("TESTAR FRAME funciona somente em OCR"); return }
        if (!models.installed("ocr")) { showMessage("OCR ausente", "Baixe o modelo OCR primeiro."); return }
        stopVideo(); pipeline.reset(); pipeline.prepareModel(); sampleMode = true
        ensureTickerScheduled()
        log("Testando imagem de exemplo do OCR")
        ui.postDelayed({ if (sampleMode) assets.open("sample_frame.jpg").use { BitmapFactory.decodeStream(it) }?.let { pipeline.offer(it, 1000) } }, 1200)
        ui.postDelayed({ if (sampleMode) assets.open("sample_frame.jpg").use { BitmapFactory.decodeStream(it) }?.let { pipeline.offer(it, 1500) } }, 2700)
    }

    private fun exportSrt() {
        val name = if (exportMode == LabMode.ASR) "aura_asr_legendas.srt" else "aura_ocr_legendas.srt"
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "application/x-subrip"
            putExtra(Intent.EXTRA_TITLE, name)
        }
        startActivityForResult(intent, EXPORT_REQUEST)
    }

    @Deprecated("Android 8+ document export")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        if (requestCode == 318) {
            val text = draftSrt ?: return; draftSrt = null
            fileWorker.execute {
                val ok = runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
                ui.post { if (!isDestroyed) showMessage("Exportar", if (ok) "SRT exportado." else "Não foi possível exportar o SRT.") }
            }
            return
        }
        if (requestCode == 315) { refreshLocal(authorize = uri); return }
        if (requestCode == 316) { refreshMusic(authorize = uri); return }
        if (requestCode == 317) {
            val item = currentMusic ?: return
            fileWorker.execute {
                val saved = runCatching {
                    val raw = contentResolver.openInputStream(uri)!!.use { it.readBytesBounded(2_000_000) }.toString(Charsets.UTF_8)
                    require(LyricsParser.fromText(raw).isNotEmpty())
                    val dir = java.io.File(filesDir, "music_lrc"); dir.mkdirs()
                    java.io.File(dir, lyricsKey(item.uri) + ".lrc").writeText(raw)
                }.isSuccess
                ui.post {
                    if (isDestroyed) return@post
                    if (!saved) showMessage("Letra", "Não foi possível ler esse arquivo de letra.")
                    else if (currentMusic?.uri == item.uri) { musicPreferLyrics = true; loadMusicDetails(item, musicRevision) }
                }
            }
            return
        }
        if (requestCode in 311..314) {
            val modelId = pendingModelImport
            Thread({
                try {
                    when (requestCode) {
                        311 -> contentResolver.openOutputStream(uri, "wt")?.use { it.write(translations.vault.exportJson().toByteArray(Charsets.UTF_8)) }
                        312 -> {
                            val raw = contentResolver.openInputStream(uri)!!.use { it.readBytesBounded(20_000_000).toString(Charsets.UTF_8) }
                            val count = translations.vault.importJson(raw); translations.clearCache(); log("Cofre: $count entradas importadas")
                        }
                        313 -> if (modelId != null) contentResolver.openInputStream(uri)!!.use { models.importArchive(modelId, it) }
                        314 -> contentResolver.openInputStream(uri)!!.use { models.importManifest(it.readBytesBounded(200_000).toString(Charsets.UTF_8)) }
                    }
                    log("Arquivo processado"); ui.post { refreshModelsSummary() }
                } catch (e: Exception) { log("Arquivo: ${e.message}") }
            }, "aura-file-io").start()
            return
        }
        if (requestCode != EXPORT_REQUEST) return
        val which = exportMode
        val speechSnapshot = timeline.all(); val ocrSnapshot = pipeline.exportSnapshot()
        val speechEpoch = translations.sessionEpoch; val ocrEpoch = pipeline.translationEpoch
        log("Exportando SRT · completando traduções pendentes…")
        Thread({
            try {
                val missing = if (which == LabMode.ASR) translations.translatePendingBlocking(speechSnapshot, speechEpoch)
                else pipeline.translatePendingBlocking(ocrSnapshot, ocrEpoch)
                val text = if (which == LabMode.ASR) timeline.exportSrt(speechSnapshot) else pipeline.srt(ocrSnapshot)
                val out = contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("Destino indisponível")
                out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ui.post {
                    log("SRT exportado com timestamps da mídia" + if (missing > 0) " · $missing legenda(s) sem tradução (mantido o idioma original)" else "")
                    if (!isDestroyed) showMessage("Exportar", "SRT exportado." + if (missing > 0) " $missing legenda(s) ficaram sem tradução (mantido o idioma original)." else "")
                }
            } catch (e: Exception) {
                ui.post { log("Falha ao exportar SRT: ${e.message}"); if (!isDestroyed) showMessage("Exportar", "Não foi possível exportar o SRT: ${e.message}") }
            }
        }, "aura-srt-export").start()
    }

    private fun updateScoutSize() {
        if (!::scoutPreview.isInitialized) return
        val w = dp(220); val h = (w / videoRatio).toInt().coerceIn(dp(112), dp(420))
        scoutPreview.layoutParams = scoutPreview.layoutParams.apply { width = w; height = h }
    }

    private fun log(message: String) {
        if (isDestroyed || isFinishing || debugLogWorker.isShutdown) return
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) { val token = debugSession; runOnUiThread { if (token == debugSession) log(message) }; return }
        android.util.Log.d("AuraLab", message)
        if (!::status.isInitialized || !prefs.debugMode) return
        debugEvents.addLast("• $message")
        debugLogWorker.execute { runCatching { debugLogFile.appendText("• $message\n") } }
        while (debugEvents.size > 300) debugEvents.removeFirst()
        debugDirty=true
    }

    // Shared widget delegates -> UiWidgets
    private fun label(text: String, size: Float, bold: Boolean = false) = widgets.label(text, size, bold)
    private fun muted(text: String, size: Float = 12f) = widgets.muted(text, size)
    private fun sectionLabel(text: String) = widgets.sectionLabel(text)
    private fun input(hint: String, initial: String) = widgets.input(hint, initial)
    private fun iconButton(icon: Int, title: String, click: () -> Unit) = widgets.iconButton(icon, title, click)
    private fun iconOnly(icon: Int, click: () -> Unit) = widgets.iconOnly(icon, click)
    private fun button(title: String, click: () -> Unit) = widgets.button(title, click)
    private fun cardRow(onClick: () -> Unit) = widgets.cardRow(onClick)
    private fun progressBar(position: Long, duration: Long) = widgets.progressBar(position, duration)
    private fun toggle(text: String, checked: Boolean, change: (Boolean) -> Unit) = widgets.toggle(text, checked, change)
    private fun ripple(color: Int, radiusDp: Float, stroke: Int = 0) = widgets.ripple(color, radiusDp, stroke)
    private fun rounded(color: Int, radiusDp: Float) = widgets.rounded(color, radiusDp)
    private fun dp(value: Int) = widgets.dp(value)
    private fun seekListener(changed: (Int) -> Unit) = widgets.seekListener(changed)

    // ---- Músicas (aba 4): pasta local, ASR compartilhado e delegação de UI ao MusicPlayerController ----
    private fun selectMusicFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        }, 316)
    }

    private fun refreshMusic(restore: Boolean = false, authorize: Uri? = null) {
        if (authorize != null) musicFilterQuery = ""
        val request = ++musicBrowserRevision
        musicPanel.removeAllViews(); musicPanel.addView(muted("Carregando pasta…"))
        fileWorker.execute {
            val result = runCatching {
                if (authorize != null) musicMedia.authorize(authorize) else if (restore) musicMedia.restore()
                musicMedia.children()
            }
            ui.post {
                if (isFinishing || isDestroyed || request != musicBrowserRevision) return@post
                musicEntries = result.getOrDefault(emptyList())
                showMusicFiles(result.exceptionOrNull()?.message)
            }
        }
    }

    private fun showMusicFiles(error: String? = null) {
        renderFolder(
            musicPanel, musicMedia, musicEntries, error,
            "Dê a permissão à pasta onde suas músicas se encontram",
            "Esta pasta não contém músicas ou subpastas.",
            "Crie pastas e subpastas com as músicas que você gosta. Toque em uma pasta para entrar e em “..” (ou no caminho) para voltar. O app reabre a última pasta.",
            musicFilterQuery, { musicFilterQuery = it },
            { selectMusicFolder() }, { refreshMusic() }
        ) { item ->
            val playing = item.uri == currentMusic?.uri
            cardRow { openMusic(item) }.apply {
                if (playing) background = ripple(0xff1d2b29.toInt(), 14f, 0xff3DDC84.toInt())
                addView(label("♪", 18f, true).apply { setTextColor(if (playing) 0xff3DDC84.toInt() else 0xff8accc1.toInt()); gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(30), -2))
                val column = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL }
                column.addView(label(item.name.substringBeforeLast('.'), 14f).apply { maxLines = 2; if (playing) setTextColor(0xff3DDC84.toInt()) })
                subtitleButton(SubtitlePackageStore.local(item), item.name)?.let { column.addView(it, LinearLayout.LayoutParams(-2, dp(48))) }
                addView(column, LinearLayout.LayoutParams(0, -2, 1f))
            }
        }
        musicPanel.addView(android.widget.Space(this), LinearLayout.LayoutParams(-1, dp(84)))
    }

    private fun lyricsKey(uri: String) = java.security.MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun toggleMusic() {
        if (musicGate && musicAsr && speech.language == null && !speech.bypass && !noSubtitleChoice) return
        musicGate = false
        musicPlayer?.let { if (it.isPlaying) it.pause() else { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.play() } }
    }

    private fun releaseMusicGate() {
        if (!musicGate) return
        musicGate = false; musicPlayer?.play()
    }

    private fun openMusic(item: LocalVideo, keepQueue: Boolean = false) {
        if (started) stopVideo()
        speech.musicMode = true
        if (!keepQueue) { musicQueue.clear(); musicQueue.addAll(musicMedia.ordered(musicEntries.filter { !it.directory })); queuePolicy.reset(item.uri) }
        if (musicQueue.none { it.uri == item.uri }) { musicQueue.clear(); musicQueue.add(item); queuePolicy.reset(item.uri) }
        checkpointPackage(force = true)
        packageId = SubtitlePackageStore.local(item); packageConsumed = false; packageDone = false; packageCoverage = emptyList(); packageSavedAt = 0L; packageLanguage = "und"
        val revision = ++musicRevision
        saveLyricsCache(force = true)
        stopMusicAsr(); timeline.reset()
        resetDebug(3)
        musicPlayer?.release()
        currentMusic = item
        ensureTickerScheduled()
        updateChannelDiscovery()
        musicSynced = false; musicLang = null; musicBitmap = null; musicSource = ""; musicStatus = ""
        musicTitleText = item.name.substringBeforeLast('.'); musicArtistText = ""; musicCachedCount = -1; musicCacheSavedAt = 0L
        musicController.resetTrackVisuals()
        musicGate = true; musicGateUntil = android.os.SystemClock.elapsedRealtime() + 50_000
        musicPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) ui.post { if (revision == musicRevision) { packageConsumed = true; checkpointPackage(force = true); refreshPackageButtons(); musicStep(1, auto = true) } }
                    }
                    override fun onIsPlayingChanged(isPlaying: Boolean) { ui.post { if (revision == musicRevision) musicController.syncNotification(musicTitleText, musicArtistText, musicBitmap, force = true) } }
                    override fun onPlayerError(error: PlaybackException) {
                        log("MÚSICA: ${error.errorCodeName} ${error.message}")
                        ui.post { if (revision == musicRevision) showMessage("Música", "Não foi possível reproduzir ${item.name}.\nO formato pode não ser compatível com este aparelho.") }
                    }
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        if (reason == Player.DISCONTINUITY_REASON_SEEK) ui.post {
                            if (revision == musicRevision) {
                                musicSeekedTo(newPosition.positionMs)
                                musicController.syncNotification(musicTitleText, musicArtistText, musicBitmap, force = true)
                            }
                        }
                    }
                })
                setMediaItem(MediaItem.fromUri(Uri.parse(item.uri))); prepare(); playWhenReady = false
            }
        loadMusicDetails(item, revision)
        askNotificationPermissionOnce()
        MusicService.start(this)
        musicController.syncNotification(musicTitleText, musicArtistText, musicBitmap, force = true)
        renderMusic()
        if (activeTab == 3) showMusicFiles()
    }

    private fun askNotificationPermissionOnce() {
        if (notificationAsked || android.os.Build.VERSION.SDK_INT < 33) return
        notificationAsked = true
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 71)
    }

    private fun musicStep(delta: Int, auto: Boolean = false) {
        val current = currentMusic ?: return
        val ids = musicQueue.map { it.uri }
        val target = if (delta > 0) queuePolicy.next(ids, current.uri, auto) else queuePolicy.previous(ids, current.uri)
        if (target == null) { if (delta > 0) { musicPlayer?.pause(); musicController.syncNotification(force = true) } else musicPlayer?.seekTo(0); return }
        if (target == current.uri) { musicPlayer?.run { seekTo(0); play() }; return }
        val next = musicQueue.firstOrNull { it.uri == target } ?: return
        openMusic(next, keepQueue = true)
    }

    private fun musicPrevious() { if ((musicPlayer?.currentPosition ?: 0L) > 3000L) musicPlayer?.seekTo(0) else musicStep(-1) }

    private fun stopMusic() {
        if (musicPlayer == null && currentMusic == null) return
        checkpointPackage(force = true); packageId = null
        saveLyricsCache(force = true)
        musicRevision++
        stopMusicAsr()
        musicPlayer?.run { stop(); release() }
        musicPlayer = null; currentMusic = null; musicGate = false
        updateChannelDiscovery()
        MusicService.stop(this)
        if (::musicController.isInitialized) musicController.hideAndCloseAll()
        if (::musicPanel.isInitialized && activeTab == 3) showMusicFiles()
    }

    private fun loadMusicDetails(item: LocalVideo, revision: Int) {
        fileWorker.execute {
            var title: String? = null; var artist: String? = null; var art: android.graphics.Bitmap? = null
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, Uri.parse(item.uri))
                    title = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                    artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    retriever.embeddedPicture?.let { bytes ->
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val sample = Integer.highestOneBit(maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 512))
                        art = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }
                } finally { retriever.release() }
            }
            val palette = MusicBackdrop.palette(art)
            val durable = runCatching { packages.load(SubtitlePackageStore.local(item)) }.getOrNull()
            val remembered = durable?.language?.takeIf { it != "und" } ?: history.languageFor(item.uri)
            musicLanguageHint = ScriptLanguage.hint(title, artist, item.name)
            val preferLyrics = musicPreferLyrics; musicPreferLyrics = false
            val cachedAsr = if (preferLyrics) null else remembered?.let { runCatching { lyricsCache.load(item, it, "pt", "asr-music-v5-timed-api") }.getOrNull() }
            val imported = java.io.File(filesDir, "music_lrc/${lyricsKey(item.uri)}.lrc").takeIf { it.isFile }
                ?.let { runCatching { LyricsParser.fromText(it.readText()) }.getOrNull() }?.takeIf { it.isNotEmpty() }
            val lines = if (cachedAsr != null) emptyList() else imported ?: EmbeddedLyrics.read(this, Uri.parse(item.uri))
            val synced = lines.any { it.startMs >= 0 }
            val language = LyricsParser.guessLanguage(lines) ?: remembered ?: musicLanguageHint
            val policy = "lyrics-api-v4:" + lines.joinToString("\n") { it.text }.hashCode()
            val cachedLyrics = if (language != null && language != "pt" && lines.isNotEmpty())
                runCatching { lyricsCache.load(item, language, "pt", policy) }.getOrNull() else null
            ui.post {
                if (isDestroyed || revision != musicRevision) return@post
                musicTitleText = title?.takeIf { it.isNotBlank() } ?: musicTitleText
                musicArtistText = artist.orEmpty(); musicBitmap = art
                musicController.updatePalette(palette)
                if (durable != null && durable.language != "und") {
                    packageLanguage = durable.language; packageConsumed = durable.consumed; packageDone = durable.transcriptDone; packageCoverage = durable.coverage
                    musicSource = "asr"; musicSynced = true; musicLang = durable.language
                    if (durable.transcriptDone) { restoreCues(durable.language, durable.cues); releaseMusicGate() }
                    else {
                        musicAsr = true; musicSource = "asr"; musicSynced = true; musicLang = durable.language
                        speech.musicMode = true; speech.startProbe(); speech.preserveTimeline = true; speech.restoredCoverage = durable.coverage
                        val at = AudioCoverage.firstGap(0, durable.coverage); musicAsrAt = at
                        speech.forceLanguage(durable.language, selectedTarget(durable.language), at) { ok ->
                            if (ok && revision == musicRevision) { restoreCues(durable.language, durable.cues, complete = false); startMusicDecoder(item, at, revision) }
                        }
                    }
                } else prepareLyrics(item, revision, lines, synced, language, policy, cachedAsr, cachedLyrics, remembered)
                renderMusic()
            }
        }
    }

    private fun prepareLyrics(
        item: LocalVideo,
        revision: Int,
        lines: List<LyricLine>,
        synced: Boolean,
        language: String?,
        policy: String,
        cachedAsr: LocalSubtitleCache.Entry?,
        cachedLyrics: LocalSubtitleCache.Entry?,
        remembered: String?
    ) {
        stopMusicAsr(); timeline.reset(); musicPolicy = policy; musicStatus = ""
        fun show(code: String?, cues: List<SpeechCue>) {
            if (code != null && code != "pt") restoreCues(code, cues)
            else {
                cues.forEach { it.output = it.source; if (code == null) { it.provider = "original"; it.reason="ECHO:ORIGINAL" } }
                timeline.restore(cues)
            }
        }
        when {
            cachedAsr!=null -> { musicSource = "asr"; musicSynced = true; musicLang = cachedAsr.language; show(cachedAsr.language, cachedAsr.cues) }
            lines.isNotEmpty() && synced -> {
                val cues = lyricCues(lines, true)
                musicSource = "lyrics"; musicSynced = true; musicLang = language
                show(language, cachedLyrics?.takeIf { it.cues.size == cues.size }?.cues ?: cues)
            }
            asrPossible(remembered) -> startMusicAsr(item, revision, remembered)
            lines.isNotEmpty() -> { musicSource = "lyrics"; musicSynced = false; musicLang = language; show(language, lyricCues(lines, false)) }
            else -> musicStatus = "Sem letra neste arquivo. Baixe VAD + SenseVoice/Whisper em MODELOS para gerar a letra pelo áudio."
        }
        if (!musicAsr) { packageDone = lines.isNotEmpty() || cachedAsr != null; releaseMusicGate() }
    }

    private fun lyricCues(lines: List<LyricLine>, synced: Boolean): List<SpeechCue> =
        lines.indices.filter { lines[it].text.isNotEmpty() }.map { i ->
            val start = if (synced) lines[i].startMs else i.toLong()
            val end = if (synced) (lines.getOrNull(i + 1)?.startMs?.takeIf { it > start } ?: (start + 6000L)) else start + 1
            SpeechCue(start, end, lines[it].text)
        }

    private fun asrPossible(remembered: String?) = models.anyAsrInstalled() &&
        ((remembered != null && models.asrBackendFor(remembered) != null) || models.installed("lid"))

    private fun startMusicAsr(item: LocalVideo, revision: Int, remembered: String?) {
        musicAsr = true; musicSource = "asr"; musicSynced = true; musicLang = null; musicAsrAt = 0L
        chooserShown = false; noSubtitleChoice = false
        musicGateUntil = android.os.SystemClock.elapsedRealtime() + 50_000
        speech.strictness = prefs.noiseLevel; speech.laughterMode = prefs.laughterMode
        speech.musicMode = true
        speech.startProbe(); speech.preserveTimeline = true
        speech.languageHint = musicLanguageHint
        val known = remembered?.let { LanguageMap.pickInstalled(it, models.asrLanguages()) }
        if (known != null) {
            musicLang = known
            speech.forceLanguage(known, selectedTarget(known), 0L) { ready ->
                if (revision != musicRevision || !musicAsr) return@forceLanguage
                if (ready) startMusicDecoder(item, 0L, revision) else musicAsrFailed(speech.phase)
            }
        } else startMusicDecoder(item, 0L, revision)
    }

    private fun startMusicDecoder(item: LocalVideo, at: Long, revision: Int) =
        decodeForAsr(item, at, { revision == musicRevision && musicAsr && currentMusic?.uri == item.uri }) { musicAsrFailed(it) }

    private fun applyManualMusicLanguage(code: String) {
        val item = currentMusic ?: return
        val revision = musicRevision
        musicLang = code; musicAsrAt = 0L; history.rememberLanguage(item.uri, code)
        localDecoder.cancel()
        speech.forceLanguage(code, selectedTarget(code), 0L) { ready ->
            if (revision != musicRevision || !musicAsr) return@forceLanguage
            if (ready) startMusicDecoder(item, 0L, revision) else musicAsrFailed(speech.phase)
        }
    }

    private fun musicSeekedTo(position: Long) {
        val item = currentMusic ?: return
        if (!musicAsr || speech.language == null || speech.processedThroughMs >= Long.MAX_VALUE / 8) return
        if (position <= speech.processedThroughMs - 500) return
        checkpointPackage(force = true)
        val revision = musicRevision
        val at = AudioCoverage.firstGap(position, packageCoverage)
        localDecoder.cancel(); musicAsrAt = at; speech.restoredCoverage = packageCoverage
        speech.seek(at) { ok ->
            if (ok && musicAsr && revision == musicRevision) {
                translations.registerSourceCues(timeline.all())
                timeline.all().forEach { translations.translate(it) }
                startMusicDecoder(item, at, revision)
            }
        }
    }

    private fun stopMusicAsr() {
        if (!musicAsr) return
        musicAsr = false
        localDecoder.cancel(); speech.stop(); timeline.reset()
    }

    private fun musicAsrFailed(message: String) {
        if (!musicAsr) return
        stopMusicAsr(); musicStatus = "Não foi possível gerar a letra: $message"; releaseMusicGate()
    }

    private fun saveLyricsCache(force: Boolean = false) {
        val item = currentMusic ?: return
        val language = musicLang ?: return
        val asr = musicSource == "asr"
        if (!asr && language == "pt") return
        if (asr && musicAsr && speech.processedThroughMs < Long.MAX_VALUE / 8) return
        val cues = timeline.all()
        if (cues.isEmpty()) return
        val allowPlayableOffline = !translations.isCurrentlyOnline()
        if (cues.any { if (allowPlayableOffline) !TranslationCoverage.playable(it, language, "pt") else !TranslationCoverage.complete(it, language, "pt") }) return
        val done = cues.count { it.output != null }
        if (done == musicCachedCount || (!force && System.currentTimeMillis() - musicCacheSavedAt < 5000)) return
        musicCachedCount = done; musicCacheSavedAt = System.currentTimeMillis()
        val snapshot = cues
        val policy = if (asr) "asr-music-v5-timed-api" else musicPolicy
        fileWorker.execute { runCatching { lyricsCache.save(item, language, "pt", policy, snapshotCues(snapshot)) } }
    }

    private fun currentCueIndex(cues: List<SpeechCue>, position: Long): Int {
        if (!musicSynced) return -1
        val i = cues.indexOfLast { it.startMs <= position }
        return if (i >= 0 && position > cues[i].endMs + 4000) -1 else i
    }

    private fun musicStatusLine(): String {
        if (translations.musicProvisionalCount > 0) {
            val offlineNow = !translations.isCurrentlyOnline()
            return if (offlineNow) "Letra offline (ML Kit / Cofre) · atualiza quando reconectar"
            else "Letra provisória · Google · aguardando a IA (${translations.lastApiFailure ?: "recuperação"})"
        }
        if (musicLang != null && musicLang != "pt" && timeline.all().any { it.output == null }) {
            return "Traduzindo letra com IA…" + translations.lastApiFailure?.let { " · $it" }.orEmpty()
        }
        val language = speech.language?.let { LanguageMap.displayName(it) }
        return when {
            musicAsr && musicGate -> if (language == null) "Preparando a letra · identificando o idioma pelo áudio…" else "Preparando a letra em $language…"
            musicAsr && language == null && !speech.bypass -> "Identificando o idioma pelo áudio…"
            musicAsr && speech.processedThroughMs < Long.MAX_VALUE / 8 -> "Gerando a letra · ${language ?: "idioma detectado"} → português"
            musicLang != null && musicLang != "pt" && timeline.size() > 0 -> "Letra em ${LanguageMap.displayName(musicLang!!)}"
            else -> musicStatus
        }
    }

    private fun renderMusic() {
        val player = musicPlayer ?: return
        val position = player.currentPosition.coerceAtLeast(0)
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        if (musicGate && (!musicAsr || speech.bypass || noSubtitleChoice ||
                (speech.language != null && speech.processedThroughMs >= MUSIC_LEAD_MS &&
                    timeline.all().any { !it.output.isNullOrBlank() && TranslationCoverage.playable(it, speech.language ?: "und", "pt") }) ||
                android.os.SystemClock.elapsedRealtime() >= musicGateUntil)) {
            releaseMusicGate()
        }
        translations.updatePlaybackPosition(position)
        val cues = timeline.all().filter { it.output != "" }
        val index = currentCueIndex(cues, position)
        val cue = cues.getOrNull(index)
        if (cue != null && musicLang != null && musicLang != "pt") translations.ensure(cue)
        val translated = cue?.let { SubtitleTiming.display(it) }?.takeIf { it.isNotEmpty() }
        val status = musicStatusLine()
        val preview = if (cue != null) (translated ?: if (musicLang == null || musicLang == "pt") cue.source else "Traduzindo letra com IA…") else ""
        musicController.render(
            titleText = musicTitleText,
            artistText = musicArtistText,
            artwork = musicBitmap,
            cues = cues,
            index = index,
            synced = musicSynced,
            position = position,
            duration = duration,
            playing = player.isPlaying,
            status = status,
            preview = preview
        )
        saveLyricsCache()
    }

    // ---- Online: playlists ---------------------------------------------------------------------------
    private fun refreshPlaylists() {
        if (!::playlistGrid.isInitialized) return
        playlistGrid.removeAllViews()
        val lists = playlists.all()
        val progressByUrl = history.all().associate { it.url to (it.positionMs to it.durationMs) }
        if (lists.isEmpty()) playlistGrid.addView(muted("Nenhuma playlist. Crie uma e adicione links do player.", 12f))
        lists.chunked(2).forEach { pair ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { i, list ->
                val seen = list.items.count { watched(progressByUrl[it.url] ?: (0L to 0L)) }
                val nextItem = playlists.firstUnfinished(list) { progressByUrl[it] ?: (0L to 0L) }
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; background = ripple(0xff141414.toInt(), 14f, 0xff262626.toInt()); setPadding(dp(12), dp(12), dp(12), dp(10)); isClickable = true
                    setOnClickListener { showPlaylistEpisodes(list.id) }
                }
                reorderable(card,"pl:${list.id}") { from, to -> playlists.moveList(from, to); refreshPlaylists() }
                card.addView(label(list.name, 14f, true).apply { maxLines = 2 })
                card.addView(muted("${list.items.size} itens · $seen assistidos", 12f))
                if (nextItem != null) {
                    val resumeBtn = button("CONTINUAR") { playPlaylistItem(list, nextItem) }.apply {
                        background = ripple(0xff1d2b29.toInt(), 12f, 0xff3DDC84.toInt())
                        setTextColor(0xff3DDC84.toInt())
                    }
                    card.addView(resumeBtn, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) })
                }
                card.addView(button("EPISÓDIOS") { showPlaylistEpisodes(list.id) }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
                row.addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i == 0) rightMargin = dp(4) else leftMargin = dp(4) })
            }
            if (pair.size == 1) row.addView(android.widget.Space(this), LinearLayout.LayoutParams(0, 1, 1f))
            playlistGrid.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
    }

    private fun playPlaylistItem(list: OnlinePlaylist, item: PlaylistItem) {
        onlineQueue.clear(); onlineQueue.addAll(list.items.dropWhile { it.id != item.id }.drop(1))
        onlineQueueCurrent = item.url; activeChannelId = null
        url.setText(item.url); referer.setText(item.referer)
        if (mode != LabMode.ASR) changeMode(LabMode.ASR)
        startVideo(item.url, episodeIdentity = item.id)
    }

    private fun playNextOnline() {
        val item = onlineQueue.firstOrNull() ?: return
        onlineQueue.removeAt(0); onlineQueueCurrent = item.url
        url.setText(item.url); referer.setText(item.referer)
        startVideo(item.url, episodeIdentity = item.id)
    }

    private fun showPlaylistEpisodes(id: String) {
        if (pages.topKey == "episodes:$id") return
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(4), dp(4), dp(4)) }
        bar.addView(iconOnly(R.drawable.ic_back) { pages.back() }.apply { contentDescription = "Voltar" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val title = label("Episódios", 20f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val listView = android.widget.ListView(this).apply { dividerHeight = dp(1) }
        var playlist: OnlinePlaylist? = null
        var progressByUrl = emptyMap<String, Pair<Long, Long>>()
        lateinit var reload: () -> Unit
        val adapter = object : android.widget.BaseAdapter() {
            override fun getCount() = playlist?.items?.size ?: 0
            override fun getItem(position: Int) = playlist!!.items[position]
            override fun getItemId(position: Int) = getItem(position).id.hashCode().toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(4), dp(6))
                    addView(LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL; minimumHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
                        addView(label("", 14f).apply { maxLines = 2 }); addView(progressBar(0, 0))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(button("Legenda") {}, LinearLayout.LayoutParams(-2, dp(48)))
                    addView(label("≡", 24f).apply { gravity = Gravity.CENTER; contentDescription = "Arrastar episódio" }, LinearLayout.LayoutParams(dp(48), dp(48)))
                }
                val item = getItem(position); val progress = progressByUrl[item.url] ?: (0L to 0L)
                val text = row.getChildAt(0) as LinearLayout
                (text.getChildAt(0) as TextView).text = item.title
                (text.getChildAt(1) as android.widget.ProgressBar).apply { this.progress = if (progress.second > 0) (progress.first.toDouble() / progress.second * 1000).toInt().coerceIn(0, 1000) else 0 }
                text.setOnClickListener { playlist?.let { playPlaylistItem(it, item) } }
                val subtitleId = SubtitlePackageStore.online(item.id, item.url)
                row.getChildAt(1).apply { visibility = if (subtitleId in availablePackages) View.VISIBLE else View.GONE; setOnClickListener { openSubtitlePackage(subtitleId, item.title) } }
                val handle = row.getChildAt(2)
                handle.setOnLongClickListener { it.startDragAndDrop(ClipData.newPlainText("episode", item.id), View.DragShadowBuilder(it), item.id, 0) }
                row.setOnLongClickListener(null)
                row.setOnDragListener { v, event ->
                    val from = event.localState as? String
                    when (event.action) {
                        android.view.DragEvent.ACTION_DRAG_STARTED -> from != null && playlist?.items?.any { it.id == from } == true
                        android.view.DragEvent.ACTION_DRAG_ENTERED -> { v.alpha = 0.6f; true }
                        android.view.DragEvent.ACTION_DRAG_EXITED, android.view.DragEvent.ACTION_DRAG_ENDED -> { v.alpha = 1f; true }
                        android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                            val xy = IntArray(2); v.getLocationOnScreen(xy); val base = IntArray(2); listView.getLocationOnScreen(base)
                            val y = xy[1] - base[1] + event.y
                            if (y < dp(64)) listView.smoothScrollBy(-dp(48), 100) else if (y > listView.height - dp(64)) listView.smoothScrollBy(dp(48), 100)
                            true
                        }
                        android.view.DragEvent.ACTION_DROP -> { v.alpha = 1f; if (from != null && from != item.id) fileWorker.execute { playlists.moveItemTo(id, from, item.id); ui.post { reload(); refreshPlaylists() } }; true }
                        else -> true
                    }
                }
                return row
            }
        }
        reload = {
            val data = playlists.all().firstOrNull { it.id == id }
            val progress = history.all().associate { it.url to (it.positionMs to it.durationMs) }
            if (!isDestroyed && data != null) {
                playlist = data; progressByUrl = progress; title.text = data.name; adapter.notifyDataSetChanged()
            }
        }
        bar.addView(button("Adicionar episódio") { promptEpisode(id, reload) }, LinearLayout.LayoutParams(-2, dp(48)))
        bar.addView(iconOnly(R.drawable.ic_menu) {
            playlist?.let { saved ->
                openSheet("Playlist") { body ->
                    body.addView(button("Renomear") { promptPlaylistName(saved) })
                    body.addView(button("Excluir playlist") {
                        confirmAction("Excluir esta playlist?", "Cancelar", "Excluir") {
                            fileWorker.execute { playlists.delete(id); ui.post { closeSheet(); pages.back(); refreshPlaylists() } }
                        }
                    })
                    saved.items.forEach { entry ->
                        body.addView(button("Remover: ${entry.title}") {
                            fileWorker.execute { playlists.removeItem(id, entry.id); ui.post { closeSheet(); reload(); refreshPlaylists() } }
                        })
                    }
                }
            }
        }.apply { contentDescription = "Opções da playlist" }, LinearLayout.LayoutParams(dp(48), dp(48)))
        page.addView(bar); listView.adapter = adapter; page.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))
        episodeReload = reload
        pages.open("episodes:$id", page, activeTab) { episodeReload = null; true }; reload()
    }

    private fun promptEpisode(id: String, changed: () -> Unit = { episodeReload?.invoke() }) {
        openSheet("Adicionar episódio") { body ->
            val name = widgets.textInput("Nome do episódio", "")
            val link = input("Link CDN", "")
            val ref = input("Referer opcional", "")
            fun submit() {
                val title = name.text.toString().trim(); val address = link.text.toString().trim(); val refer = ref.text.toString().trim()
                if (title.isBlank() || !StreamResolver.httpUrl(address) || (refer.isNotBlank() && !StreamResolver.httpUrl(refer))) { showMessage("Episódio", "Informe nome e URLs HTTP(S) válidos."); return }
                fileWorker.execute {
                    val added = playlists.add(id, title, address, refer)
                    ui.post {
                        if (added) { closeSheet(); changed(); refreshPlaylists() } else showMessage("Episódio", "Link duplicado ou limite de 200 episódios atingido.")
                    }
                }
            }
            body.addView(name, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            body.addView(link, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(4) })
            body.addView(widgets.urlQuickActionsRow(link), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
            body.addView(ref, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            widgets.onImeDone(ref) { submit() }
            body.addView(button("Adicionar") { submit() }, LinearLayout.LayoutParams(-1, dp(48)))
            widgets.focusAndShowKeyboard(name)
        }
    }

    private fun promptPlaylistName(existing: OnlinePlaylist?, addLink: Boolean = false) {
        openSheet(if (existing == null) "Nova playlist" else "Renomear playlist") { body ->
            val field = widgets.textInput("Nome da playlist", existing?.name.orEmpty())
            fun submit() {
                val name = field.text.toString().trim()
                if (name.isBlank()) { showMessage("Playlist", "Informe um nome."); return }
                fileWorker.execute {
                    val made = if (existing == null) playlists.create(name) else { playlists.rename(existing.id, name); existing.copy(name = name) }
                    ui.post {
                        if (isDestroyed) return@post
                        if (made == null) showMessage("Playlist", "Limite de ${PlaylistStore.MAX_LISTS} playlists atingido.")
                        else {
                            closeSheet(); refreshPlaylists()
                            if (existing != null) episodeReload?.invoke()
                            else if (addLink) addCurrentLinkTo(made)
                            else { showPlaylistEpisodes(made.id); promptEpisode(made.id) }
                        }
                    }
                }
            }
            widgets.onImeDone(field) { submit() }
            body.addView(field, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(8) })
            body.addView(button("Salvar") { submit() }, LinearLayout.LayoutParams(-1, dp(48)))
            widgets.focusAndShowKeyboard(field)
        }
    }

    private fun addCurrentLinkTo(list: OnlinePlaylist) {
        val link = url.text.toString().trim()
        val ref = referer.text.toString()
        val title = SubtitleSettingsSheet.shortUrl(link)
        fileWorker.execute {
            val added = playlists.add(list.id, title, link, ref)
            ui.post {
                if (isDestroyed) return@post
                showMessage("Playlist", if (added) "Link adicionado a “${list.name}”." else "Este link já está na playlist ou ela está cheia.")
                refreshPlaylists()
            }
        }
    }

    private fun addLinkToPlaylist() {
        val link = url.text.toString().trim()
        if (!link.startsWith("https://", true) && !link.startsWith("http://", true)) { showMessage("Playlist", "Cole uma URL HTTP(S) válida primeiro."); return }
        openSheet("Adicionar à playlist") { body ->
            playlists.all().forEach { l -> body.addView(button(l.name) { closeSheet(); addCurrentLinkTo(l) }) }
            body.addView(button("＋ NOVA PLAYLIST") { promptPlaylistName(null, addLink = true) })
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig); updateScoutSize()
        if (newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE) manualFill = false
        if (queueSheetOpen) showEpisodeQueue()
        if (::musicController.isInitialized && musicController.nowOpen && (pages.topKey == null || pages.topKey == "lyrics")) {
            val lyrics = musicController.lyricsOpen
            musicController.showNowPlaying()
            if (lyrics) musicController.showLyricsPage(animate = false)
        }
        applyResize(); updateFillButton()
    }

    override fun onStart() {
        super.onStart(); inBackground = false
        ensureTickerScheduled()
        if (started && mode == LabMode.ASR) { scoutPlayer?.play(); if (mainStarted && resumeVideoOnStart) mainPlayer?.play() }
        if (::localDecoder.isInitialized) localDecoder.paused = false
        if (::channelThumbnailer.isInitialized && activeTab == 2 && !started) {
            channelThumbnailer.setPaused(false)
            if (::channelBrowser.isInitialized) channelBrowser.refresh()
        }
        refreshModelsSummary()
        updateChannelDiscovery()
    }

    override fun onStop() {
        inBackground = true
        updateChannelDiscovery()
        if (::channelThumbnailer.isInitialized) channelThumbnailer.setPaused(true)
        checkpointPackage(force = true)
        savePlaybackProgress(); if (::localDecoder.isInitialized) localDecoder.paused = musicPlayer==null
        resumeVideoOnStart = mainPlayer?.playWhenReady == true
        mainPlayer?.pause(); scoutPlayer?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        closing = true
        fun safe(block: () -> Unit) { try { block() } catch (_: Throwable) {} }
        safe { editorTranslator?.close(); editorTranslator = null }
        MusicBridge.clearCallbacks()
        tickerActive = false; tickScheduled = false; ui.removeCallbacksAndMessages(null)
        safe { closeSheet() }
        packageOpenRequest++
        safe { streamWorker.shutdownNow() }
        if (::pages.isInitialized) safe { pages.closeAll() }
        safe { stopMusic() }; safe { stopVideo() }
        if (::channelThumbnailer.isInitialized) safe { channelThumbnailer.close() }
        safe { debugLogWorker.shutdown() }
        if (::channelCatalog.isInitialized) safe { channelCatalog.close() }
        if (::packages.isInitialized) safe { fileWorker.execute { packages.close() } }
        safe { fileWorker.shutdown() }; safe { thumbnails.shutdownNow() }
        if (::localDecoder.isInitialized) safe { localDecoder.close() }
        if (::speech.isInitialized) safe { speech.close() }
        if (::translations.isInitialized) safe { translations.close() }
        if (::pipeline.isInitialized) safe { pipeline.close() }
        if (::models.isInitialized) safe { models.close() }
        super.onDestroy()
    }
}

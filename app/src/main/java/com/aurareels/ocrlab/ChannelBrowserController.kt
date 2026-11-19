package com.aurareels.ocrlab

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executor

/**
 * Controller for the **Canais** tab:
 *  - Keeps an empty built-in playlist for backwards compatibility; no channels are preloaded.
 *  - Provides **"Criar playlist"** and smart **"Adicionar fonte de canais"** routing (creates a playlist when
 *    none exist yet; asks between existing user playlists or a new one when playlists already exist).
 *  - Includes coalesced UI updates (zero layout thrash during discovery bursts), instant channel search/filter,
 *    zero-flicker cached thumbnails, 48dp touch targets and accidental-delete protection on favorite channels.
 */
class ChannelBrowserController(
    private val activity: Activity,
    private val ui: Handler,
    private val widgets: UiWidgets,
    private val catalog: ChannelCatalog,
    private val thumbnailer: ChannelThumbnailer,
    private val streamWorker: Executor,
    private val resolveStream: (String, Map<String, String>) -> ResolvedStream,
    private val openSheet: (title: String, maxFraction: Float, onClose: () -> Unit, fill: (LinearLayout) -> Unit) -> View,
    private val closeSheet: () -> Unit,
    private val showMessage: (String, String) -> Unit,
    private val confirmAction: (message: String, negative: String, positive: String, confirmed: () -> Unit) -> Unit,
    private val isChannelTabActive: () -> Boolean,
    private val isPlaybackBusy: () -> Boolean,
    private val isDebugOrBackground: () -> Boolean,
    private val onPlayChannel: (LiveChannel) -> Unit
) {
    lateinit var panel: LinearLayout; private set
    private lateinit var playlistStrip: LinearLayout
    private lateinit var filterContainer: LinearLayout
    private lateinit var statusView: TextView
    private lateinit var channelList: LinearLayout
    private var sheetStatusView: TextView? = null
    private var shownRevision = -1L
    private var activeFilterPlaylistId = ""
    private var filterQuery = ""
    private var filterBuiltForPlaylistId: String? = null
    private var pendingRefresh = false

    private val coalescedRefresh = Runnable {
        pendingRefresh = false
        if (!activity.isDestroyed && !activity.isFinishing) refresh()
    }

    fun buildPanel(): LinearLayout {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val topActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val addSourceBtn = widgets.iconButton(R.drawable.ic_datacenter, "Adicionar fonte de canais") {
            promptAddSource()
        }
        val createPlaylistBtn = widgets.button("Criar playlist") {
            promptCreatePlaylist()
        }
        topActions.addView(addSourceBtn, LinearLayout.LayoutParams(0, widgets.dp(48), 1.45f).apply { rightMargin = widgets.dp(8) })
        topActions.addView(createPlaylistBtn, LinearLayout.LayoutParams(0, widgets.dp(48), 1f))
        root.addView(topActions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = widgets.dp(8); bottomMargin = widgets.dp(8) })

        val playlistHeader = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val scroller = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
        }
        playlistStrip = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, widgets.dp(2), 0, widgets.dp(2))
        }
        scroller.addView(playlistStrip)
        playlistHeader.addView(scroller, LinearLayout.LayoutParams(0, -2, 1f))
        playlistHeader.addView(
            widgets.iconOnly(R.drawable.ic_gear) { showPlaylistOptions(catalog.selectedPlaylist()) }.apply {
                contentDescription = "Gerenciar playlist selecionada"
            },
            LinearLayout.LayoutParams(widgets.dp(48), widgets.dp(48)).apply { leftMargin = widgets.dp(4) }
        )
        root.addView(playlistHeader, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = widgets.dp(6) })

        filterContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(filterContainer, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = widgets.dp(6) })

        statusView = widgets.muted("").apply { setPadding(0, widgets.dp(4), 0, widgets.dp(8)) }
        root.addView(statusView)

        channelList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        root.addView(channelList)
        panel = root
        return root
    }

    /** Coalesces rapid background discovery callbacks so active scrolling and touch stay 60fps smooth. */
    fun onCatalogChanged() {
        if (activity.isDestroyed || activity.isFinishing) return
        val summary = catalog.statusSummary
        if (::statusView.isInitialized) statusView.text = summary
        sheetStatusView?.text = summary
        if (shownRevision != catalog.revision && !pendingRefresh) {
            pendingRefresh = true
            ui.postDelayed(coalescedRefresh, 180L)
        }
    }

    fun updateDiscoverySuspension(suspended: Boolean) {
        catalog.setSuspended(suspended)
        val summary = catalog.statusSummary
        if (::statusView.isInitialized) statusView.text = summary
        sheetStatusView?.text = summary
    }

    fun refresh() {
        if (!::channelList.isInitialized) return
        ui.removeCallbacks(coalescedRefresh)
        pendingRefresh = false
        shownRevision = catalog.revision

        val activeId = catalog.selectedPlaylistId
        if (activeFilterPlaylistId != activeId) {
            activeFilterPlaylistId = activeId
            filterQuery = ""
            filterBuiltForPlaylistId = null
        }

        val allPlaylists = catalog.playlists()
        val countsByPlaylist = catalog.channelCountsByPlaylist()

        playlistStrip.removeAllViews()
        allPlaylists.forEachIndexed { idx, pl ->
            val selected = pl.id == activeId
            val count = countsByPlaylist[pl.id] ?: 0
            val chip = TextView(activity).apply {
                text = if (count > 0) "${pl.name} · $count" else pl.name
                textSize = 12.5f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                minHeight = widgets.dp(42)
                setPadding(widgets.dp(14), widgets.dp(10), widgets.dp(14), widgets.dp(10))
                background = if (selected) {
                    widgets.rounded(0xff1d2b29.toInt(), 12f).apply { setStroke(widgets.dp(1), 0xff8accc1.toInt()) }
                } else {
                    widgets.ripple(0xff1c1c1c.toInt(), 12f, 0xff2b2b2b.toInt())
                }
                setTextColor(if (selected) 0xff8accc1.toInt() else 0xffb8b8b8.toInt())
                setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
                contentDescription = "Playlist ${pl.name}"
                setOnClickListener {
                    if (catalog.selectedPlaylistId != pl.id) {
                        catalog.selectedPlaylistId = pl.id
                        refresh()
                    } else {
                        showPlaylistOptions(pl)
                    }
                }
            }
            if (!pl.builtIn) {
                widgets.reorderable(ui, chip, "cpl:${pl.id}") { from, to ->
                    catalog.movePlaylist(from, to)
                    refresh()
                }
            }
            playlistStrip.addView(
                chip,
                LinearLayout.LayoutParams(-2, -2).apply { if (idx > 0) leftMargin = widgets.dp(6) }
            )
        }

        val selectedPlaylist = catalog.selectedPlaylist()
        val allChannels = catalog.channels(selectedPlaylist.id)
        statusView.text = catalog.playlistStatusSummary(selectedPlaylist.id)

        val shouldShowFilter = allChannels.size >= 6 || filterQuery.isNotEmpty()
        if (shouldShowFilter) {
            filterContainer.visibility = View.VISIBLE
            if (filterBuiltForPlaylistId != selectedPlaylist.id) {
                filterContainer.removeAllViews()
                filterContainer.addView(
                    widgets.filterBar("Buscar canal em ${selectedPlaylist.name}…", filterQuery) { nextQuery ->
                        filterQuery = nextQuery.trim()
                        renderChannelCards(selectedPlaylist, catalog.channels(selectedPlaylist.id))
                    }
                )
                filterBuiltForPlaylistId = selectedPlaylist.id
            }
        } else {
            filterContainer.visibility = View.GONE
            filterBuiltForPlaylistId = null
        }

        renderChannelCards(selectedPlaylist, allChannels)
    }

    private fun renderChannelCards(selectedPlaylist: ChannelPlaylist, allChannels: List<LiveChannel>) {
        channelList.removeAllViews()
        if (allChannels.isEmpty()) {
            val hint = if (selectedPlaylist.sources.isNotEmpty()) {
                if (selectedPlaylist.autoEnabled) {
                    "Extraindo e validando canais ao vivo de ${selectedPlaylist.name}… Os canais válidos aparecem automaticamente."
                } else {
                    "A busca automática desta playlist está pausada. Toque na engrenagem da playlist para retomar ou adicione uma fonte."
                }
            } else {
                "Esta playlist ainda não possui fontes. Toque em “Adicionar fonte de canais” para incluir um template {id}, lista M3U ou stream."
            }
            channelList.addView(widgets.muted(hint, 12.5f).apply { setPadding(0, widgets.dp(12), 0, widgets.dp(12)) })
            if (selectedPlaylist.sources.isEmpty()) {
                channelList.addView(widgets.button("Adicionar fonte nesta playlist") {
                    promptAddSource(preselectedPlaylistId = selectedPlaylist.id)
                }, LinearLayout.LayoutParams(-1, widgets.dp(48)))
            }
            return
        }

        val needle = filterQuery.lowercase()
        val visibleChannels = if (needle.isEmpty()) {
            allChannels
        } else {
            allChannels.filter { ch -> ch.name.lowercase().contains(needle) || ch.id.toString().contains(needle) }
        }

        if (visibleChannels.isEmpty()) {
            channelList.addView(
                widgets.muted("Nenhum canal encontrado para “$filterQuery” nesta playlist.", 12.5f).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, widgets.dp(18), 0, widgets.dp(18))
                }
            )
            return
        }

        visibleChannels.forEach { channel ->
            val card = widgets.cardRow { onPlayChannel(channel) }
            val thumb = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = widgets.rounded(0xff262626.toInt(), 8f)
                clipToOutline = true
            }
            card.addView(thumb, LinearLayout.LayoutParams(widgets.dp(96), widgets.dp(54)).apply { rightMargin = widgets.dp(12) })
            bindChannelThumbnail(channel, thumb)
            card.addView(
                widgets.label(channel.name + if (channel.validationState == "pending") " · não verificado" else "", 15f, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            card.addView(TextView(activity).apply {
                text = if (channel.favoriteAt > 0) "★" else "☆"
                textSize = 20f
                gravity = Gravity.CENTER
                background = widgets.ripple(0x00000000, 24f)
                setTextColor(if (channel.favoriteAt > 0) 0xffe8c26a.toInt() else 0xffa0a0a0.toInt())
                contentDescription = "Favoritar canal ${channel.name}"
                setOnClickListener { catalog.favorite(channel); refresh() }
            }, LinearLayout.LayoutParams(widgets.dp(48), widgets.dp(48)))
            card.addView(TextView(activity).apply {
                text = "×"
                textSize = 22f
                gravity = Gravity.CENTER
                background = widgets.ripple(0x00000000, 24f)
                setTextColor(0xffa0a0a0.toInt())
                contentDescription = "Remover canal ${channel.name}"
                setOnClickListener {
                    if (channel.favoriteAt > 0) {
                        confirmAction("Remover o canal favorito “${channel.name}”?", "Cancelar", "Remover") {
                            catalog.remove(channel)
                            refresh()
                        }
                    } else {
                        catalog.remove(channel)
                        refresh()
                    }
                }
            }, LinearLayout.LayoutParams(widgets.dp(48), widgets.dp(48)))
            channelList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = widgets.dp(8) })
        }
    }

    /** Standalone "Criar playlist" dialog in the Canais tab. */
    fun promptCreatePlaylist() {
        openSheet("Criar playlist de canais", 0.76f, {}) { body ->
            body.addView(widgets.muted("Organize seus canais e fontes em playlists separadas. A playlist principal começa vazia: adicione fontes que você tem autorização para usar.", 12f))
            val nameField = widgets.textInput("Nome da playlist (ex.: Filmes, Esportes, Casa)", "")
            val optionalSource = widgets.input("Fonte opcional ({id}, canal exemplo, M3U ou stream)", "")
            body.addView(nameField, LinearLayout.LayoutParams(-1, widgets.dp(48)).apply { bottomMargin = widgets.dp(8) })
            body.addView(optionalSource, LinearLayout.LayoutParams(-1, widgets.dp(48)).apply { bottomMargin = widgets.dp(4) })
            body.addView(widgets.urlQuickActionsRow(optionalSource), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = widgets.dp(8) })
            val action = widgets.button("Criar playlist") {}
            val submit = submitBlock@{
                val cleanName = nameField.text.toString().trim()
                val rawSource = optionalSource.text.toString().trim()
                if (cleanName.isBlank()) {
                    showMessage("Playlist", "Informe um nome para a playlist.")
                    return@submitBlock
                }
                if (rawSource.isNotBlank() && ChannelDiscoverySource.parse(rawSource) == null && !StreamResolver.httpUrl(rawSource)) {
                    showMessage("Fonte inválida", "Informe uma URL HTTP(S) válida, lista M3U ou template HTTPS com {id}.")
                    return@submitBlock
                }
                val created = catalog.createPlaylist(cleanName)
                if (created == null) {
                    showMessage("Playlist", "Limite de ${ChannelCatalog.MAX_PLAYLISTS} playlists atingido.")
                    return@submitBlock
                }
                if (rawSource.isBlank()) {
                    closeSheet()
                    refresh()
                } else {
                    attachSourceToPlaylist(created.id, rawSource, "", emptyMap(), action)
                }
            }
            action.setOnClickListener { submit() }
            widgets.onImeDone(nameField) { if (optionalSource.text.isNullOrBlank()) submit() else optionalSource.requestFocus() }
            widgets.onImeDone(optionalSource, submit)
            body.addView(action, LinearLayout.LayoutParams(-1, widgets.dp(48)))
            widgets.focusAndShowKeyboard(nameField)
        }
    }

    /**
     * Smart "Adicionar fonte de canais":
     *  - Validates URL/template/M3U/stream + optional headers.
     *  - If [preselectedPlaylistId] is null:
     *    - No user playlists yet -> asks the user to create their first playlist for this source.
     *    - 1+ user playlists -> asks whether to add to an existing user playlist or create a new one.
     */
    fun promptAddSource(preselectedPlaylistId: String? = null) {
        openSheet("Adicionar fonte de canais", 0.85f, {}) { body ->
            body.addView(
                widgets.muted(
                    "Cole um template com {id}, o link de um canal de exemplo (ex.: network_id=3021), uma lista M3U ou um stream direto.",
                    12f
                )
            )
            val address = widgets.input("URL da fonte, lista M3U ou stream HTTP(S)", "")
            val customName = widgets.textInput("Nome opcional (para canal único)", "")
            val headersField = widgets.input("Cabeçalhos opcionais · Nome: valor", "").apply {
                isSingleLine = false
                minLines = 2
                visibility = View.GONE
            }
            body.addView(address, LinearLayout.LayoutParams(-1, widgets.dp(48)).apply { bottomMargin = widgets.dp(4) })
            body.addView(widgets.urlQuickActionsRow(address), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = widgets.dp(8) })
            body.addView(customName, LinearLayout.LayoutParams(-1, widgets.dp(48)).apply { bottomMargin = widgets.dp(6) })
            body.addView(widgets.button("Opções avançadas") {
                headersField.visibility = if (headersField.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            })
            body.addView(headersField)

            val continueLabel = if (preselectedPlaylistId != null) "Adicionar" else "Continuar"
            val action = widgets.button(continueLabel) {}
            val submit = submitBlock@{
                val raw = address.text.toString().trim()
                val title = customName.text.toString().trim()
                val allowed = runCatching { StreamResolver.allowedHeaders(headersField.text.toString()) }
                if (allowed.isFailure) {
                    showMessage("Fonte de canais", allowed.exceptionOrNull()?.message ?: "Cabeçalhos inválidos.")
                    return@submitBlock
                }
                val headers = allowed.getOrThrow()
                val discoveryCandidate = ChannelDiscoverySource.parse(raw)
                if (discoveryCandidate == null && !StreamResolver.httpUrl(raw)) {
                    showMessage("Fonte inválida", "Informe uma URL HTTP(S) válida, lista M3U ou um template HTTPS com {id} / ID numérico.")
                    return@submitBlock
                }
                if (isPlaybackBusy()) {
                    showMessage("Fonte de canais", "Encerre a reprodução atual antes de adicionar e verificar novos canais.")
                    return@submitBlock
                }
                if (preselectedPlaylistId != null) {
                    attachSourceToPlaylist(preselectedPlaylistId, raw, title, headers, action)
                    return@submitBlock
                }
                val existingUserPlaylists = catalog.userPlaylists()
                if (existingUserPlaylists.isEmpty()) {
                    promptCreatePlaylistForSource(raw, title, headers)
                } else {
                    promptChoosePlaylistForSource(raw, title, headers, existingUserPlaylists)
                }
            }
            action.setOnClickListener { submit() }
            widgets.onImeDone(address, submit)
            widgets.onImeDone(customName, submit)
            body.addView(action, LinearLayout.LayoutParams(-1, widgets.dp(48)).apply { topMargin = widgets.dp(6) })
            widgets.focusAndShowKeyboard(address)
        }
    }

    /** Shown when the user adds a source and has NO user-created playlists yet. */
    private fun promptCreatePlaylistForSource(raw: String, title: String, headers: Map<String, String>) {
        openSheet("Criar playlist", 0.68f, {}) { body ->
            body.addView(
                widgets.muted(
                    "Você ainda não tem playlists criadas. Dê um nome para criar sua playlist e salvar esta fonte de canais:",
                    12.5f
                )
            )
            val nameField = widgets.textInput("Nome da playlist", "")
            body.addView(nameField, LinearLayout.LayoutParams(-1, widgets.dp(48)).apply { topMargin = widgets.dp(6); bottomMargin = widgets.dp(8) })
            val saveBtn = widgets.button("Criar playlist e adicionar fonte") {}
            val submit = submitBlock@{
                val cleanName = nameField.text.toString().trim()
                if (cleanName.isBlank()) {
                    showMessage("Playlist", "Informe um nome para criar a playlist.")
                    return@submitBlock
                }
                val created = catalog.createPlaylist(cleanName)
                if (created == null) {
                    showMessage("Playlist", "Limite de ${ChannelCatalog.MAX_PLAYLISTS} playlists atingido.")
                    return@submitBlock
                }
                attachSourceToPlaylist(created.id, raw, title, headers, saveBtn)
            }
            saveBtn.setOnClickListener { submit() }
            widgets.onImeDone(nameField, submit)
            body.addView(saveBtn, LinearLayout.LayoutParams(-1, widgets.dp(48)))
            widgets.focusAndShowKeyboard(nameField)
        }
    }

    /** Shown when the user adds a source and ALREADY HAS 1+ user-created playlists. */
    private fun promptChoosePlaylistForSource(
        raw: String,
        title: String,
        headers: Map<String, String>,
        userPlaylists: List<ChannelPlaylist>
    ) {
        val countsByPlaylist = catalog.channelCountsByPlaylist()
        openSheet("Onde adicionar esta fonte?", 0.76f, {}) { body ->
            body.addView(
                widgets.muted(
                    "Deseja adicionar esta nova fonte a uma playlist existente ou criar uma nova playlist?",
                    12.5f
                )
            )
            userPlaylists.forEach { pl ->
                val count = countsByPlaylist[pl.id] ?: 0
                val btn = widgets.button("${pl.name} · $count canais") {}
                btn.setOnClickListener {
                    attachSourceToPlaylist(pl.id, raw, title, headers, btn)
                }
                body.addView(btn, LinearLayout.LayoutParams(-1, widgets.dp(48)))
            }
            body.addView(
                widgets.button("＋ Criar nova playlist") {
                    promptCreatePlaylistForSource(raw, title, headers)
                },
                LinearLayout.LayoutParams(-1, widgets.dp(48)).apply { topMargin = widgets.dp(8) }
            )
        }
    }

    private fun attachSourceToPlaylist(
        playlistId: String,
        raw: String,
        title: String,
        headers: Map<String, String>,
        triggerButton: android.widget.Button
    ) {
        catalog.selectedPlaylistId = playlistId
        val discovery = if (headers.isEmpty() && title.isBlank()) ChannelDiscoverySource.parse(raw) else null
        val directDiscoveryTemplate = discovery != null && (
            raw.contains("{id}") ||
            ChannelDiscoverySource.isOtteraHost(raw)
        )
        if (directDiscoveryTemplate) {
            if (catalog.configureSource(playlistId, raw)) {
                closeSheet()
                refresh()
            } else {
                showMessage("Fonte inválida", "Não foi possível configurar esta fonte automática.")
            }
            return
        }

        triggerButton.isEnabled = false
        val previousLabel = triggerButton.text.toString()
        triggerButton.text = "Verificando…"
        streamWorker.execute {
            val resolvedResult = runCatching { resolveStream(raw, headers) }
            val stream = resolvedResult.getOrNull()
            var added = 0
            var configuredAuto = false
            if (stream != null && stream.kind == StreamKind.CATALOG) {
                stream.entries.forEach { entry ->
                    if (catalog.addManual(entry.name, entry.url, "", headers, false, playlistId)) added++
                }
            } else if (discovery != null && (stream == null || stream.kind == StreamKind.HLS)) {
                configuredAuto = catalog.configureSource(playlistId, raw)
            } else if (stream != null && stream.kind != StreamKind.UNSUPPORTED) {
                if (catalog.addManual(title, raw, stream.kind.name, headers, stream.mediaValidated, playlistId)) added++
            }
            ui.post {
                if (activity.isDestroyed || activity.isFinishing) return@post
                triggerButton.isEnabled = true
                triggerButton.text = previousLabel
                if (configuredAuto || added > 0) {
                    closeSheet()
                    refresh()
                } else {
                    showMessage(
                        "Fonte de canais",
                        stream?.issue ?: resolvedResult.exceptionOrNull()?.message ?: "Canal já cadastrado nesta playlist ou catálogo cheio."
                    )
                }
            }
        }
    }

    private fun showPlaylistOptions(playlist: ChannelPlaylist) {
        openSheet("Playlist · ${playlist.name}", 0.8f, { sheetStatusView = null }) { body ->
            val summary = widgets.muted(catalog.playlistStatusSummary(playlist.id), 12f).apply {
                setPadding(0, widgets.dp(4), 0, widgets.dp(8))
            }
            sheetStatusView = summary
            body.addView(summary)
            body.addView(widgets.button("Adicionar fonte nesta playlist") {
                promptAddSource(preselectedPlaylistId = playlist.id)
            })
            if (playlist.sources.isNotEmpty()) {
                val toggleText = if (playlist.autoEnabled) "Pausar busca automática" else "Retomar busca automática"
                body.addView(widgets.button(toggleText) {
                    catalog.setPlaylistAutoEnabled(playlist.id, !playlist.autoEnabled)
                    closeSheet()
                    refresh()
                })
                body.addView(widgets.button("Validar canais salvos") {
                    catalog.revalidateAll(playlist.id)
                    sheetStatusView?.text = catalog.playlistStatusSummary(playlist.id)
                })
            }
            if (!playlist.builtIn) {
                playlist.sources.forEach { src ->
                    val shortSrc = if (src.length > 44) src.take(22) + "…" + src.takeLast(18) else src
                    body.addView(widgets.button("Remover fonte: $shortSrc") {
                        catalog.removeSourceFromPlaylist(playlist.id, src)
                        closeSheet()
                        refresh()
                    })
                }
                body.addView(widgets.button("Renomear playlist") {
                    promptRenamePlaylist(playlist)
                })
                body.addView(widgets.button("Excluir playlist") {
                    confirmAction("Excluir a playlist “${playlist.name}” e seus canais?", "Cancelar", "Excluir") {
                        catalog.deletePlaylist(playlist.id)
                        refresh()
                    }
                })
            } else {
                body.addView(
                    widgets.muted(
                        "A playlist principal está vazia. Os canais só são adicionados pelas fontes que você configurar.",
                        11.5f
                    )
                )
            }
        }
    }

    private fun promptRenamePlaylist(playlist: ChannelPlaylist) {
        openSheet("Renomear playlist", 0.6f, {}) { body ->
            val field = widgets.textInput("Nome da playlist", playlist.name)
            body.addView(field, LinearLayout.LayoutParams(-1, widgets.dp(48)).apply { bottomMargin = widgets.dp(8) })
            val submit = submitBlock@{
                val clean = field.text.toString().trim()
                if (clean.isBlank()) {
                    showMessage("Playlist", "Informe um nome válido.")
                    return@submitBlock
                }
                catalog.renamePlaylist(playlist.id, clean)
                closeSheet()
                refresh()
            }
            body.addView(widgets.button("Salvar") { submit() })
            widgets.onImeDone(field, submit)
            widgets.focusAndShowKeyboard(field)
        }
    }

    private fun bindChannelThumbnail(channel: LiveChannel, image: ImageView) {
        image.tag = channel.url
        // Fast path: bind already-cached thumbnail synchronously with zero icon flicker or PreDraw overhead.
        val cached = thumbnailer.peek(channel.url)
        if (cached != null) {
            image.setImageBitmap(cached)
            return
        }
        image.setImageResource(R.drawable.ic_play)
        var loading = false
        var complete = false
        var retryAt = 0L
        val listener = ViewTreeObserver.OnPreDrawListener {
            if (!complete && !loading && isChannelTabActive() && !isPlaybackBusy() && !isDebugOrBackground() &&
                SystemClock.elapsedRealtime() >= retryAt && image.getGlobalVisibleRect(Rect())) {
                loading = true
                val done: (Bitmap?) -> Unit = { frame ->
                    loading = false
                    retryAt = SystemClock.elapsedRealtime() + 60_000
                    if (!activity.isDestroyed && image.tag == channel.url && frame != null) {
                        image.setImageBitmap(frame)
                        complete = true
                    }
                }
                if (channel.kind.isNotBlank()) {
                    thumbnailer.load(channel.url, channel.headers, channel.kind, done)
                } else {
                    streamWorker.execute {
                        val resolved = runCatching { resolveStream(channel.url, channel.headers) }.getOrNull()
                        ui.post {
                            if (activity.isDestroyed || isPlaybackBusy() || !isChannelTabActive() || resolved == null || resolved.kind == StreamKind.UNSUPPORTED) {
                                done(null)
                            } else {
                                thumbnailer.load(resolved.url, channel.headers, resolved.kind.name, done)
                            }
                        }
                    }
                }
            }
            true
        }
        image.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.viewTreeObserver.addOnPreDrawListener(listener)
            }
            override fun onViewDetachedFromWindow(view: View) {
                if (view.viewTreeObserver.isAlive) view.viewTreeObserver.removeOnPreDrawListener(listener)
            }
        })
    }
}

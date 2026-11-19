package com.aurareels.ocrlab

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.EditText
import android.text.InputType
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.media3.ui.PlayerView
import java.text.DateFormat
import java.util.Date

/**
 * Modular controller for in-app dark sheets: General Settings, Player Gear Sheet, Subtitle Style &
 * Experimental Burned-In Concealment Mask, Offline/ASR/OCR Model Manager, Translation Vault and Link History.
 */
class SubtitleSettingsSheet(
    private val activity: Activity,
    private val ui: Handler,
    private val widgets: UiWidgets,
    private val prefs: AppPrefs,
    private val models: ModelRepository,
    private val translations: GoogleTranslateBridge,
    private val speech: SpeechBridge,
    private val history: LinkHistory,
    private val openSheet: (title: String, maxFraction: Float, onClose: () -> Unit, fill: (LinearLayout) -> Unit) -> View,
    private val closeSheet: () -> Unit,
    private val showMessage: (String, String) -> Unit,
    private val currentSheet: () -> View?,
    private val isStarted: () -> Boolean,
    private val hasMainPlayer: () -> Boolean,
    private val mainPlayerView: () -> PlayerView?,
    private val applyDebug: () -> Unit,
    private val applySubtitleStyle: () -> Unit,
    private val resetShownCaptionKey: () -> Unit,
    private val showCaption: (String) -> Unit,
    private val applyMask: () -> Unit,
    private val resetLetterboxZoom: () -> Unit,
    private val scrollVideoIntoView: () -> Unit,
    private val refreshModelsSummary: () -> Unit,
    private val onRequestModelArchiveImport: (String) -> Unit,
    private val onPlayHistoryEntry: (LinkHistory.Entry) -> Unit,
    private val log: (String) -> Unit
) {

    fun showSettings() {
        openSheet("Ajustes", 0.66f, {}) { body ->
            body.addView(widgets.toggle("Modo debug", prefs.debugMode) { prefs.debugMode = it; applyDebug() })
            widgets.sliderRow(body, "Atraso do vídeo principal (ASR)", 8, 90, prefs.delaySeconds, { "$it s" }) { prefs.delaySeconds = it }
            body.addView(widgets.muted("Quanto o vídeo visível fica atrás do reconhecimento. Aumente para idiomas muito rápidos ou aparelhos lentos."))
            body.addView(widgets.toggle("Preferir Whisper Small instalado (próxima reprodução)", prefs.preferWhisperSmall) { prefs.preferWhisperSmall = it })
            body.addView(widgets.toggle("Suavizar pausas", prefs.smoothPauses) { prefs.smoothPauses = it })
            body.addView(widgets.label("Risadas", 12f, true))
            body.addView(widgets.segmented(listOf("Ocultar", "(risos)", "Original"), prefs.laughterMode) { prefs.laughterMode = it; speech.laughterMode = it })
            body.addView(widgets.button("COFRE DE TRADUÇÃO") { showVault() })
            body.addView(widgets.button("SERVIDOR DE TRADUÇÃO · API COMPATÍVEL") { showCompatibleApiSettings() })
            body.addView(widgets.label("Filtro de ruído (vale na próxima reprodução)", 12f, true).apply { setPadding(0, widgets.dp(14), 0, widgets.dp(6)) })
            body.addView(widgets.segmented(listOf("Permissivo", "Normal", "Rigoroso"), prefs.noiseLevel) { prefs.noiseLevel = it; speech.strictness = it })
            body.addView(widgets.muted("Permissivo: mais legendas, pode pegar ruído. Rigoroso: ignora falas curtas e incertas."))
            body.addView(
                widgets.button("LEGENDAS · POSIÇÃO, TAMANHO, FUNDO") { showSubtitleSettings() },
                LinearLayout.LayoutParams(-1, widgets.dp(48)).apply { topMargin = widgets.dp(14) }
            )
        }
    }

    fun showCompatibleApiSettings() {
        openSheet("Servidor de tradução", 0.74f, {}) { body ->
            body.addView(widgets.muted("Opcional: use um gateway HTTPS com formato Chat Completions. A tradução local continua disponível quando o servidor não está configurado."))
            fun field(label: String, value: String, secret: Boolean = false): EditText {
                body.addView(widgets.label(label, 12f, true))
                return EditText(activity).apply {
                    setSingleLine(true)
                    setText(value)
                    if(secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    body.addView(this, LinearLayout.LayoutParams(-1, widgets.dp(50)))
                }
            }
            val endpoint=field("Endpoint HTTPS (/v1/chat/completions)", prefs.translationEndpoint)
            val model=field("Model ID (provided by your server)",prefs.translationModel)
            val token=field("Optional session-only gateway token (not saved)","",true)
            body.addView(widgets.muted("Use an access-limited gateway token. Never enter a provider's master API key in a mobile client."))
            body.addView(widgets.button("SALVAR CONFIGURAÇÃO") {
                val cfg=CompatibleApiConfig(endpoint.text.toString().trim(),model.text.toString().trim(),token.text.toString())
                val valid=runCatching { if(cfg.endpoint.isNotBlank() || cfg.model.isNotBlank()) cfg.validated() }.exceptionOrNull()
                if(valid!=null) {
                    showMessage("Servidor inválido",valid.message.orEmpty())
                } else {
                    prefs.translationEndpoint=cfg.endpoint
                    prefs.translationModel=cfg.model
                    ApiRuntimeCredentials.sessionToken=cfg.sessionToken
                    translations.newSession(false)
                    closeSheet()
                }
            })
        }
    }

    fun showPlayerSettings() {
        openSheet("Player", 0.66f, {}) { body ->
            body.addView(widgets.toggle("Zoom automático em faixas pretas", prefs.autoZoomBars) {
                prefs.autoZoomBars = it
                if (!it) resetLetterboxZoom()
            })
            body.addView(widgets.muted("Se o vídeo 16:9 tiver faixas pretas em cima e embaixo, o player amplia a imagem. Acompanha o vídeo: desfaz o zoom quando as faixas somem."))
            body.addView(widgets.toggle("Modo debug", prefs.debugMode) { prefs.debugMode = it; applyDebug() })
            body.addView(widgets.muted("Abre uma tela própria com vídeo, status e eventos ao sair da tela cheia. Desligado por padrão."))
            body.addView(
                widgets.button("LEGENDAS") { showSubtitleSettings() },
                LinearLayout.LayoutParams(-1, widgets.dp(48)).apply { topMargin = widgets.dp(10) }
            )
        }
    }

    fun showSubtitleSettings() {
        openSheet("Legendas", 0.5f, { if (!isStarted()) showCaption("") }) { body ->
            body.addView(widgets.muted("Editando vídeos ${if (prefs.vertical) "VERTICAIS" else "HORIZONTAIS"}. Cada orientação guarda a sua própria configuração.", 12f))
            val sample: () -> Unit = { if (!isStarted()) showCaption("Exemplo de legenda traduzida\nsegunda linha para testar a posição") }
            widgets.sliderRow(body, "Tamanho do texto", 12, 34, prefs.textSizeSp, { "$it sp" }) {
                prefs.textSizeSp = it; applySubtitleStyle(); sample()
            }
            widgets.sliderRow(body, "Fundo da legenda", 0, 100, prefs.backgroundPercent, { if (it == 0) "sem fundo (contorno)" else "$it%" }) {
                prefs.backgroundPercent = it; applySubtitleStyle(); sample()
            }
            widgets.sliderRow(body, "Posição da legenda", 50, 98, prefs.positionPercent, {
                when { it >= 92 -> "bem embaixo"; it >= 80 -> "embaixo"; it >= 65 -> "meio-baixo"; else -> "meio/alto" }
            }) {
                prefs.positionPercent = it; resetShownCaptionKey(); sample()
            }
            body.addView(widgets.toggle("Legendas superiores (texto original)", prefs.showOriginal) { prefs.showOriginal = it })
            body.addView(widgets.muted("Mostra, acima do vídeo, a fala no idioma original junto da tradução. Desligado por padrão para você se imergir na legenda traduzida."))
            body.addView(widgets.label("REMOÇÃO DA LEGENDA ORIGINAL · EXPERIMENTAL", 11f, true).apply {
                setPadding(0, widgets.dp(16), 0, widgets.dp(2)); setTextColor(0xffe8c26a.toInt())
            })
            body.addView(widgets.toggle("Ativar máscara", prefs.maskEnabled) { prefs.maskEnabled = it; applyMask() })
            body.addView(widgets.button("AJUSTAR MÁSCARA") { showMaskEditor() }, LinearLayout.LayoutParams(-1, widgets.dp(48)))
        }
        scrollVideoIntoView()
        if (!isStarted()) showCaption("Exemplo de legenda traduzida\nsegunda linha para testar a posição")
    }

    fun showMaskEditor() {
        val tex = mainPlayerView()?.videoSurfaceView as? TextureView
        val frame = if (tex != null && tex.isAvailable && tex.width > 0 && tex.height > 0 && hasMainPlayer()) {
            tex.getBitmap(480, (480f * tex.height / tex.width).toInt().coerceIn(48, 960))
        } else null
        if (frame == null) {
            showMessage("Máscara de legenda", "Reproduza um vídeo e abra de novo: o frame com a legenda é capturado do player.")
            return
        }
        openSheet("Máscara · experimental", 0.86f, { applyMask() }) { body ->
            body.addView(widgets.muted("Frame capturado do vídeo ${if (prefs.vertical) "vertical" else "horizontal"}. Arraste na imagem ou use os controles para cobrir a legenda original.", 12f))
            var positionBar: SeekBar? = null
            val preview = MaskPreviewView(activity, frame, prefs.maskCenter, prefs.maskHeight) { c -> positionBar?.progress = c - 5 }
            body.addView(preview, LinearLayout.LayoutParams(-1, -2).apply { topMargin = widgets.dp(8) })
            positionBar = widgets.sliderRow(body, "Posição da faixa", 5, 98, prefs.maskCenter, { "$it%" }) {
                prefs.maskCenter = it; preview.centerPercent = it; preview.invalidate(); applyMask()
            }
            widgets.sliderRow(body, "Altura da faixa", 3, 30, prefs.maskHeight, { "$it%" }) {
                prefs.maskHeight = it; preview.heightPercent = it; preview.invalidate(); applyMask()
            }
            body.addView(widgets.toggle("Ativar máscara no vídeo", prefs.maskEnabled) { prefs.maskEnabled = it; applyMask() })
            body.addView(widgets.muted("A faixa reconstrói o fundo a partir do que há logo acima e abaixo dela. Funciona melhor em fundos suaves; em texturas detalhadas sobra um borrão."))
        }
    }

    fun showLanguageModels() {
        openSheet("Modelos por idioma", 0.66f, {}) { body ->
            for (entry in LanguageCatalog.entries) {
                body.addView(widgets.button(entry.namePt + if (models.asrBackendFor(entry.code) != null) " · pronto" else " · baixar") {
                    translations.prewarmOffline(entry.code, "pt")
                    if (models.asrBackendFor(entry.code) != null) {
                        log("${entry.namePt}: ASR disponível · modelo de tradução offline aquecido")
                        return@button
                    }
                    val model = "whisper" // Audit-friendly default; restricted models may be imported separately
                    fun download(id: String) {
                        if (!models.installed(id)) models.install(LabModels.byId(id), log) { _, msg -> log(msg); refreshModelsSummary() }
                    }
                    download("vad")
                    download(model)
                    if (entry.code == "tr") log("Turco usa Whisper + VAD. Vosk turco permanece disponível como alternativa em Modelos.")
                })
            }
        }
    }

    fun showVault() {
        openSheet("Cofre de tradução", 0.66f, {}) { body ->
            body.addView(widgets.muted("Tradução atual: ${translations.state}", 12f))
            body.addView(widgets.toggle("Usar cofre local", prefs.vaultEnabled) { prefs.vaultEnabled = it; translations.clearCache() })
            val summary = widgets.label("Contando entradas…", 12f)
            body.addView(summary)
            translations.vault.counts { counts ->
                ui.post {
                    summary.text = counts.entries.joinToString("\n") { "${LanguageMap.displayName(it.key)}: ${it.value}" }
                        .ifBlank { "Nenhuma frase promovida ainda" }
                    counts.keys.forEach { code ->
                        body.addView(widgets.button("LIMPAR · ${LanguageMap.displayName(code)}") {
                            translations.vault.clear(code) { translations.clearCache(); ui.post { showVault() } }
                        })
                    }
                }
            }
            body.addView(widgets.button("LIMPAR TUDO") {
                translations.vault.clear { translations.clearCache(); ui.post { showVault() } }
            })
            body.addView(widgets.button("EXPORTAR JSON") {
                activity.startActivityForResult(
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        type = "application/json"
                        addCategory(Intent.CATEGORY_OPENABLE)
                        putExtra(Intent.EXTRA_TITLE, "aura_cofre.json")
                    },
                    311
                )
            })
            body.addView(widgets.button("IMPORTAR JSON") {
                activity.startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "application/json"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    },
                    312
                )
            })
        }
    }

    fun showModels() {
        lateinit var bodyRef: LinearLayout
        val me = openSheet("Modelos", 0.66f, {}) { body -> bodyRef = body }
        val statusViews = HashMap<String, TextView>()
        var signature = ""

        fun stateLine(m: LabModel): String {
            val progress = models.operationStatus(m.id)
            return when {
                progress != null -> progress
                models.installed(m.id) -> "Instalado"
                models.lastError(m.id) != null -> "Falhou: ${models.lastError(m.id)}"
                else -> "Não instalado"
            }
        }
        fun paintState(view: TextView, m: LabModel) {
            view.text = stateLine(m)
            view.setTextColor(
                when {
                    models.isBusy(m.id) -> 0xffe8c26a.toInt()
                    models.installed(m.id) -> 0xff8accc1.toInt()
                    models.lastError(m.id) != null -> 0xffe08a8a.toInt()
                    else -> 0xff8a8a8a.toInt()
                }
            )
        }
        fun section(titleText: String, badge: String) {
            bodyRef.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, widgets.dp(16), 0, widgets.dp(4))
                addView(widgets.label(titleText, 11f, true).apply { letterSpacing = 0.08f })
                if (badge.isNotEmpty()) {
                    addView(
                        widgets.label(badge, 10f, true).apply {
                            setTextColor(0xff8accc1.toInt())
                            setPadding(widgets.dp(8), widgets.dp(2), widgets.dp(8), widgets.dp(2))
                            background = widgets.rounded(0xff1d2b29.toInt(), 8f)
                        },
                        LinearLayout.LayoutParams(-2, -2).apply { leftMargin = widgets.dp(8) }
                    )
                }
            })
        }
        fun row(m: LabModel) {
            if (m.id == "lid" && models.bundledLidAvailable()) return
            val busy = models.isBusy(m.id)
            val present = models.installed(m.id)
            val line = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, widgets.dp(8), 0, widgets.dp(8))
            }
            val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            column.addView(widgets.label(m.title, 12f, true))
            column.addView(widgets.muted(m.sizeHint))
            val state = widgets.label("", 12f)
            statusViews[m.id] = state
            paintState(state, m)
            column.addView(state)
            line.addView(column, LinearLayout.LayoutParams(0, -2, 2.3f))
            val action = widgets.button(
                when {
                    busy -> "CANCELAR"
                    present -> "EXCLUIR"
                    models.lastError(m.id) != null -> "TENTAR DE NOVO"
                    else -> "BAIXAR"
                }
            ) {
                when {
                    busy -> models.cancel(m.id)
                    present -> models.remove(m, isStarted) { ok, message ->
                        log(message); refreshModelsSummary()
                        if (!ok) showMessage("Modelos", message)
                    }
                    else -> models.install(m, log) { _, message ->
                        log(message); refreshModelsSummary()
                    }
                }
            }
            line.addView(action, LinearLayout.LayoutParams(0, widgets.dp(48), 1f))
            bodyRef.addView(line)
            if (!present && !busy) {
                bodyRef.addView(widgets.button("IMPORTAR ARQUIVO · ${m.title}") {
                    onRequestModelArchiveImport(m.id)
                    activity.startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                            type = "*/*"
                            addCategory(Intent.CATEGORY_OPENABLE)
                        },
                        313
                    )
                })
            }
            bodyRef.addView(View(activity).apply { setBackgroundColor(0xff262626.toInt()) }, LinearLayout.LayoutParams(-1, widgets.dp(1)))
        }
        fun rebuild() {
            bodyRef.removeAllViews()
            statusViews.clear()
            bodyRef.addView(widgets.muted("Baixe apenas o necessário. Vários modelos podem baixar ao mesmo tempo.", 12f))
            bodyRef.addView(widgets.button("BAIXAR POR IDIOMA") { showLanguageModels() })
            bodyRef.addView(widgets.button("IMPORTAR MANIFESTO DE ESPELHOS") {
                activity.startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        type = "application/json"
                        addCategory(Intent.CATEGORY_OPENABLE)
                    },
                    314
                )
            })
            section("FERRAMENTAS", "importantes")
            LabModels.all.filter { it.type == LabModel.Type.AUDIO_LID || it.type == LabModel.Type.VAD }.forEach { row(it) }
            section("VOZ PARA TEXTO", "melhores")
            LabModels.all.filter { it.type == LabModel.Type.SENSEVOICE || it.type == LabModel.Type.WHISPER }.forEach { row(it) }
            bodyRef.addView(widgets.muted("Alternativos · menos precisos em idiomas rápidos"))
            LabModels.all.filter { it.type == LabModel.Type.VOSK }.forEach { row(it) }
            section("IMAGEM PARA TEXTO", "")
            LabModels.all.filter { it.type == LabModel.Type.OCR }.forEach { row(it) }
        }
        fun currentSignature() = LabModels.all.joinToString("|") {
            "${it.id}:${models.isBusy(it.id)}:${models.installed(it.id)}:${models.lastError(it.id) != null}"
        }
        val poll = object : Runnable {
            override fun run() {
                if (currentSheet() !== me) return
                val now = currentSignature()
                if (now != signature) {
                    signature = now
                    rebuild()
                } else {
                    for (m in LabModels.all) statusViews[m.id]?.let { paintState(it, m) }
                }
                ui.postDelayed(this, 500)
            }
        }
        poll.run()
    }

    fun showHistory(watchedPredicate: (Pair<Long, Long>) -> Boolean, shortUrl: (String) -> String) {
        val entries = history.all()
        val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        openSheet("Histórico de links", 0.66f, {}) { body ->
            if (entries.isEmpty()) {
                body.addView(
                    widgets.label(
                        "Nenhum link reproduzido ainda. Os links aparecem aqui depois de tocar em REPRODUZIR.",
                        13f
                    ).apply { setPadding(0, widgets.dp(6), 0, widgets.dp(10)) }
                )
                return@openSheet
            }
            body.addView(widgets.muted("Toque no link para retomar a reprodução.", 12f))
            for (entry in entries) {
                val line = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, widgets.dp(6), 0, widgets.dp(6))
                }
                val text = widgets.label(
                    "${shortUrl(entry.url)}\n${format.format(Date(entry.lastUsed))}" +
                        (if (watchedPredicate(entry.positionMs to entry.durationMs)) " · assistido" else "") +
                        (if (entry.referer.isNotBlank()) " · com referer" else ""),
                    12f
                ).apply {
                    minimumHeight = widgets.dp(48)
                    gravity = Gravity.CENTER_VERTICAL
                    setOnClickListener {
                        closeSheet()
                        onPlayHistoryEntry(entry)
                    }
                }
                line.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                line.addView(
                    widgets.iconOnly(R.drawable.ic_copy) {
                        val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("link", entry.url))
                        Toast.makeText(activity, "Link copiado", Toast.LENGTH_SHORT).show()
                    }.apply { contentDescription = "Copiar link" },
                    LinearLayout.LayoutParams(widgets.dp(48), widgets.dp(48))
                )
                line.addView(
                    widgets.iconOnly(R.drawable.ic_trash) {
                        history.remove(entry.url)
                        showHistory(watchedPredicate, shortUrl)
                    }.apply { contentDescription = "Remover do histórico" },
                    LinearLayout.LayoutParams(widgets.dp(48), widgets.dp(48))
                )
                body.addView(line)
                body.addView(widgets.progressBar(entry.positionMs, entry.durationMs))
                body.addView(View(activity).apply { setBackgroundColor(0xff262626.toInt()) }, LinearLayout.LayoutParams(-1, widgets.dp(1)))
            }
            body.addView(
                widgets.button("LIMPAR TUDO") {
                    history.clear()
                    log("Histórico limpo")
                    closeSheet()
                },
                LinearLayout.LayoutParams(-1, widgets.dp(48)).apply { topMargin = widgets.dp(12) }
            )
        }
    }

    companion object {
        fun shortUrl(address: String): String {
            val uri = Uri.parse(address)
            val host = uri.host ?: address.take(30)
            val tail = (uri.path.orEmpty() + (uri.query?.let { "?$it" } ?: "")).takeLast(46)
            return if (tail.isEmpty()) host else "$host …$tail"
        }
    }
}

package com.aurareels.ocrlab

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/**
 * Native recycled subtitle package editor:
 *  - Shows both translated text (up to 2 lines) and original spoken source text on every row for instant context.
 *  - Highlights "Salvar" visually whenever draft edits or timing changes exist.
 *  - Provides an instant search/filter bar to jump directly to any cue in long episodes or movies.
 */
class SubtitleEditorPage(
    context: Context,
    private val pack: SubtitlePackageStore.Package,
    title: String,
    private val back: () -> Unit,
    private val save: (List<SubtitlePackageStore.Edit>, List<SubtitlePackageStore.Timing>, (Boolean) -> Unit) -> Unit,
    private val export: (String) -> Unit,
    private val edit: (SpeechCue, String, (String) -> Unit) -> Unit,
    private val editTime: (SpeechCue, Long, Long, (Long, Long) -> Unit) -> Unit,
    private val confirm: (String, String, String, () -> Unit) -> Unit
) : LinearLayout(context) {
    private val edits = LinkedHashMap<String, SubtitlePackageStore.Edit>()
    private val timings = LinkedHashMap<String, SubtitlePackageStore.Timing>()
    private fun startOf(cue: SpeechCue) = timings[cue.id]?.startMs ?: cue.startMs
    private fun endOf(cue: SpeechCue) = timings[cue.id]?.endMs ?: cue.endMs
    private fun dirty() = edits.isNotEmpty() || timings.isNotEmpty()
    private var cues = pack.cues
    private var saving = false
    private var searchQuery = ""
    private val state = TextView(context)
    private val saveButton: Button
    private val rows = ListView(context)
    private var allVisibleRows = emptyList<SpeechCue>()
    private var filteredRows = emptyList<SpeechCue>()
    private fun visible() = filteredRows

    private val adapter = object : BaseAdapter() {
        override fun getCount() = visible().size
        override fun getItem(position: Int) = visible()[position]
        override fun getItemId(position: Int) = getItem(position).id.hashCode().toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(12), dp(6), dp(4), dp(6))
                addView(TextView(context).apply {
                    setTextColor(0xffbdbdbd.toInt())
                    textSize = 12f
                    typeface = Typeface.MONOSPACE
                    setPadding(0, dp(12), 0, dp(12))
                    contentDescription = "Editar tempo da legenda"
                }, LayoutParams(dp(90), -2))
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    addView(TextView(context).apply {
                        setTextColor(Color.WHITE)
                        textSize = 14f
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(TextView(context).apply {
                        setTextColor(0xff8e8e8e.toInt())
                        textSize = 11.5f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(2), 0, 0)
                    })
                }, LayoutParams(0, -2, 1f))
                addView(ImageView(context).apply {
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(Color.WHITE)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    isClickable = true
                    isFocusable = true
                    background = RippleDrawable(
                        ColorStateList.valueOf(0x33ffffff),
                        null,
                        GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(24).toFloat() }
                    )
                    contentDescription = "Excluir legenda"
                }, LayoutParams(dp(48), dp(48)))
            }
            val cue = getItem(position)
            val editedText = edits[cue.id]?.text
            val timed = timings.containsKey(cue.id) || cue.humanTimed

            (row.getChildAt(0) as TextView).apply {
                text = time(startOf(cue)) + if (timed) " •" else ""
                setTextColor(if (timed) 0xff8accc1.toInt() else 0xffbdbdbd.toInt())
                setOnClickListener {
                    if (!saving) editTime(cue, startOf(cue), endOf(cue)) { start, end ->
                        timings[cue.id] = SubtitlePackageStore.Timing(cue.id, start, end)
                        refresh()
                    }
                }
            }
            val textColumn = row.getChildAt(1) as LinearLayout
            val primaryLine = textColumn.getChildAt(0) as TextView
            val sourceLine = textColumn.getChildAt(1) as TextView
            val shownTranslation = editedText ?: cue.output?.takeIf { it.isNotBlank() } ?: "Tradução em andamento…"
            primaryLine.text = shownTranslation
            primaryLine.setTextColor(if (editedText != null || cue.humanEdited) 0xff8accc1.toInt() else Color.WHITE)
            sourceLine.text = cue.source
            sourceLine.visibility = if (cue.source.isBlank() || cue.source == shownTranslation) View.GONE else View.VISIBLE
            textColumn.setOnClickListener {
                edit(cue, editedText ?: cue.output.orEmpty()) { value ->
                    if (!saving) {
                        edits[cue.id] = SubtitlePackageStore.Edit(cue.id, value, false)
                        refresh()
                    }
                }
            }
            row.getChildAt(2).setOnClickListener {
                if (!saving) confirm("Excluir esta legenda?", "Cancelar", "Excluir") {
                    edits[cue.id] = SubtitlePackageStore.Edit(cue.id, editedText ?: cue.output.orEmpty(), true)
                    refresh()
                }
            }
            return row
        }
    }

    init {
        orientation = VERTICAL
        val bar = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(8), dp(4))
        }
        bar.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(0x33ffffff),
                null,
                GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(24).toFloat() }
            )
            contentDescription = "Voltar"
            setOnClickListener { back() }
        }, LayoutParams(dp(48), dp(48)))
        bar.addView(TextView(context).apply {
            text = title
            textSize = 18f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(8), 0)
        }, LayoutParams(0, -2, 1f))
        saveButton = action("Salvar") {
            if (!saving && dirty()) {
                saving = true
                refresh()
                val submitted = edits.values.toList()
                val submittedTimings = timings.values.toList()
                save(submitted, submittedTimings) { ok ->
                    saving = false
                    if (ok) {
                        cues.forEach { cue ->
                            submitted.firstOrNull { it.cueId == cue.id }?.let { cue.applyHuman(it.text, it.deleted) }
                            submittedTimings.firstOrNull { it.cueId == cue.id }?.let { cue.applyTiming(it.startMs, it.endMs) }
                        }
                        submitted.forEach { if (edits[it.cueId] == it) edits.remove(it.cueId) }
                        submittedTimings.forEach { if (timings[it.cueId] == it) timings.remove(it.cueId) }
                    } else {
                        Toast.makeText(context, "Não foi possível salvar. O rascunho foi mantido.", Toast.LENGTH_LONG).show()
                    }
                    refresh()
                }
            }
        }
        bar.addView(saveButton, LayoutParams(-2, dp(48)))
        bar.addView(action("Exportar") { exportDraft() }, LayoutParams(-2, dp(48)).apply { leftMargin = dp(6) })
        addView(bar)

        val searchBar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(0xff161616.toInt())
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), 0xff262626.toInt())
            }
            setPadding(dp(12), 0, dp(4), 0)
        }
        val searchField = EditText(context).apply {
            hint = "Buscar fala traduzida ou trecho original…"
            setTextColor(Color.WHITE)
            setHintTextColor(0xff757575.toInt())
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
            background = null
            setPadding(0, dp(10), dp(8), dp(10))
        }
        val clearSearch = ImageView(context).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            setPadding(dp(11), dp(11), dp(11), dp(11))
            visibility = View.GONE
            contentDescription = "Limpar busca"
            setOnClickListener { searchField.setText("") }
        }
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim().orEmpty()
                clearSearch.visibility = if (searchQuery.isEmpty()) View.GONE else View.VISIBLE
                refresh()
            }
        })
        searchBar.addView(searchField, LayoutParams(0, dp(44), 1f))
        searchBar.addView(clearSearch, LayoutParams(dp(44), dp(44)))
        addView(searchBar, LayoutParams(-1, -2).apply { setMargins(dp(12), dp(2), dp(12), dp(4)) })

        state.apply {
            textSize = 12f
            setTextColor(0xff9e9e9e.toInt())
            setPadding(dp(16), dp(2), dp(16), dp(6))
        }
        addView(state)
        rows.adapter = adapter
        rows.dividerHeight = dp(1)
        addView(rows, LayoutParams(-1, 0, 1f))
        refresh()
    }

    /** Checkpoints update unedited IDs without changing scroll or overwriting the draft. */
    fun merge(snapshot: SubtitlePackageStore.Package) { cues = snapshot.cues; refresh() }

    fun requestClose(): Boolean {
        if (saving) return false
        if (!dirty()) return true
        confirm("Sair sem salvar? As alterações não serão salvas.", "Continuar editando", "Sair sem salvar") {
            edits.clear(); timings.clear(); back()
        }
        return false
    }

    private fun exportDraft() {
        // Export always exports the full non-deleted subtitle set, even when a search query is active on screen.
        val chosen = allVisibleRows
        val pending = chosen.any { cue -> edits[cue.id] == null && !TranslationCoverage.playable(cue, pack.language, "pt") }
        val emit = {
            val snapshot = chosen.mapNotNull { cue ->
                val text = edits[cue.id]?.text ?: cue.output?.takeIf { TranslationCoverage.playable(cue, pack.language, "pt") }
                text?.takeIf { it.isNotBlank() }?.let {
                    SpeechCue(startOf(cue), endOf(cue), cue.source).apply {
                        output = it
                        if (timings.containsKey(cue.id) || cue.humanTimed) applyTiming(startOf(cue), endOf(cue))
                    }
                }
            }
            export(SubtitleTimeline().exportSrt(snapshot.sortedBy { it.startMs }))
        }
        if (pending) confirm("Há traduções pendentes. Exportar somente as traduzidas?", "Aguardar", "Exportar traduzidas", emit)
        else emit()
    }

    private fun paintSaveButton() {
        val hasChanges = dirty() && !saving
        saveButton.isEnabled = hasChanges
        saveButton.alpha = if (hasChanges) 1f else 0.5f
        saveButton.setTextColor(if (hasChanges) 0xff8accc1.toInt() else Color.WHITE)
        saveButton.background = RippleDrawable(
            ColorStateList.valueOf(0x22ffffff),
            GradientDrawable().apply {
                setColor(if (hasChanges) 0xff1d2b29.toInt() else 0xff202020.toInt())
                cornerRadius = dp(12).toFloat()
                if (hasChanges) setStroke(dp(1), 0xff3DDC84.toInt())
            },
            null
        )
    }

    private fun refresh() {
        allVisibleRows = cues
            .filter { !(edits[it.id]?.deleted ?: it.suppressed) && it.reason?.startsWith("SUPPRESSED:") != true }
            .sortedBy { startOf(it) }
        val needle = searchQuery.lowercase()
        filteredRows = if (needle.isEmpty()) {
            allVisibleRows
        } else {
            allVisibleRows.filter { cue ->
                val text = (edits[cue.id]?.text ?: cue.output.orEmpty()).lowercase()
                text.contains(needle) || cue.source.lowercase().contains(needle)
            }
        }
        val untranslated = allVisibleRows.count { edits[it.id] == null && !TranslationCoverage.playable(it, pack.language, "pt") }
        val provisional = allVisibleRows.count { edits[it.id] == null && TranslationCoverage.playable(it, pack.language, "pt") && !TranslationCoverage.complete(it, pack.language, "pt") }
        val filterNote = if (needle.isNotEmpty()) " · exibindo ${filteredRows.size} de ${allVisibleRows.size}" else ""
        state.text = when {
            saving -> "Salvando…"
            dirty() -> "Alterações não salvas (${edits.size + timings.size})$filterNote"
            untranslated > 0 -> "Tradução em andamento · $untranslated pendentes$filterNote"
            provisional > 0 -> "${allVisibleRows.size} legendas · $provisional provisórias (aguardando IA)$filterNote"
            else -> "${allVisibleRows.size} legendas$filterNote"
        }
        paintSaveButton()
        adapter.notifyDataSetChanged()
    }

    private fun action(title: String, run: () -> Unit) = Button(context).apply {
        text = title; textSize = 12f; isAllCaps = false; setTextColor(Color.WHITE)
        minWidth = 0; minimumWidth = 0; minHeight = dp(48); stateListAnimator = null; elevation = 0f
        setPadding(dp(14), 0, dp(14), 0)
        background = RippleDrawable(
            ColorStateList.valueOf(0x22ffffff),
            GradientDrawable().apply { setColor(0xff202020.toInt()); cornerRadius = dp(12).toFloat() },
            null
        )
        setOnClickListener { run() }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun time(ms: Long) = "%02d:%02d:%02d.%d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000 / 100)
}

package com.aurareels.ocrlab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.DragEvent
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * Shared dark-theme UI widget factory used across [MainActivity] and its modular controllers.
 * Enforces accessible 48dp touch targets, natural IME keyboard modes, one-tap clipboard helpers,
 * instant filter bars, vector icons (no emojis) and custom dark surfaces.
 */
class UiWidgets(private val context: Context) {

    fun dp(value: Int): Int = (context.resources.displayMetrics.density * value).toInt()

    fun rounded(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp.toInt()).toFloat()
    }

    fun ripple(color: Int, radiusDp: Float, stroke: Int = 0): Drawable {
        val base = rounded(color, radiusDp).apply { if (stroke != 0) setStroke(dp(1), stroke) }
        return RippleDrawable(ColorStateList.valueOf(0x33ffffff), base, null)
    }

    fun label(text: String, size: Float, bold: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(Color.WHITE)
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
    }

    fun muted(text: String, size: Float = 12f): TextView = label(text, size).apply {
        setTextColor(0xffa0a0a0.toInt())
        setPadding(0, dp(4), 0, dp(4))
    }

    fun sectionLabel(text: String): TextView = label(text, 11f, true).apply {
        setTextColor(0xffa4a4a4.toInt())
        letterSpacing = 0.08f
        setPadding(0, dp(14), 0, dp(6))
    }

    /** URL / technical field (`TYPE_TEXT_VARIATION_URI`). */
    fun input(hint: String, initial: String): EditText = EditText(context).apply {
        this.hint = hint
        setText(initial)
        setTextColor(Color.WHITE)
        setHintTextColor(0xff757575.toInt())
        textSize = 13f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        imeOptions = EditorInfo.IME_ACTION_DONE
        isSingleLine = true
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = rounded(0xff171717.toInt(), 10f)
    }

    /** Natural human-language field (names, playlist titles, search queries — preserves spacebar & capitalization). */
    fun textInput(hint: String, initial: String): EditText = EditText(context).apply {
        this.hint = hint
        setText(initial)
        setTextColor(Color.WHITE)
        setHintTextColor(0xff757575.toInt())
        textSize = 13f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_ACTION_DONE
        isSingleLine = true
        setPadding(dp(12), dp(7), dp(12), dp(7))
        background = rounded(0xff171717.toInt(), 10f)
    }

    fun hideKeyboard(view: View?) {
        val token = view?.windowToken ?: return
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(token, 0)
    }

    fun focusAndShowKeyboard(field: EditText) {
        field.post {
            field.requestFocus()
            field.setSelection(field.text?.length ?: 0)
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun onImeDone(field: EditText, submit: () -> Unit) {
        field.setOnEditorActionListener { _, actionId, event ->
            val enterPressed = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || enterPressed) {
                hideKeyboard(field)
                submit()
                true
            } else false
        }
    }

    /** Wraps a URL [EditText] with one-tap "Colar" and "Limpar" buttons so mobile users avoid awkward long-press menus. */
    fun urlQuickActionsRow(target: EditText): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
        val pasteBtn = button("Colar") {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
            if (clip.isNotEmpty()) {
                target.setText(clip)
                target.setSelection(clip.length)
            } else {
                Toast.makeText(context, "Área de transferência vazia", Toast.LENGTH_SHORT).show()
            }
        }.apply { minHeight = dp(38); minimumHeight = dp(38); textSize = 11.5f; setPadding(dp(14), 0, dp(14), 0) }

        val clearBtn = button("Limpar") {
            target.setText("")
            target.requestFocus()
        }.apply { minHeight = dp(38); minimumHeight = dp(38); textSize = 11.5f; setPadding(dp(14), 0, dp(14), 0) }

        addView(pasteBtn, LinearLayout.LayoutParams(-2, dp(38)).apply { rightMargin = dp(6) })
        addView(clearBtn, LinearLayout.LayoutParams(-2, dp(38)))
    }

    /** Compact instant filter bar with an auto-hiding clear button (`×`). */
    fun filterBar(hint: String, initial: String, onChanged: (String) -> Unit): LinearLayout {
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xff161616.toInt(), 12f).apply { setStroke(dp(1), 0xff262626.toInt()) }
            setPadding(dp(12), 0, dp(4), 0)
        }
        val field = EditText(context).apply {
            this.hint = hint
            setText(initial)
            setTextColor(Color.WHITE)
            setHintTextColor(0xff757575.toInt())
            textSize = 13f
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            isSingleLine = true
            background = null
            setPadding(0, dp(10), dp(8), dp(10))
        }
        val clear = iconOnly(R.drawable.ic_close) {
            field.setText("")
            hideKeyboard(field)
        }.apply {
            contentDescription = "Limpar busca"
            visibility = if (initial.isEmpty()) View.GONE else View.VISIBLE
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString().orEmpty()
                clear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
                onChanged(query)
            }
        })
        onImeDone(field) { hideKeyboard(field) }
        bar.addView(field, LinearLayout.LayoutParams(0, dp(46), 1f))
        bar.addView(clear, LinearLayout.LayoutParams(dp(44), dp(44)))
        return bar
    }

    fun iconButton(icon: Int, title: String, click: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ripple(0xff202020.toInt(), 12f)
            isClickable = true
            isFocusable = true
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { click() }
        }
        row.addView(
            ImageView(context).apply { setImageResource(icon); setColorFilter(Color.WHITE) },
            LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(8) }
        )
        row.addView(
            TextView(context).apply {
                text = title
                textSize = 12f
                setTextColor(Color.WHITE)
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            }
        )
        return row
    }

    fun iconOnly(icon: Int, click: () -> Unit): ImageView = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        setPadding(dp(11), dp(11), dp(11), dp(11))
        isClickable = true
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(0x33ffffff), null, rounded(Color.WHITE, 24f))
        setOnClickListener { click() }
    }

    fun button(title: String, click: () -> Unit): Button = Button(context).apply {
        text = title
        textSize = 12f
        isAllCaps = false
        stateListAnimator = null
        elevation = 0f
        setTextColor(Color.WHITE)
        background = ripple(0xff202020.toInt(), 12f)
        minHeight = dp(46)
        minimumHeight = dp(46)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(4)) }
        setOnClickListener { click() }
    }

    fun cardRow(onClick: () -> Unit): LinearLayout = LinearLayout(context).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = ripple(0xff141414.toInt(), 14f, 0xff262626.toInt())
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun progressBar(position: Long, duration: Long): ProgressBar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = if (duration > 0) (position.toDouble() / duration * 1000).toInt().coerceIn(0, 1000) else 0
            progressTintList = ColorStateList.valueOf(0xff3ddc84.toInt())
            contentDescription = "Assistido: ${progress / 10}%"
            layoutParams = LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(5); bottomMargin = dp(5) }
        }

    fun sliderRow(
        body: LinearLayout,
        title: String,
        lo: Int,
        hi: Int,
        value: Int,
        text: (Int) -> String,
        changed: (Int) -> Unit
    ): SeekBar {
        val header = label("$title: ${text(value)}", 12f, true).apply { setPadding(0, dp(12), 0, 0) }
        body.addView(header)
        val bar = SeekBar(context)
        bar.max = hi - lo
        bar.progress = value - lo
        bar.setOnSeekBarChangeListener(seekListener { p ->
            val v = lo + p
            header.text = "$title: ${text(v)}"
            changed(v)
        })
        body.addView(bar)
        return bar
    }

    fun toggle(text: String, checked: Boolean, change: (Boolean) -> Unit): Switch = Switch(context).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 13f
        setPadding(0, dp(10), 0, dp(10))
        isChecked = checked
        setOnCheckedChangeListener { _, on -> change(on) }
    }

    fun segmented(options: List<String>, selected: Int, pick: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val views = ArrayList<TextView>()
        fun paint(current: Int) = views.forEachIndexed { i, v ->
            v.background = rounded(if (i == current) 0xff3a3a3a.toInt() else 0xff202020.toInt(), 10f)
            v.setTextColor(if (i == current) Color.WHITE else 0xffa0a0a0.toInt())
        }
        options.forEachIndexed { i, option ->
            val v = TextView(context).apply {
                text = option
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(4), dp(11), dp(4), dp(11))
                setOnClickListener { paint(i); pick(i) }
            }
            views.add(v)
            row.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) leftMargin = dp(6) })
        }
        paint(selected)
        return row
    }

    fun seekListener(changed: (Int) -> Unit): SeekBar.OnSeekBarChangeListener = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) { changed(progress) }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    /**
     * Touch-and-hold drag reorder shared by tabs, playlists and playlist items.
     * Keys look like "group:id"; drops only count inside the same group.
     */
    fun reorderable(ui: Handler, view: View, key: String, onDrop: (String, String) -> Unit) {
        val group = key.substringBefore(':')
        view.setOnLongClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            it.startDragAndDrop(ClipData.newPlainText("reorder", key), View.DragShadowBuilder(it), key, 0)
        }
        view.setOnDragListener { v, event ->
            val dragged = event.localState as? String
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> dragged != null && dragged.substringBefore(':') == group
                DragEvent.ACTION_DRAG_ENTERED -> { v.alpha = 0.5f; true }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> { v.alpha = 1f; true }
                DragEvent.ACTION_DROP -> {
                    v.alpha = 1f
                    if (dragged != null && dragged != key) ui.post { onDrop(dragged.substringAfter(':'), key.substringAfter(':')) }
                    true
                }
                else -> true
            }
        }
    }
}

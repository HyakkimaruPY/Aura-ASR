package com.aurareels.ocrlab

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Space
import android.widget.TextView

/** Only visible verses allocate rows. Text updates never trigger follow/scroll. */
class LyricsListView(context: Context, private val seek: (Long) -> Unit, importLyrics: () -> Unit) : ListView(context) {
    private var cues = emptyList<SpeechCue>()
    private var active = -1
    private var synced = true
    private var renderedEpoch = -1L
    private var centerPosted = false
    private val follow = LyricFollowController()
    private val tail = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val spacer = Space(context)

    private val verseAdapter = object : BaseAdapter() {
        override fun getCount() = cues.size
        override fun getItem(position: Int) = cues[position]
        override fun getItemId(position: Int): Long {
            val rawId = cues[position].id
            return rawId.take(15).toLongOrNull(16) ?: rawId.hashCode().toLong()
        }
        override fun hasStableIds() = true
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? LinearLayout ?: LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(12), dp(24), dp(12))
                addView(TextView(context).apply { textSize = 22f; setTypeface(Typeface.DEFAULT, Typeface.BOLD) })
                addView(TextView(context).apply { textSize = 14f; setPadding(0, dp(2), 0, 0) })
            }
            val cue = cues[position]; val translated = SubtitleTiming.display(cue)
            val same = translated.isEmpty() || translated == cue.source
            (row.getChildAt(0) as TextView).apply { text=if(same) cue.source else translated; setTextColor(if (!synced || active == position) Color.WHITE else 0xff6a6a6a.toInt()) }
            (row.getChildAt(1) as TextView).apply { text = if (same) "" else cue.source; visibility = if (same) View.GONE else View.VISIBLE; setTextColor(if (!synced || active == position) 0xffb8b8b8.toInt() else 0xff555555.toInt()) }
            return row
        }
    }

    init {
        divider = null; isVerticalScrollBarEnabled = false; cacheColorHint = Color.TRANSPARENT
        tail.setPadding(dp(24), dp(8), dp(24), 0)
        tail.addView(
            Button(context).apply {
                text = "Importar letra (.lrc)"
                isAllCaps = false; textSize = 12f; stateListAnimator = null; elevation = 0f
                background = RippleDrawable(
                    ColorStateList.valueOf(0x22ffffff),
                    GradientDrawable().apply { setColor(0xff202020.toInt()); cornerRadius = dp(12).toFloat() },
                    null
                )
                setTextColor(Color.WHITE)
                setOnClickListener { importLyrics() }
            },
            LinearLayout.LayoutParams(-1, dp(48))
        )
        tail.addView(spacer, LinearLayout.LayoutParams(-1, dp(160)))
        addFooterView(tail, null, false)
        adapter = verseAdapter
        setOnItemClickListener { _, _, position, _ -> if (synced) cues.getOrNull(position)?.let { seek(it.startMs) } }
    }

    fun update(next: List<SpeechCue>, index: Int, timed: Boolean) {
        val epoch = SpeechCue.changeEpoch.get()
        val changed = epoch != renderedEpoch || cues.size != next.size || active != index || synced != timed
        cues = next; active = index; synced = timed; renderedEpoch = epoch
        if (changed) verseAdapter.notifyDataSetChanged()
        if (follow.cueChanged(cues.getOrNull(active)?.id)) requestCenter()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        spacer.layoutParams = LinearLayout.LayoutParams(-1, h / 2 + dp(32))
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) follow.beginTouch()
        val result = super.dispatchTouchEvent(event)
        if (event.actionMasked in intArrayOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL) && follow.endTouch()) requestCenter()
        return result
    }

    private fun requestCenter() {
        if (centerPosted) return
        centerPosted = true
        postOnAnimation {
            centerPosted = false
            if (!follow.canCenter || active !in cues.indices || height <= 0) return@postOnAnimation
            val row = getChildAt(active - firstVisiblePosition)
            val rowHeight = row?.height ?: dp(100)
            smoothScrollToPositionFromTop(active, (height - rowHeight) / 2, 220)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
}

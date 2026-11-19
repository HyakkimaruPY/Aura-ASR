package com.aurareels.ocrlab

import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout

/** Pages stay attached to their origin; opening/closing has no playback side effects and cleans up IME focus. */
class ContentPageHost(private val root: FrameLayout) {
    data class Page(val key: String, val view: View, val origin: Int, val requestClose: (() -> Boolean)?)
    private val stack = ArrayList<Page>()
    val isOpen get() = stack.isNotEmpty()
    val topKey get() = stack.lastOrNull()?.key

    private fun dismissKeyboard() {
        val imm = root.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(root.windowToken, 0)
    }

    fun open(key: String, view: View, origin: Int, animate: Boolean = true, requestClose: (() -> Boolean)? = null) {
        if (topKey == key) return
        dismissKeyboard()
        // Preserve custom backdrops (such as MusicBackdrop on the Lyrics page); default to opaque black otherwise.
        if (view.background == null) view.setBackgroundColor(Color.BLACK)
        view.isClickable = true
        view.isFocusableInTouchMode = true
        view.elevation = 24 * root.resources.displayMetrics.density
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        stack.add(Page(key, view, origin, requestClose))
        view.requestFocus()
        view.animate().cancel()
        if (animate && root.width > 0) {
            view.translationX = root.width.toFloat()
            view.animate().translationX(0f).setDuration(220).start()
        } else {
            view.translationX = 0f
        }
    }

    fun back(): Boolean {
        val page = stack.lastOrNull() ?: return false
        if (page.requestClose?.invoke() != false) pop(animate = true)
        return true
    }

    fun pop(animate: Boolean = true) {
        val page = stack.removeLastOrNull() ?: return
        dismissKeyboard()
        page.view.animate().cancel()
        if (animate && root.width > 0) {
            page.view.animate().translationX(root.width.toFloat()).setDuration(220)
                .withEndAction { (page.view.parent as? ViewGroup)?.removeView(page.view) }
                .start()
        } else {
            (page.view.parent as? ViewGroup)?.removeView(page.view)
        }
    }

    fun closeAll() {
        dismissKeyboard()
        stack.forEach { it.view.animate().cancel(); root.removeView(it.view) }
        stack.clear()
    }
}

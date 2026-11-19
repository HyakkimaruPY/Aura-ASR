package com.aurareels.ocrlab

/** Manual browsing lasts until the next sung cue. New rows or translations are not new cues. */
class LyricFollowController {
    private var key: String?=null
    private var touching=false
    private var manual=false
    private var pending=false
    val canCenter: Boolean get()=!touching && !manual && key!=null
    fun reset() { key=null; touching=false; manual=false; pending=false }
    fun cueChanged(next: String?): Boolean {
        if(next==key) return false
        key=next; manual=false; pending=next!=null
        if(touching || next==null) return false
        pending=false; return true
    }
    fun beginTouch() { touching=true; manual=true; pending=false }
    fun endTouch(): Boolean {
        touching=false
        if(!pending) return false
        pending=false; manual=false; return key!=null
    }
}
object LyricViewport {
    fun centeredScroll(rowTop: Int, rowHeight: Int, viewportHeight: Int, contentHeight: Int): Int =
        (rowTop+rowHeight/2-viewportHeight/2).coerceIn(0,(contentHeight-viewportHeight).coerceAtLeast(0))
}

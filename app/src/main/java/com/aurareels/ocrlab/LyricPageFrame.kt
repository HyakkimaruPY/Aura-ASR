package com.aurareels.ocrlab

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.view.View

/** Gestures start only in the empty side margins; list/text/transport touches remain native. */
class LyricPageFrame(context: Context,private val close: ()->Unit) : FrameLayout(context) {
    var scroll: View?=null
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var downX=0f; private var downY=0f; private var downRawY=0f; private var candidate=false; private var dragging=false
    private val margin=20*resources.displayMetrics.density
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX=event.x; downY=event.y; downRawY=event.rawY; dragging=false
                candidate=event.y>56*resources.displayMetrics.density && (event.x<margin || event.x>width-margin)
            }
            MotionEvent.ACTION_MOVE -> if(candidate && downY<height/2 && scroll?.canScrollVertically(-1)!=true && event.y-downY>slop && kotlin.math.abs(event.x-downX)<(event.y-downY)/2) { dragging=true; return true }
            MotionEvent.ACTION_UP -> if(candidate && kotlin.math.abs(event.x-downX)<slop && kotlin.math.abs(event.y-downY)<slop) { close(); candidate=false; return true }
            MotionEvent.ACTION_CANCEL -> { candidate=false; dragging=false }
        }
        return dragging || super.onInterceptTouchEvent(event)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if(!dragging) return super.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_MOVE -> translationY=(event.rawY-downRawY).coerceAtLeast(0f)
            MotionEvent.ACTION_UP -> { if(translationY>=height*0.25f) close() else animate().translationY(0f).setDuration(180).start(); dragging=false; candidate=false }
            MotionEvent.ACTION_CANCEL -> { animate().translationY(0f).setDuration(180).start(); dragging=false; candidate=false }
        }
        return true
    }
}

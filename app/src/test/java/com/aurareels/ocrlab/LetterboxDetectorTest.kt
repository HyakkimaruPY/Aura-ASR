package com.aurareels.ocrlab

import org.junit.Assert.*
import org.junit.Test

/** Symmetric baked-in black bar measurement and hysteresis tracking. */
class LetterboxDetectorTest {
    private val w = LetterboxDetector.W
    private val h = LetterboxDetector.H
    private fun frame(top: Float, bottom: Float, content: Int = 0xff787064.toInt()) = IntArray(w * h) { i ->
        val y = i / w
        if (y < top * h || y >= h - bottom * h) 0xff000000.toInt() else content
    }

    @Test fun detectsSymmetricBars() {
        val bars = LetterboxDetector.measure(frame(0.128f, 0.128f))!!
        assertEquals(0.256f, bars.total, 0.03f)
    }
    @Test fun plainFrameHasNoBars() = assertEquals(0f, LetterboxDetector.measure(frame(0f, 0f))!!.total, 0f)
    @Test fun darkSceneAndBlackFrameAreAmbiguous() {
        assertNull(LetterboxDetector.measure(frame(0f, 0f, 0xff0a0a0c.toInt())))
        assertNull(LetterboxDetector.measure(frame(0.5f, 0.5f, 0xff000000.toInt())))
    }
    @Test fun oneSidedBlackIsNotLetterbox() = assertEquals(0f, LetterboxDetector.measure(frame(0.3f, 0f))!!.total, 0f)

    @Test fun zoomNeedsFourSamplesAndDropsAfterTwo() {
        val t = LetterboxDetector.Tracker()
        val bars = LetterboxDetector.Bars(0.128f, 0.128f)
        repeat(3) { assertEquals(0f, t.push(bars), 0f) }
        assertEquals(0.256f, t.push(bars), 0.001f)
        t.push(null); assertEquals(0.256f, t.applied, 0.001f)       // ambiguous sample changes nothing
        t.push(LetterboxDetector.Bars(0f, 0f)); assertEquals(0.256f, t.applied, 0.001f)
        t.push(LetterboxDetector.Bars(0f, 0f)); assertEquals(0f, t.applied, 0f)
    }
    @Test fun barsOnlyInTheMiddleOfAVideo() {
        val t = LetterboxDetector.Tracker()
        repeat(5) { t.push(LetterboxDetector.Bars(0f, 0f)) }
        assertEquals(0f, t.applied, 0f)
        repeat(4) { t.push(LetterboxDetector.Bars(0.12f, 0.12f)) }
        assertTrue(t.applied > 0.2f)
        repeat(2) { t.push(LetterboxDetector.Bars(0f, 0f)) }
        assertEquals(0f, t.applied, 0f)
    }
}

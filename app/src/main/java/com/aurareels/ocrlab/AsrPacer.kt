package com.aurareels.ocrlab

import kotlin.math.max
import kotlin.math.min

data class PacerInput(val mainPos: Long, val scoutPos: Long, val readyThrough: Long,
    val backlogMs: Long, val known: Boolean, val engineActive: Boolean, val rtf: Double,
    val mainStarted: Boolean, val mainPlaying: Boolean, val afterSeek: Boolean,
    val delayMs: Long, val autoPaused: Boolean = false, val scoutPlaying: Boolean = true,
    val scoutEnded: Boolean = false, val mainReady: Boolean = true,
    val bypass: Boolean = false, val smooth: Boolean = true, val live: Boolean = false, val startupWaitMs: Long = 0, val recoveryPlayback: Boolean = false)
enum class MainAction { START, PAUSE, RESUME, NONE }
data class PacerDecision(val main: MainAction, val mainSpeed: Float, val scoutPlay: Boolean,
    val scoutSpeed: Float, val needMs: Long, val progress: Int, val reason: String)

/** Pure pacing policy. Never grants subtitle readiness from scout playback or network translation. */
class AsrPacer {
    var initialStalls = 0; private set
    fun reset() { initialStalls = 0 }
    fun noteStall(mediaMs: Long) { if (mediaMs < 90_000) initialStalls++ }
    fun decide(i: PacerInput): PacerDecision {
        val rtf = i.rtf.takeIf { it.isFinite() && it > 0 } ?: 0.5
        val recovery = if (rtf < 1.0) (i.backlogMs * rtf / max(0.15, 1.0 - rtf)).toLong()
            else (i.delayMs * min(rtf, 2.0)).toLong()
        // A warmed, fast engine need not collect the whole configured lead before the first frame.
        // Keep the larger reserve for slow CPUs, replay backlog and recovery from stalls.
        val fastStart = !i.mainStarted && !i.afterSeek && rtf <= 0.65 && i.backlogMs < 2500 && initialStalls == 0
        val base = if (i.afterSeek) min(i.delayMs, 8_000) else if (fastStart) min(i.delayMs, 12_000) else i.delayMs
        val need = (max(base, min(60_000L, recovery)) + initialStalls * 5_000L).coerceAtMost(90_000L)
        val lead = i.readyThrough - i.mainPos
        val ended = i.scoutEnded && i.readyThrough >= Long.MAX_VALUE / 8
        val ahead = i.scoutPos - i.mainPos
        // During LID do not deadlock on the playback cap; SpeechBridge keeps a bounded preflight.
        val cap = max(i.delayMs, need) + 25_000L
        val canScout = !i.bypass && !i.scoutEnded && (!i.known || i.engineActive)
        val scoutPlay = canScout && if (i.scoutPlaying) i.backlogMs < 12_000 && (!i.known || ahead < cap)
            else i.backlogMs < 4_000 && (!i.known || ahead < cap - 6_000)
        val scoutSpeed = if (i.backlogMs < 2_500) (1.0 / max(rtf, 0.3)).coerceIn(1.0, 3.0).toFloat() else 1f
        val progress = if (i.bypass || ended) 100 else if (!i.engineActive) 0 else (100.0 * max(0L, lead) / need).toInt().coerceIn(0, 100)
        val warn = max(5_000L, i.delayMs / 3)
        val resume = max(4_000L, min(need, max(i.delayMs / 2, recovery) + initialStalls * 5_000L)).coerceAtMost(60_000)
        val action = when {
            !i.mainStarted && i.mainReady && (PlaybackStartup.expired(i.startupWaitMs) || i.bypass || (i.known && i.engineActive && (ended || lead >= need))) -> MainAction.START
            !i.mainStarted -> MainAction.NONE
            i.bypass -> if (i.autoPaused && i.mainReady) MainAction.RESUME else MainAction.NONE
            i.recoveryPlayback -> if(i.autoPaused && i.mainReady) MainAction.RESUME else MainAction.NONE
            !i.engineActive -> MainAction.NONE
            i.live -> if(i.autoPaused && i.mainReady) MainAction.RESUME else MainAction.NONE
            !ended && i.mainPlaying && lead < 1_000 -> MainAction.PAUSE
            i.autoPaused && i.mainReady && (ended || lead >= resume) -> MainAction.RESUME
            else -> MainAction.NONE
        }
        val speed = if (!i.recoveryPlayback && !i.live && !i.bypass && !ended && i.smooth && i.mainStarted && lead < warn) 0.94f else 1f
        val reason = when {
            !i.mainStarted && PlaybackStartup.expired(i.startupWaitMs) -> "STARTUP_TIMEOUT"
            i.bypass -> "NO_SUBTITLES"
            !i.known -> "LID"
            !i.engineActive -> "ENGINE_LOADING"
            action == MainAction.PAUSE -> if (i.backlogMs > 4_000) "REPLAY_BACKLOG" else "VAD_OPEN_SEGMENT"
            action == MainAction.RESUME -> "HORIZON_RECOVERED"
            rtf >= 1.0 -> "RTF_SLOW"
            else -> "SAFE_HORIZON"
        }
        return PacerDecision(action, speed, scoutPlay, scoutSpeed, need, progress, reason)
    }
}

package com.aurareels.ocrlab

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executor

/**
 * Finishes pending translations for an opened, inactive subtitle package using the shared
 * batch/cache/ML Kit translation hub without taking over the active playback session.
 */
class SubtitlePackageTranslator(
    context: Context,
    private val store: SubtitlePackageStore,
    private val io: Executor,
    private val pack: SubtitlePackageStore.Package,
    private val update: (SubtitlePackageStore.Package) -> Unit
) : java.io.Closeable {
    private val handler = Handler(Looper.getMainLooper())
    private val cues = pack.cues.map { it.snapshot() }
    private val bridge = GoogleTranslateBridge(context) {}.apply {
        backgroundPackageMode = true
        configure(pack.language, "pt")
        registerSourceCues(cues, complete = pack.transcriptDone)
    }
    private var closed = false
    private var savedAt = 0L
    private var lastProgressSig = progressSignature()

    /** Tracks both final and provisional coverage so Web/ML Kit fallbacks push immediately to the open editor. */
    private fun progressSignature(): Long {
        var complete = 0L
        var playable = 0L
        for (c in cues) {
            if (TranslationCoverage.complete(c, pack.language, "pt")) complete++
            if (TranslationCoverage.playable(c, pack.language, "pt")) playable++
        }
        return (complete shl 20) or playable
    }

    fun applyEdits(edits: List<SubtitlePackageStore.Edit>, timings: List<SubtitlePackageStore.Timing> = emptyList()) {
        cues.forEach { c ->
            edits.firstOrNull { it.cueId == c.id }?.let { c.applyHuman(it.text, it.deleted) }
            timings.firstOrNull { it.cueId == c.id }?.let { c.applyTiming(it.startMs, it.endMs) }
        }
    }

    private val pulse = object : Runnable {
        override fun run() {
            if (closed) return
            val nowMs = System.currentTimeMillis()
            val pending = cues.asSequence()
                .filter { !it.busy && it.retryAt <= nowMs && !TranslationCoverage.complete(it, pack.language, "pt") }
                .take(6)
                .toList()
            pending.firstOrNull()?.let {
                bridge.updatePlaybackPosition(it.startMs, advancing = true)
                pending.forEach { c -> bridge.translate(c) }
            }
            val now = android.os.SystemClock.elapsedRealtime()
            val sigNow = progressSignature()
            if (sigNow != lastProgressSig && (pending.isEmpty() || now - savedAt >= 2500)) {
                lastProgressSig = sigNow
                savedAt = now
                checkpoint()
            }
            handler.postDelayed(this, if (pending.isEmpty()) 2500L else 600L)
        }
    }

    init { handler.post(pulse) }

    private fun checkpoint() {
        val snapshot = cues.map { it.snapshot() }
        io.execute {
            runCatching {
                store.checkpoint(pack.id, pack.language, pack.asrVersion, snapshot, pack.coverage, pack.consumed, pack.transcriptDone)
                store.load(pack.id)
            }.getOrNull()?.let { current -> handler.post { if (!closed) update(current) } }
        }
    }

    override fun close() {
        if (closed) return
        closed = true; handler.removeCallbacks(pulse); checkpoint(); bridge.close()
    }
}

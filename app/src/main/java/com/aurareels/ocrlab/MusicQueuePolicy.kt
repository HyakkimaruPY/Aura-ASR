package com.aurareels.ocrlab

import kotlin.random.Random

/** Off -> whole queue -> current track -> off. */
enum class RepeatMode {
    OFF, QUEUE, TRACK;
    fun next(): RepeatMode = when(this) { OFF -> QUEUE; QUEUE -> TRACK; TRACK -> OFF }
}

/**
 * Pure queue policy of the music player (no Android types): shuffle without repetition inside the current
 * folder/queue, three-state repeat, and the history behind "Previous" while shuffling.
 * Tracks are identified by their URI string; the caller owns the queue itself.
 */
class MusicQueuePolicy(private val random: Random = Random.Default) {
    var shuffle = false; private set
    var repeat = RepeatMode.OFF; private set
    private val history = ArrayList<String>()          // tracks left behind by Next (shuffle only)
    private val visited = LinkedHashSet<String>()      // tracks already played in the current shuffle cycle

    /** A different queue or an explicit track pick: forget history, keep the user's shuffle/repeat choice. */
    fun reset(current: String?) {
        history.clear(); visited.clear()
        if(current != null) visited.add(current)
    }
    fun setShuffle(on: Boolean, current: String?) {
        shuffle = on
        visited.clear(); if(current != null) visited.add(current)
    }
    fun cycleRepeat(): RepeatMode { repeat = repeat.next(); return repeat }

    /**
     * Next track id, or null when playback must stop.
     * [auto] = the track ended by itself. Repeat TRACK only replays on a natural end; a manual Next always advances.
     */
    fun next(queue: List<String>, current: String, auto: Boolean): String? {
        if(queue.isEmpty()) return null
        if(auto && repeat == RepeatMode.TRACK) return current
        val wrap = repeat != RepeatMode.OFF && (!auto || repeat == RepeatMode.QUEUE)
        val target = if(shuffle) shuffled(queue, current, wrap) else sequential(queue, current, wrap)
        if(target != null && shuffle && target != current) history.add(current)
        return target
    }

    /** Previous track id; null means "restart the current track". */
    fun previous(queue: List<String>, current: String): String? {
        if(shuffle) {
            while(history.isNotEmpty()) {
                val id = history.removeAt(history.size - 1)
                if(id != current && id in queue) return id
            }
            return null
        }
        val index = queue.indexOf(current)
        return when {
            index > 0 -> queue[index - 1]
            index == 0 && repeat != RepeatMode.OFF && queue.size > 1 -> queue.last()
            else -> null
        }
    }

    private fun sequential(queue: List<String>, current: String, wrap: Boolean): String? {
        val index = queue.indexOf(current)
        return when {
            index in 0 until queue.size - 1 -> queue[index + 1]
            wrap -> queue.first()
            index < 0 -> queue.first()
            else -> null
        }
    }

    private fun shuffled(queue: List<String>, current: String, wrap: Boolean): String? {
        visited.add(current)
        var pool = queue.filter { it !in visited }
        if(pool.isEmpty()) {
            if(!wrap) return null
            visited.clear(); visited.add(current)         // new cycle; the track that just played is not drawn again at once
            pool = queue.filter { it != current }
            if(pool.isEmpty()) return current             // one-track queue
        }
        val pick = pool[random.nextInt(pool.size)]
        visited.add(pick)
        return pick
    }
}

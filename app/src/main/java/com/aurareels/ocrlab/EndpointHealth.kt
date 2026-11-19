package com.aurareels.ocrlab

/** Per-endpoint failure budget and latency tracker; injected clocks keep tests deterministic. */
class EndpointHealth(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private data class Health(var failures: Int = 0, var until: Long = 0, var serverRejected: Boolean = false)
    private val states = HashMap<String, Health>()
    private val latencies = HashMap<String, Long>()
    private var lastWinner: String? = null

    @Synchronized fun allowed(id: String) = clock() >= (states[id]?.until ?: 0)

    @Synchronized fun success(id: String) {
        states.remove(id)
        lastWinner = id
    }

    @Synchronized fun recordSuccess(id: String, elapsedMs: Long) {
        success(id)
        val sample = elapsedMs.coerceIn(20L, 15_000L)
        latencies[id] = latencies[id]?.let { (it * 3 + sample) / 4 } ?: sample
    }

    @Synchronized fun failure(id: String, rejected: Boolean = false, retryAfterMs: Long = 0) {
        val s = states.getOrPut(id) { Health() }
        s.failures++
        if (rejected || retryAfterMs > 0) s.serverRejected = true
        if (rejected || retryAfterMs > 0 || s.failures >= 3) {
            s.until = clock() + maxOf(retryAfterMs, (1500L shl (s.failures - 1).coerceIn(0, 6)).coerceAtMost(90_000)) +
                kotlin.random.Random.nextLong(250, 1000)
        }
    }

    /** Clears transient network/socket cooldowns when connectivity returns; keeps HTTP 429/403 server bans. */
    @Synchronized fun clearTransient() {
        val it = states.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next().value
            if (!entry.serverRejected) it.remove()
        }
    }

    /** Healthy endpoints ordered by recent reliability, sticky winner and EWMA latency. */
    @Synchronized fun ordered(ids: List<String>): List<String> = ids
        .filter { allowed(it) }
        .sortedWith(
            compareBy<String> { states[it]?.failures ?: 0 }
                .thenBy { if (it == lastWinner) 0 else 1 }
                .thenBy { latencies[it] ?: 800L }
                .thenBy { ids.indexOf(it) }
        )
}

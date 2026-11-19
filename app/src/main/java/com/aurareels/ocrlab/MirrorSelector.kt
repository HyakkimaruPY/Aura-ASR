package com.aurareels.ocrlab

data class ModelMirror(val id: String, val url: String, val priority: Int = 10)
class MirrorSelector(private val clock: () -> Long = { System.currentTimeMillis() }) {
    private data class State(var failures: Int = 0, var until: Long = 0)
    private val states = HashMap<String,State>()
    @Synchronized fun ordered(mirrors: List<ModelMirror>, lastGood: String?): List<ModelMirror> = mirrors
        .filter { (states[it.url]?.until ?: 0) <= clock() }
        .sortedWith(compareBy<ModelMirror> { if(it.id == lastGood) 0 else 1 }.thenBy { it.priority }.thenBy { states[it.url]?.failures ?: 0 })
    @Synchronized fun fail(mirror: ModelMirror) {
        val s=states.getOrPut(mirror.url) { State() }; s.failures++
        if(s.failures >= 3) s.until=clock()+600_000
    }
    @Synchronized fun success(mirror: ModelMirror) { states.remove(mirror.url) }
    fun rotatable(error: Exception, cancelled: Boolean) = !cancelled && error !is NoModelSpace && error !is InterruptedException
}
class NoModelSpace(message: String) : java.io.IOException(message)

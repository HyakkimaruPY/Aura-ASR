package com.aurareels.ocrlab

/** Pure text <-> milliseconds conversion for the subtitle time editor. */
object SubtitleTimeText {
    /** H:MM:SS.mmm (hours omitted when zero would still be shown to keep fields aligned). */
    fun format(ms: Long): String {
        val v=ms.coerceAtLeast(0)
        return "%d:%02d:%02d.%03d".format(v/3_600_000,v/60_000%60,v/1000%60,v%1000)
    }
    /** Accepts "SS", "SS.mmm", "MM:SS", "MM:SS.mmm", "H:MM:SS.mmm"; comma or dot as decimal mark. Null when invalid. */
    fun parse(text: String): Long? {
        val clean=text.trim().replace(',','.')
        if(clean.isEmpty()) return null
        val parts=clean.split(':')
        if(parts.size>3) return null
        val last=parts.last()
        val secPart=last.split('.')
        if(secPart.size>2) return null
        val seconds=secPart[0].toLongOrNull()?.takeIf { it>=0 && secPart[0].all(Char::isDigit) } ?: return null
        val frac=if(secPart.size==2) {
            val digits=secPart[1]
            if(digits.isEmpty() || digits.length>3 || !digits.all(Char::isDigit)) return null
            digits.padEnd(3,'0').toLong()
        } else 0L
        val whole=parts.dropLast(1).map { p -> p.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull() ?: return null }
        val minutes=when(parts.size) { 1 -> 0L; 2 -> whole[0]; else -> whole[1] }
        val hours=if(parts.size==3) whole[0] else 0L
        if(parts.size>1 && seconds>=60) return null
        if(parts.size==3 && minutes>=60) return null
        return ((hours*60+minutes)*60+seconds)*1000+frac
    }
    /** Validates a window the same way [SpeechCue.applyTiming] does; returns an error message or null. */
    fun validate(start: Long?,end: Long?): String? = when {
        start==null -> "Início inválido. Use mm:ss.mmm"
        end==null -> "Fim inválido. Use mm:ss.mmm"
        end<start+SubtitleTiming.MIN_DURATION_MS -> "O fim deve ser pelo menos ${SubtitleTiming.MIN_DURATION_MS} ms depois do início"
        else -> null
    }
}

package com.aurareels.ocrlab

import java.io.File

/** Pure helpers (JVM-testable) for safe model-archive extraction. */
object ArchivePaths {
    private fun normalize(name: String): String =
        name.replace('\\', '/').removePrefix("./").trimStart('/')

    /**
     * Returns the single top-level directory shared by all [names], or null when entries sit at
     * different top-level locations (or at the archive root). Vosk/sherpa archives normally have
     * one root folder, but its name can differ slightly from the catalog (e.g. a patch version).
     */
    fun commonRoot(names: Collection<String>): String? {
        val roots = names.map { normalize(it) }.filter { it.isNotEmpty() && it != "." }
            .map { it.substringBefore('/') to it.contains('/') }
        if (roots.isEmpty()) return null
        val first = roots.first().first
        return if (roots.all { it.first == first } && roots.any { it.second }) first else null
    }

    /**
     * Path of [name] inside [root], or null if it is the root itself or outside it.
     * When [root] is blank the normalized name is returned unchanged.
     */
    fun stripRoot(name: String, root: String): String? {
        val normalized = normalize(name)
        if (root.isBlank()) return normalized.ifEmpty { null }
        return when {
            normalized == root || normalized == "$root/" -> null
            normalized.startsWith("$root/") -> normalized.removePrefix("$root/").ifEmpty { null }
            else -> null
        }
    }

    /** True if [relative] resolves inside [base] (blocks "../" and absolute-path tricks). */
    fun isInside(base: File, relative: String): Boolean {
        if (relative.isEmpty()) return false
        val target = File(base, relative)
        return target.canonicalPath.startsWith(base.canonicalPath + File.separator)
    }

    /**
     * A Vosk pack is usable when the acoustic model, its config and a decoding graph are present.
     * [sizeOf] returns a file's length (0 when absent) so the rule can be tested without a disk.
     */
    fun voskLooksComplete(exists: (String) -> Boolean, sizeOf: (String) -> Long): Boolean =
        sizeOf("am/final.mdl") > 100_000 && exists("conf/model.conf") &&
            (exists("graph/HCLr.fst") || exists("graph/HCLG.fst") || exists("graph/phones.txt") ||
                exists("graph/words.txt") || exists("graph/Gr.fst"))
}

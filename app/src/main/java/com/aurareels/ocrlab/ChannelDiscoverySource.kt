package com.aurareels.ocrlab

import java.net.URI

/**
 * Derives a channel template from an explicitly supplied `{id}` pattern or authorized example URL.
 * No vendor endpoints or discovery seeds are embedded in the application.
 */
data class ChannelDiscoverySource(
    val template: String,
    val seed: Int,
    val width: Int = 0,
    val isOttera: Boolean = false // retained for migration of legacy persisted data
) {
    fun url(id: Int): String = template.replace("{id}", id.toString().padStart(width, '0'))

    fun defaultChannelName(id: Int, detectedTitle: String? = null): String {
        val clean = detectedTitle?.trim().orEmpty()
        if (clean.isNotEmpty()) return clean.take(100)
        return "Canal $id"
    }

    companion object {
        const val FIRST = 0
        const val LAST = 999_999
        const val OTTERA_ID = "ottera"
        const val OTTERA_NAME = "Canais"
        const val OTTERA_TEMPLATE = "" // Legacy built-in slot no longer has a remote source

        /** No vendor-specific network identifiers or scan seeds are shipped. */
        val OTTERA_PRIORITY_IDS: List<Int> = emptyList()

        private val QUERY_ID_REGEX = Regex(
            "([?&](?:network_id|net_id|nid|id|cid|channel_id|channel|stream_id|ch)=)(\\d{1,7})(?=&|$)",
            RegexOption.IGNORE_CASE
        )
        private val EMPTY_QUERY_ID_REGEX = Regex(
            "([?&](?:network_id|net_id|nid|id|cid|channel_id|channel|stream_id|ch)=)(?=&|$)",
            RegexOption.IGNORE_CASE
        )
        private val PATH_ID_REGEX = Regex(
            "/(\\d{1,7})(?=/|\\.(?:m3u8|ts|mp4)(?:[?#]|$)|[?#]|$)",
            RegexOption.IGNORE_CASE
        )

        /** Compatibility hook for older persisted playlist entries; vendor discovery is disabled. */
        @Suppress("UNUSED_PARAMETER")
        fun isOtteraHost(raw: String): Boolean = false

        fun parse(raw: String): ChannelDiscoverySource? = runCatching {
            val trimmed = raw.trim().substringBefore('#').trim()
            if (trimmed.isEmpty()) return null

            EMPTY_QUERY_ID_REGEX.find(trimmed)?.let { emptyParam ->
                val withToken = trimmed.replaceRange(emptyParam.range, "${emptyParam.groupValues[1]}{id}")
                val checkUrl = URI(withToken.replace("{id}", "100")).toURL()
                require(checkUrl.protocol == "https" && checkUrl.host.isNotBlank())
                return ChannelDiscoverySource(withToken, FIRST, 0, false)
            }

            val token = if (trimmed.contains("{id}")) {
                trimmed
            } else {
                val query = QUERY_ID_REGEX.find(trimmed)
                val path = PATH_ID_REGEX.findAll(trimmed.substringBefore('?')).lastOrNull()
                val match = query ?: path ?: return null
                val digits = match.groups[if (query != null) 2 else 1] ?: return null
                trimmed.replaceRange(digits.range, "{id}")
            }
            require(token.windowed(4).count { it == "{id}" } == 1 && token.count { it == '{' } == 1 && token.count { it == '}' } == 1)
            val url = URI(token.replace("{id}", FIRST.toString())).toURL()
            require(url.protocol == "https" && url.host.isNotBlank())
            val seed = if (trimmed.contains("{id}")) {
                FIRST
            } else {
                val prefix = token.substringBefore("{id}")
                val suffix = token.substringAfter("{id}")
                trimmed.removePrefix(prefix).removeSuffix(suffix).toInt().coerceIn(FIRST, LAST)
            }
            val digits = if (trimmed.contains("{id}")) "" else trimmed.removePrefix(token.substringBefore("{id}")).removeSuffix(token.substringAfter("{id}"))
            ChannelDiscoverySource(token, seed, if (digits.length > 1 && digits.startsWith('0')) digits.length else 0, false)
        }.getOrNull()

        fun next(id: Int): Int = if (id >= LAST) FIRST else (id + 1).coerceAtLeast(FIRST)
    }
}

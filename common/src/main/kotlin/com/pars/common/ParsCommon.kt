package com.pars.common

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

object ParsUrl {
    fun http(value: String?): String? {
        val raw = value.orEmpty().trim().replace("\\/", "/").replace("&amp;", "&")
        val fixed = if (raw.startsWith("//")) "https:$raw" else raw
        return runCatching {
            val uri = URI(fixed)
            if ((uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrBlank()) fixed else null
        }.getOrNull()
    }

    fun origin(url: String): String? = runCatching {
        val u = URI(url)
        val port = if (u.port > 0 && u.port != 80 && u.port != 443) ":${u.port}" else ""
        "${u.scheme}://${u.host}$port"
    }.getOrNull()
}

object ChannelNormalizer {
    private val noise = Regex("\\b(?:FHD|FULL\\s*HD|UHD|4K|HD|SD|HEVC|H265|H264)\\b", RegexOption.IGNORE_CASE)
    private val spaces = Regex("[^\\p{L}\\p{N}]+")

    fun displayName(value: String): String = value.trim().replace(Regex("\\s+"), " ")

    fun key(value: String): String = displayName(value)
        .replace(noise, " ")
        .lowercase(Locale.forLanguageTag("tr"))
        .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u').replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
        .replace(spaces, "")

    fun stableId(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(key(value).toByteArray())
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }
}

data class HlsVariant(val url: String, val quality: Int, val bandwidth: Long = 0)

object HlsQuality {
    fun parse(masterUrl: String, text: String): List<HlsVariant> {
        if (!text.contains("#EXT-X-STREAM-INF", ignoreCase = true)) return emptyList()
        val lines = text.lineSequence().map { it.trim() }.toList()
        val out = mutableListOf<HlsVariant>()
        var pendingQuality = 0
        var pendingBandwidth = 0L
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF", true)) {
                pendingQuality = Regex("RESOLUTION=\\d+x(\\d+)", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                pendingBandwidth = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
                continue
            }
            if (pendingQuality >= 0 && line.isNotBlank() && !line.startsWith("#")) {
                val resolved = runCatching { URI(masterUrl).resolve(line).toString() }.getOrDefault(line)
                if (ParsUrl.http(resolved) != null) out += HlsVariant(resolved, pendingQuality, pendingBandwidth)
                pendingQuality = -1
                pendingBandwidth = 0
            }
        }
        return out.distinctBy { it.url }.sortedWith(compareByDescending<HlsVariant> { it.quality }.thenByDescending { it.bandwidth })
    }
}

object DomainCandidates {
    fun ordered(current: String, lastGood: String?, remote: List<String>, defaults: List<String>): List<String> =
        buildList {
            ParsUrl.http(lastGood)?.let(::add)
            ParsUrl.http(current)?.let(::add)
            remote.mapNotNull(ParsUrl::http).forEach(::add)
            defaults.mapNotNull(ParsUrl::http).forEach(::add)
        }.distinct()
}

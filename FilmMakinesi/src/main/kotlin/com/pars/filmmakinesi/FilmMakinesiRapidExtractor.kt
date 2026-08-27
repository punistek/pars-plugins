package com.pars.filmmakinesi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class FilmMakinesiRapidExtractor : ExtractorApi() {

    override val name = "FilmMakinesi Rapid"
    override val mainUrl = "https://rapid.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val pageReferer = referer ?: "https://filmmakinesi.to/"
        val pageHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to pageReferer)

        val html = try { app.get(url, headers = pageHeaders).text } catch (_: Throwable) { return null }
        val decoded = decode(html)
        val candidates = LinkedHashSet<String>()

        collectCandidates(decoded, candidates)
        collectCandidates(decode(decoded), candidates)

        val ordered = candidates
            .asSequence()
            .map(::clean)
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .sortedBy(::score)
            .toList()

        for (stream in ordered) {
            val streamHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to url,
                "Origin" to mainUrl,
                "Accept" to "*/*"
            )

            if (!isWorkingHls(stream, streamHeaders)) continue

            return listOf(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = stream,
                    type = ExtractorLinkType.M3U8
                ) {
                    headers = streamHeaders
                    quality = Qualities.Unknown.value
                }
            )
        }

        return null
    }

    private suspend fun isWorkingHls(stream: String, headers: Map<String, String>): Boolean {
        return try {
            val response = app.get(stream, headers = headers)
            val body = response.text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            body.startsWith("#EXTM3U", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun collectCandidates(text: String, out: LinkedHashSet<String>) {
        Regex(
            """https?://[^\s\"'<>\\]+?\.m3u8[^\s\"'<>\\]*""",
            RegexOption.IGNORE_CASE
        ).findAll(text).forEach { out.add(clean(it.value)) }

        Regex("""(?i)(?:file|src|source)\s*[:=]\s*[\"']([^\"']+)[\"']""")
            .findAll(text).forEach {
                val value = clean(it.groupValues[1])
                if (value.startsWith("http") && ".m3u8" in value.lowercase()) out.add(value)
            }
    }

    private fun score(value: String): Int {
        val v = value.lowercase()
        return when {
            "master.m3u8" in v -> 0
            "1080" in v -> 1
            "720" in v -> 2
            "playlist.m3u8" in v -> 3
            "index" in v && ".m3u8" in v -> 4
            else -> 10
        }
    }

    private fun decode(value: String): String = value
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003D", "=")
        .replace("&amp;", "&")
        .replace("%3A", ":", ignoreCase = true)
        .replace("%2F", "/", ignoreCase = true)
        .replace("%3F", "?", ignoreCase = true)
        .replace("%26", "&", ignoreCase = true)
        .replace("%3D", "=", ignoreCase = true)

    private fun clean(value: String): String = decode(value)
        .replace("\\", "")
        .trim()
        .trim('"', '\'', ')', ']', '}')

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}

package com.pars.filmmakinesi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CloseLoadExtractor : ExtractorApi() {

    override val name = "FilmMakinesi Close"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?
    ): List<ExtractorLink>? {
        val pageReferer = referer ?: "https://filmmakinesi.to/"

        val html = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to pageReferer
            )
        ).text

        val decoded = decode(html)
        val candidates = LinkedHashSet<String>()

        Regex(
            """https?://[^\s"'<>\\]+?(?:\.m3u8|master\.txt|playlist\.txt|index\.txt)[^\s"'<>\\]*""",
            RegexOption.IGNORE_CASE
        ).findAll(decoded).forEach {
            candidates.add(clean(it.value))
        }

        Regex(
            """(?i)(?:file|src|source)\s*[:=]\s*["']([^"']+)["']"""
        ).findAll(decoded).forEach {
            val value = clean(it.groupValues[1])
            val lower = value.lowercase()
            if (
                value.startsWith("http") &&
                (
                    ".m3u8" in lower ||
                    "master.txt" in lower ||
                    "playlist.txt" in lower ||
                    "index.txt" in lower
                )
            ) {
                candidates.add(value)
            }
        }

        val stream = candidates
            .filter { it.startsWith("http") }
            .distinct()
            .minByOrNull { score(it) }
            ?: return null

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = stream,
                type = ExtractorLinkType.M3U8
            ) {
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to url,
                    "Origin" to mainUrl
                )
                quality = Qualities.Unknown.value
            }
        )
    }

    private fun score(value: String): Int {
        val v = value.lowercase()
        return when {
            "master.m3u8" in v -> 0
            "master.txt" in v -> 1
            "1080" in v && ".m3u8" in v -> 2
            "720" in v && ".m3u8" in v -> 3
            "playlist.m3u8" in v -> 4
            "playlist.txt" in v -> 5
            ".m3u8" in v -> 6
            "index.txt" in v -> 7
            else -> 20
        }
    }

    private fun decode(value: String): String =
        value
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

    private fun clean(value: String): String =
        decode(value)
            .replace("\\", "")
            .trim()
            .trim('"', '\'', ')', ']', '}')

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}

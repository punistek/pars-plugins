package com.pars.filmmakinesi

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleFile
import com.lagradost.cloudstream3.utils.newExtractorLink

class FilmMakinesiRapidExtractor : ExtractorApi() {
    override val name = "FilmMakinesi Rapid"
    override val mainUrl = "https://rapid.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val effectiveReferer = referer
            ?.takeIf { it.isNotBlank() }
            ?: "https://filmmakinesi.to/"

        val response = app.get(
            url,
            referer = effectiveReferer,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        ).text

        val decoded = decode(response)

        val candidates = LinkedHashSet<String>()

        Regex(
            """https?://[^\s"'<>\\]+?\.m3u8[^\s"'<>\\]*""",
            RegexOption.IGNORE_CASE
        ).findAll(decoded).forEach { match ->
            candidates += clean(match.value)
        }

        listOf(
            Regex("""(?i)(?:file|src|source)\s*[:=]\s*["']([^"']+)["']"""),
            Regex("""(?i)["'](?:file|src|source)["']\s*:\s*["']([^"']+)["']""")
        ).forEach { regex ->
            regex.findAll(decoded).forEach { match ->
                val value = clean(match.groupValues[1])
                if (
                    value.startsWith("http") &&
                    value.lowercase().contains(".m3u8")
                ) {
                    candidates += value
                }
            }
        }

        val stream = pickBest(candidates.toList()) ?: return

        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = stream,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to url,
                    "Origin" to mainUrl
                )
            }
        )
    }

    private fun decode(value: String): String {
        return value
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
    }

    private fun clean(value: String): String {
        return decode(value)
            .replace("\\", "")
            .trim()
            .trim('"', '\'', ')', ']', '}')
    }

    private fun pickBest(items: List<String>): String? {
        return items
            .filter { it.startsWith("http") }
            .distinct()
            .sortedBy { value ->
                val lower = value.lowercase()
                when {
                    lower.contains("master.m3u8") -> 0
                    lower.contains("1080") -> 1
                    lower.contains("720") -> 2
                    lower.contains("playlist.m3u8") -> 3
                    lower.contains("index") && lower.contains(".m3u8") -> 4
                    else -> 10
                }
            }
            .firstOrNull()
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}

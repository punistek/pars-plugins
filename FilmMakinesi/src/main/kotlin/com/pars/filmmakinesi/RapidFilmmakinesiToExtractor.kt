package com.pars.filmmakinesi

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class RapidFilmmakinesiToExtractor : ExtractorApi() {
    override val name = "Rapid"
    override val mainUrl = "https://rapid.filmmakinesi.to"
    override val requiresReferer = true

    private fun absoluteUrl(raw: String): String {
        val value = raw
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()

        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/$value"
        }
    }

    private fun isHls(url: String): Boolean {
        val u = url.lowercase()
        return u.contains(".m3u8") ||
            u.endsWith("/master.txt") ||
            u.contains("/master.txt?") ||
            (u.contains("/hls/") && u.contains("master"))
    }

    private fun addCandidate(set: MutableSet<String>, raw: String?) {
        if (raw.isNullOrBlank()) return
        val clean = raw
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
            .trim('"', '\'', ' ')
        if (clean.isNotBlank()) set.add(clean)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer ?: "https://filmmakinesi.to/"
        val response = app.get(
            url,
            referer = pageReferer,
            headers = mapOf("Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7")
        )
        val body = response.text
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val candidates = linkedSetOf<String>()

        // Standard media URLs plus CloseLoad's HLS disguised as .../master.txt.
        Regex(
            """https?://[^"'\\s<>]+?(?:\.m3u8|\.mpd|\.mp4|/master\.txt)(?:\?[^"'\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach { addCandidate(candidates, it.value) }

        // JS/JWPlayer file/src values.
        Regex(
            """(?:file|src|contentUrl)\s*["']?\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach { m ->
            val value = m.groupValues[1]
            if (
                value.contains(".m3u8", true) ||
                value.contains(".mpd", true) ||
                value.contains(".mp4", true) ||
                value.contains("/master.txt", true)
            ) addCandidate(candidates, value)
        }

        // JSON-LD VideoObject is particularly reliable on CloseLoad.
        response.document.select("""script[type="application/ld+json"]""").forEach { node ->
            val json = node.data().ifBlank { node.html() }
                .replace("\\/", "/")
                .replace("&amp;", "&")
            Regex(
                """"contentUrl"\s*:\s*"([^"]+)"""",
                RegexOption.IGNORE_CASE
            ).findAll(json).forEach { addCandidate(candidates, it.groupValues[1]) }
        }

        response.document.select("video[src], source[src], audio[src]").forEach {
            addCandidate(candidates, it.attr("src"))
        }

        // Captions/subtitles from JWPlayer tracks.
        val subtitles = linkedSetOf<Pair<String, String>>()
        Regex(
            """\{[^{}]*["']file["']\s*:\s*["']([^"']+\.vtt[^"']*)["'][^{}]*["'](?:label|name)["']\s*:\s*["']([^"']+)["'][^{}]*\}""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(body).forEach {
            subtitles.add(it.groupValues[2] to absoluteUrl(it.groupValues[1]))
        }
        // Also handle label before file.
        Regex(
            """\{[^{}]*["'](?:label|name)["']\s*:\s*["']([^"']+)["'][^{}]*["']file["']\s*:\s*["']([^"']+\.vtt[^"']*)["'][^{}]*\}""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(body).forEach {
            subtitles.add(it.groupValues[1] to absoluteUrl(it.groupValues[2]))
        }
        subtitles.forEach { (label, subUrl) ->
            subtitleCallback(SubtitleFile(label.ifBlank { "Subtitle" }, subUrl))
        }

        val emitted = linkedSetOf<String>()
        for (raw in candidates) {
            val stream = absoluteUrl(raw)
            if (!emitted.add(stream)) continue

            when {
                isHls(stream) -> {
                    generateM3u8(
                        name,
                        stream,
                        url,
                        headers = mapOf(
                            "Referer" to url,
                            "Origin" to mainUrl
                        )
                    ).forEach(callback)
                }
                stream.contains(".mpd", true) -> {
                    callback(newExtractorLink(name, name, stream, INFER_TYPE) {
                        this.referer = url
                    })
                }
                stream.contains(".mp4", true) -> {
                    callback(newExtractorLink(name, name, stream, INFER_TYPE) {
                        this.referer = url
                    })
                }
            }
        }
    }
}

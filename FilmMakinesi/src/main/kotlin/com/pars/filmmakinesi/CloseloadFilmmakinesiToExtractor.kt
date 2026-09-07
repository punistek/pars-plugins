package com.pars.filmmakinesi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class CloseloadFilmmakinesiToExtractor : ExtractorApi() {

    override val name = "CloseLoad"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    private val browserUa =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private fun clean(raw: String): String {
        return raw
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
            .trim('"', '\'', ' ')
    }

    private fun absoluteUrl(raw: String): String {
        val value = clean(raw)

        return when {
            value.startsWith("https://", true) ||
                value.startsWith("http://", true) -> value

            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/$value"
        }
    }

    private fun isHls(url: String): Boolean {
        val value = url.lowercase()

        return value.contains(".m3u8") ||
            value.endsWith("/master.txt") ||
            value.contains("/master.txt?") ||
            value.contains("/txt/master.txt") ||
            (value.contains("/hls/") && value.contains("master"))
    }

    private fun mediaHeaders(playerUrl: String): Map<String, String> {
        return mapOf(
            "Referer" to playerUrl,
            "Origin" to mainUrl,
            "User-Agent" to browserUa,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        )
    }

    private fun addCandidate(
        target: MutableSet<String>,
        raw: String?
    ) {
        if (raw.isNullOrBlank()) return

        val value = clean(raw)
        if (value.isBlank()) return

        if (
            value.contains(".m3u8", true) ||
            value.contains(".mpd", true) ||
            value.contains(".mp4", true) ||
            value.contains("/master.txt", true)
        ) {
            target.add(value)
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer ?: "https://filmmakinesi.to/"

        // Cache kullanma. CloseLoad video adresi film/oturum bazlı değişebiliyor.
        val page = app.get(
            url,
            referer = pageReferer,
            headers = mapOf(
                "User-Agent" to browserUa,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache"
            )
        )

        val body = page.text
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val candidates = linkedSetOf<String>()

        // 1) Sayfada açık şekilde duran medya adresleri.
        Regex(
            """https?://[^"'\\\s<>]+?(?:\.m3u8|\.mpd|\.mp4|/master\.txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach {
            addCandidate(candidates, it.value)
        }

        // 2) JSON-LD contentUrl.
        page.document
            .select("""script[type="application/ld+json"]""")
            .forEach { script ->
                val json = script.data().ifBlank { script.html() }
                    .replace("\\/", "/")
                    .replace("&amp;", "&")

                Regex(
                    """"contentUrl"\s*:\s*"([^"]+)"""",
                    RegexOption.IGNORE_CASE
                ).findAll(json).forEach {
                    addCandidate(candidates, it.groupValues[1])
                }
            }

        // 3) JS değişkenlerini çöz:
        // const cz6id = "https://.../master.txt"
        // sources: [{ file: cz6id, type: "hls" }]
        val jsVariables = linkedMapOf<String, String>()

        Regex(
            """(?:var|let|const)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach { match ->
            val variable = match.groupValues[1]
            val value = clean(match.groupValues[2])

            if (
                value.contains(".m3u8", true) ||
                value.contains(".mpd", true) ||
                value.contains(".mp4", true) ||
                value.contains("/master.txt", true)
            ) {
                jsVariables[variable] = value
                addCandidate(candidates, value)
            }
        }

        // file: cz6id gibi tırnaksız değişken referansları.
        Regex(
            """(?:file|src|contentUrl)\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)""",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach { match ->
            val variable = match.groupValues[1]
            addCandidate(candidates, jsVariables[variable])
        }

        // file: "https://..." / src="..."
        Regex(
            """(?:file|src|contentUrl)\s*["']?\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach {
            addCandidate(candidates, it.groupValues[1])
        }

        // 4) DOM video/source.
        page.document
            .select("video[src], source[src], audio[src]")
            .forEach {
                addCandidate(candidates, it.attr("src"))
            }

        // 5) Altyazılar.
        val subtitles = linkedSetOf<Pair<String, String>>()

        Regex(
            """\{[^{}]*["']file["']\s*:\s*["']([^"']+\.(?:vtt|srt)[^"']*)["'][^{}]*["'](?:label|name)["']\s*:\s*["']([^"']+)["'][^{}]*\}""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(body).forEach {
            subtitles.add(
                it.groupValues[2] to absoluteUrl(it.groupValues[1])
            )
        }

        Regex(
            """\{[^{}]*["'](?:label|name)["']\s*:\s*["']([^"']+)["'][^{}]*["']file["']\s*:\s*["']([^"']+\.(?:vtt|srt)[^"']*)["'][^{}]*\}""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(body).forEach {
            subtitles.add(
                it.groupValues[1] to absoluteUrl(it.groupValues[2])
            )
        }

        subtitles.forEach { (label, subUrl) ->
            subtitleCallback(
                SubtitleFile(
                    label.ifBlank { "Subtitle" },
                    subUrl
                )
            )
        }

        // ÖNEMLİ:
        // Eski/stale master.txt adresini callback'e vermeden önce gerçekten
        // halen erişilebilir mi kontrol ediyoruz. 404 ise sıradaki adaya geç.
        val emitted = linkedSetOf<String>()

        for (raw in candidates) {
            val stream = absoluteUrl(raw)
            if (!emitted.add(stream)) continue

            if (isHls(stream)) {
                val headers = mediaHeaders(url)

                val validHls = try {
                    val manifest = app.get(
                        stream,
                        referer = url,
                        headers = headers
                    ).text

                    manifest.contains("#EXTM3U", ignoreCase = true)
                } catch (_: Throwable) {
                    false
                }

                if (!validHls) {
                    continue
                }

                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = stream,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                )

                // CloseLoad'da doğrulanmış ilk HLS ana kaynak yeterli.
                return
            }

            if (stream.contains(".mpd", true)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = stream,
                        type = INFER_TYPE
                    ) {
                        this.referer = url
                        headers = mediaHeaders(url)
                    }
                )
                return
            }

            if (stream.contains(".mp4", true)) {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = stream,
                        type = INFER_TYPE
                    ) {
                        this.referer = url
                        headers = mediaHeaders(url)
                    }
                )
                return
            }
        }
    }
}

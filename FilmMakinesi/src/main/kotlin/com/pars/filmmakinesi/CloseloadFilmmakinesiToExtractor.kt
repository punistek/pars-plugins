package com.pars.filmmakinesi

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class CloseloadFilmmakinesiToExtractor : ExtractorApi() {

    override val name = "CloseLoad"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    private val browserUa =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"

    private fun clean(raw: String): String =
        raw.replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
            .trim('"', '\'', ' ')

    private fun absoluteUrl(raw: String): String {
        val value = clean(raw)
        return when {
            value.startsWith("https://", true) || value.startsWith("http://", true) -> value
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
            u.contains("/txt/master.txt") ||
            (u.contains("/hls/") && u.contains("master"))
    }

    private fun mediaHeaders(playerUrl: String): Map<String, String> =
        mapOf(
            "Referer" to playerUrl,
            "Origin" to mainUrl,
            "User-Agent" to browserUa,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
        )

    private fun addCandidate(target: MutableSet<String>, raw: String?) {
        if (raw.isNullOrBlank()) return
        val value = clean(raw)
        if (value.isBlank()) return
        if (
            value.contains(".m3u8", true) ||
            value.contains(".mpd", true) ||
            value.contains(".mp4", true) ||
            value.contains("/master.txt", true)
        ) target += value
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageReferer = referer ?: "https://filmmakinesi.to/"

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

        // HDFilmCehennemi'ndeki çalışan ana mantık:
        // sources: bulunan packed script -> getAndUnpack -> dinamik decrypt.
        page.document.select("script").forEach { node ->
            val script = node.data().ifBlank { node.html() }
            if (
                script.contains("sources:", true) ||
                script.contains("sources =", true) ||
                script.contains("eval(function(p,a,c,k,e,d)", true)
            ) {
                val decoded = FilmmakinesiPackedSource.unpackAndDecrypt(script)
                if (!decoded.isNullOrBlank()) {
                    Log.d("FM-CLOSE", "Decoded packed source: $decoded")
                    addCandidate(candidates, decoded)
                }
            }
        }

        // Fallback: açık medya URL'leri.
        Regex(
            """https?://[^"'\\\s<>]+?(?:\.m3u8|\.mpd|\.mp4|/master\.txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach { addCandidate(candidates, it.value) }

        // Fallback: JS değişkeni + file: variable.
        val jsVariables = linkedMapOf<String, String>()
        Regex(
            """(?:var|let|const)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach { m ->
            val value = clean(m.groupValues[2])
            if (
                value.contains(".m3u8", true) ||
                value.contains(".mpd", true) ||
                value.contains(".mp4", true) ||
                value.contains("/master.txt", true)
            ) {
                jsVariables[m.groupValues[1]] = value
                addCandidate(candidates, value)
            }
        }

        Regex(
            """(?:file|src|contentUrl)\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)""",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach { m ->
            addCandidate(candidates, jsVariables[m.groupValues[1]])
        }

        Regex(
            """(?:file|src|contentUrl)\s*["']?\s*[:=]\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach { addCandidate(candidates, it.groupValues[1]) }

        page.document.select("""script[type="application/ld+json"]""").forEach { script ->
            val json = script.data().ifBlank { script.html() }
                .replace("\\/", "/")
                .replace("&amp;", "&")
            Regex(
                """"contentUrl"\s*:\s*"([^"]+)"""",
                RegexOption.IGNORE_CASE
            ).findAll(json).forEach { addCandidate(candidates, it.groupValues[1]) }
        }

        page.document.select("video[src], source[src], audio[src]").forEach {
            addCandidate(candidates, it.attr("src"))
        }

        val emitted = linkedSetOf<String>()
        for (raw in candidates) {
            val stream = absoluteUrl(raw)
            if (!emitted.add(stream)) continue

            if (isHls(stream)) {
                val headers = mediaHeaders(url)

                // HDFilmCehennemi yaklaşımındaki gibi son linki M3U8 olarak veriyoruz,
                // ancak stale master.txt'yi callback'e sokmamak için doğrulama korunuyor.
                val manifestOk = runCatching {
                    app.get(stream, referer = url, headers = headers)
                        .text
                        .contains("#EXTM3U", ignoreCase = true)
                }.getOrDefault(false)

                if (!manifestOk) {
                    Log.d("FM-CLOSE", "Rejected stale HLS: $stream")
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
                return
            }

            if (stream.contains(".mpd", true) || stream.contains(".mp4", true)) {
                callback(
                    newExtractorLink(name, name, stream) {
                        this.referer = url
                        headers = mediaHeaders(url)
                    }
                )
                return
            }
        }
    }
}

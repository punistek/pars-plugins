package com.pars.filmmakinesi

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class RapidFilmmakinesiToExtractor : ExtractorApi() {

    override val name = "Rapid"
    override val mainUrl = "https://rapid.filmmakinesi.to"
    override val requiresReferer = true

    private fun clean(raw: String): String =
        raw.replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()
            .trim('"', '\'', ' ')

    private fun absoluteUrl(raw: String): String {
        val value = clean(raw)
        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
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

    private fun addCandidate(set: MutableSet<String>, raw: String?) {
        if (raw.isNullOrBlank()) return
        val value = clean(raw)
        if (value.isNotBlank() && (
                value.contains(".m3u8", true) ||
                value.contains("/master.txt", true) ||
                value.contains("/txt/master.txt", true)
            )
        ) set += value
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
            headers = mapOf(
                "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache"
            )
        )

        val body = response.text
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val candidates = linkedSetOf<String>()

        // Aynı HDFilmCehennemi yaklaşımını Rapid'e de uygula.
        response.document.select("script").forEach { node ->
            val script = node.data().ifBlank { node.html() }
            if (
                script.contains("sources:", true) ||
                script.contains("sources =", true) ||
                script.contains("eval(function(p,a,c,k,e,d)", true)
            ) {
                val decoded = FilmmakinesiPackedSource.unpackAndDecrypt(script)
                if (!decoded.isNullOrBlank()) {
                    Log.d("FM-RAPID", "Decoded packed source: $decoded")
                    addCandidate(candidates, decoded)
                }
            }
        }

        Regex(
            """https?://[^"'\\\s<>]+?(?:\.m3u8|/master\.txt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach { addCandidate(candidates, it.value) }

        val jsVariables = linkedMapOf<String, String>()
        Regex(
            """(?:var|let|const)\s+([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        ).findAll(body).forEach { m ->
            val value = clean(m.groupValues[2])
            if (
                value.contains(".m3u8", true) ||
                value.contains("/master.txt", true) ||
                value.contains("/txt/master.txt", true)
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

        response.document.select("video[src], source[src], audio[src]").forEach {
            addCandidate(candidates, it.attr("src"))
        }

        val emitted = linkedSetOf<String>()
        for (raw in candidates) {
            val stream = absoluteUrl(raw)
            if (!emitted.add(stream)) continue

            if (isHls(stream)) {
                val headers = mapOf(
                    "Referer" to url,
                    "Origin" to mainUrl
                )

                val manifestOk = runCatching {
                    app.get(stream, referer = url, headers = headers)
                        .text
                        .contains("#EXTM3U", ignoreCase = true)
                }.getOrDefault(false)

                if (!manifestOk) {
                    Log.d("FM-RAPID", "Rejected stale HLS: $stream")
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
                    }
                )
                return
            }
        }
    }
}

package com.pars.filmmakinesi

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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
        val pageReferer = referer ?: "https://filmmakinesi.to/"
        val response = try {
            app.get(
                url,
                referer = pageReferer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
                )
            )
        } catch (e: Throwable) {
            Log.e(TAG, "RAPID_FETCH_ERROR url=$url error=$e")
            return
        }

        val html = response.text
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val links = LinkedHashSet<String>()

        Regex(
            """https?://[^\"'\\s<>]+?(?:\.m3u8|\.mpd|\.mp4|/master\.txt)(?:\?[^\"'\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { links += clean(it.value) }

        Regex(
            """(?is)(?:file|src|source|url|contentUrl)\s*[\"']?\s*[:=]\s*[\"']([^\"']+)[\"']"""
        ).findAll(html).forEach { m ->
            val value = clean(m.groupValues[1])
            if (looksLikeMedia(value)) links += value
        }

        if (html.contains("eval(function(p,a,c,k,e", ignoreCase = true)) {
            runCatching {
                val unpacked = getAndUnpack(html)
                    .replace("\\u0026", "&")
                    .replace("\\/", "/")
                Regex(
                    """https?://[^\"'\\s<>]+?(?:\.m3u8|\.mpd|\.mp4|/master\.txt)(?:\?[^\"'\\s<>]*)?""",
                    RegexOption.IGNORE_CASE
                ).findAll(unpacked).forEach { links += clean(it.value) }
            }
        }

        response.document.select("video[src], source[src], audio[src]").forEach { e ->
            val value = clean(e.attr("src"))
            if (looksLikeMedia(value)) links += absoluteUrl(value)
        }

        val seenSubs = LinkedHashSet<String>()
        Regex(
            """https?://[^\"'\\s<>]+\.(?:vtt|srt)(?:\?[^\"'\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach { m ->
            val sub = clean(m.value)
            if (seenSubs.add(sub)) subtitleCallback(SubtitleFile("Altyazı", sub))
        }

        val emitted = LinkedHashSet<String>()
        links.forEach { raw ->
            val stream = absoluteUrl(raw)
            if (!emitted.add(stream)) return@forEach
            val isHls = isHls(stream)
            val type = when {
                isHls -> ExtractorLinkType.M3U8
                stream.contains(".mpd", true) -> ExtractorLinkType.DASH
                else -> ExtractorLinkType.VIDEO
            }

            callback(
                newExtractorLink(name, if (isHls) "$name HLS" else name, stream, type) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to url,
                        "Origin" to mainUrl,
                        "Accept" to "*/*"
                    )
                }
            )
        }
    }

    private fun clean(value: String): String = value
        .trim()
        .replace("\\u0026", "&")
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .trim('"', '\'', ' ')

    private fun absoluteUrl(raw: String): String {
        val value = clean(raw)
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/$value"
        }
    }

    private fun isHls(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains(".m3u8") || lower.contains("/master.txt") || lower.contains("/hls/")
    }

    private fun looksLikeMedia(url: String): Boolean {
        val lower = url.lowercase()
        return isHls(lower) || lower.contains(".mp4") || lower.contains(".mpd")
    }

    companion object {
        private const val TAG = "FM_RAPID"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}

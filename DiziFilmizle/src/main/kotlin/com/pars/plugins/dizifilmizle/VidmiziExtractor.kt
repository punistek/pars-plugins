package com.pars.plugins.dizifilmizle

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class VidmiziExtractor : ExtractorApi() {
    override val name = "Vidmizi"
    override val mainUrl = "https://vidmixi.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer ?: "https://dizifilmizle.to/"
        val response = app.get(
            url,
            referer = ref,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7"
            )
        )
        val html = response.text
        val normalized = html
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("&amp;", "&")

        val links = LinkedHashSet<String>()

        // 1) Direkt kaynaklar.
        Regex("""https?://[^"'\\\s<>]+\.m3u8(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(normalized).forEach { links += clean(it.value) }

        Regex("""https?://[^"'\\\s<>]+\.mp4(?:\?[^"'\\\s<>]*)?""", RegexOption.IGNORE_CASE)
            .findAll(normalized).forEach { links += clean(it.value) }

        // 2) Ekrandaki Network isteğinde görülen /m3u/<token> yapısı.
        // Token HTML/JS içine relative veya absolute basılırsa yakalanır.
        Regex("""https?://(?:www\.)?vidmi(?:xi|zi)\.com/m3u/[A-Za-z0-9%+/_=\-.]+""",
            RegexOption.IGNORE_CASE
        ).findAll(normalized).forEach { links += clean(it.value) }

        Regex("""["'](/m3u/[A-Za-z0-9%+/_=\-.]+)["']""", RegexOption.IGNORE_CASE)
            .findAll(normalized).forEach { links += "$mainUrl${clean(it.groupValues[1])}" }

        // 3) JS source/file/url alanları.
        listOf("file", "src", "source", "url", "hls", "playlist").forEach { key ->
            Regex(
                """["']?$key["']?\s*[:=]\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(normalized).forEach { m ->
                val v = clean(m.groupValues[1])
                if (v.contains(".m3u8", true) || v.contains(".mp4", true) || v.contains("/m3u/")) {
                    links += when {
                        v.startsWith("http") -> v
                        v.startsWith("/") -> "$mainUrl$v"
                        else -> "$mainUrl/$v"
                    }
                }
            }
        }

        // 4) P.A.C.K.E.R. kullanılan embed'ler için CloudStream unpack yardımcısı.
        if (normalized.contains("eval(function(p,a,c,k,e", true)) {
            runCatching {
                val unpacked = getAndUnpack(normalized)
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")

                Regex("""https?://[^"'\\\s<>]+(?:\.m3u8|/m3u/)[^"'\\\s<>]*""",
                    RegexOption.IGNORE_CASE
                ).findAll(unpacked).forEach { links += clean(it.value) }
            }
        }

        // Altyazı varsa gönder.
        Regex(
            """https?://[^"'\\\s<>]+\.(?:vtt|srt)(?:\?[^"'\\\s<>]*)?""",
            RegexOption.IGNORE_CASE
        ).findAll(normalized).forEach { m ->
            subtitleCallback(SubtitleFile("Türkçe", clean(m.value)))
        }

        links.forEach { media ->
            val isHls = media.contains(".m3u8", true) || media.contains("/m3u/")
            callback(
                newExtractorLink(
                    name,
                    if (isHls) "Vidmizi HLS" else "Vidmizi MP4",
                    media
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    this.headers = mapOf(
                        "Referer" to url,
                        "Origin" to mainUrl,
                        "User-Agent" to USER_AGENT
                    )
                }
            )
        }
    }

    private fun clean(value: String): String =
        value.trim()
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\", "")
}

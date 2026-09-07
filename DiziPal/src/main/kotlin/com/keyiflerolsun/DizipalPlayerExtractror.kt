package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

class DizipalPlayer {

    suspend fun extract(
        url: String,
        pageReferer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerUrl = normalizeUrl(url)
        val uri = runCatching { URI(playerUrl) }.getOrNull() ?: return false
        val origin = "${uri.scheme}://${uri.host}"
        val referer = "$origin/"

        Log.d("DZP2126", "player GET=$playerUrl origin=$origin")

        val response = app.get(
            playerUrl,
            referer = pageReferer,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "User-Agent" to USER_AGENT
            )
        ).text

        val cleaned = response
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        val streams = linkedSetOf<String>()

        // file:"https://...m3u8", file: "..."
        Regex("""(?i)(?:file|src)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""")
            .findAll(cleaned)
            .forEach { streams += it.groupValues[1] }

        // JSON: "file":"https://...m3u8"
        Regex("""(?i)"(?:file|src|url)"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
            .findAll(cleaned)
            .forEach { streams += it.groupValues[1] }

        // Son çare: HTML/JS içindeki çıplak m3u8 adresleri.
        Regex("""https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
            .findAll(cleaned)
            .forEach { streams += it.value }

        if (streams.isEmpty()) {
            Log.e("DZP2126", "formationfeed/player sayfasında m3u8 bulunamadı")
            return false
        }

        streams.map(::normalizeUrl).distinct().forEach { stream ->
            Log.d("DZP2126", "m3u8=$stream")

            callback(
                newExtractorLink(
                    source = "DiziPal",
                    name = "DiziPal",
                    url = stream,
                    type = ExtractorLinkType.M3U8
                ) {
                    quality = Qualities.Unknown.value

                    // DiziPal 2126'nın güncel akışında medya isteği formationfeed
                    // origin/referer ile kabul ediliyor.
                    headers = mapOf(
                        "Origin" to origin,
                        "Referer" to referer,
                        "User-Agent" to USER_AGENT,
                        "Accept" to "*/*"
                    )
                }
            )
        }

        return true
    }

    private fun normalizeUrl(value: String): String {
        var out = value.trim()
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        if (out.startsWith("//")) out = "https:$out"
        return out
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/151.0.0.0 Safari/537.36"
    }
}

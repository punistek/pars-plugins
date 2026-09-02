package com.lagradost

import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class HotstreamExtractor : ExtractorApi() {
    override var name = "Hotstream"
    override var mainUrl = "https://hotstream.club"
    override val requiresReferer = true

    private val tag = "HotstreamExtractor"

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val embedHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Referer" to (referer ?: "https://720izle.com/")
        )

        val embedResponse = app.get(url, headers = embedHeaders)
        Log.d(tag, "Embed GET Status: ${embedResponse.code}")

        val embedHtml = embedResponse.text
        Log.d(tag, "Embed HTML Length: ${embedHtml.length}")

        val m3uRegex = Regex("""/m3u/([^"'\\\s<>]+)""")
        val matchResult = m3uRegex.find(embedHtml)

        if (matchResult == null) {
            Log.d(tag, "m3u Bulunamadi")
            return null
        }

        Log.d(tag, "m3u Bulundu")
        val m3uPath = matchResult.groupValues[0]
        val fullM3uUrl = if (m3uPath.startsWith("http")) m3uPath else "$mainUrl$m3uPath"

        val requestHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Referer" to url
        )

        val m3uResponse = app.get(fullM3uUrl, headers = requestHeaders)
        Log.d(tag, "m3u GET Status: ${m3uResponse.code}")

        val contentType = m3uResponse.headers["Content-Type"] ?: "Bilinmiyor"
        Log.d(tag, "Response Content-Type: $contentType")

        val responseText = m3uResponse.text.trim()
        Log.d(tag, "Response Uzunlugu: ${responseText.length}")

        val isRealM3u8 = responseText.startsWith("#EXTM3U")

        if (!isRealM3u8) {
            Log.d(tag, "#EXTM3U Dogrulanmadi")
            return null
        }

        Log.d(tag, "#EXTM3U Dogrulandi")

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = fullM3uUrl,
                type = ExtractorLinkType.M3U8
            ) {
                headers = requestHeaders
                quality = Qualities.Unknown.value
            }
        )
    }
}

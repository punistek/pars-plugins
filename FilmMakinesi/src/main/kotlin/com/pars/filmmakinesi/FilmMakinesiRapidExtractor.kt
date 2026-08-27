package com.pars.filmmakinesi

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class FilmMakinesiRapidExtractor : ExtractorApi() {

    override val name = "FilmMakinesi Rapid"
    override val mainUrl = "https://rapid.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val pageReferer = referer ?: "https://filmmakinesi.to/"

        val resolver = WebViewResolver(
            interceptUrl = Regex("""\.m3u8"""),
            additionalUrls = listOf(),
            useOkhttp = false,
            timeout = 20_000L
        )

        val response = try {
            app.get(
                url,
                referer = pageReferer,
                headers = mapOf("User-Agent" to USER_AGENT),
                interceptor = resolver
            )
        } catch (e: Throwable) {
            Log.e(TAG, "RAPID_WEBVIEW_ERROR url=$url error=$e")
            return null
        }

        val finalUrl = response.url

        Log.i(TAG, "RAPID_WEBVIEW_RESULT requested=$url intercepted=$finalUrl")

        if (finalUrl.isBlank() || finalUrl == url) {
            Log.i(TAG, "RAPID_WEBVIEW_NO_MATCH")
            return null
        }

        val streamHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to url,
            "Origin" to mainUrl,
            "Accept" to "*/*"
        )

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = finalUrl,
                type = ExtractorLinkType.M3U8
            ) {
                headers = streamHeaders
                quality = Qualities.Unknown.value
            }
        )
    }

    companion object {
        private const val TAG = "FM_RAPID"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}

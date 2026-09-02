package com.lagradost

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Izle720Provider : MainAPI() {
    override var mainUrl = "https://720izle.com"
    override var name = "720izle"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val document = response.document

        val iframeUrl = document.selectFirst("iframe[src*=hotstream.club]")?.attr("abs:src")
            ?: Regex("""https?://hotstream\.club/embed/[^"'\\\s<>]+""").find(document.html())?.value

        if (iframeUrl != null) {
            val fixedIframeUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl

            return loadExtractor(
                url = fixedIframeUrl,
                referer = data,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
        }

        return false
    }
}

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class UncutMaza : MainAPI() {
    override var mainUrl = "https://uncutmaza.cc"
    override var name = "UncutMaza"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest videos"
    )

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return "${mainUrl}/page/$page/"
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null

        // Gerçek sitede kartlar article.thumb-block olarak geliyor.
        // video-preview-item sınıfı her kartta yok; eski filtre tüm sonuçları eliyordu.
        if (!hasClass("thumb-block")) return null

        val title = anchor.attr("title")
            .ifBlank { selectFirst("header.entry-header span")?.text().orEmpty() }
            .ifBlank { selectFirst("img.video-main-thumb")?.attr("alt").orEmpty() }
            .trim()
        if (title.isBlank()) return null

        val img = selectFirst("img.video-main-thumb")
        val poster = fixUrlNull(
            img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: attr("data-main-thumb").takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { !it.startsWith("data:") }
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }

    private fun parseCards(document: org.jsoup.nodes.Document): List<SearchResponse> {
        return document
            .select("article.thumb-block")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = pageUrl(request.data, page)
        Log.d("UNCUTMAZA", "MAIN page=$page url=$url")

        val response = app.get(
            url,
            headers = browserHeaders,
            referer = "$mainUrl/"
        )

        val rawCards = response.document.select("article.thumb-block")
        val items = rawCards.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        Log.d(
            "UNCUTMAZA",
            "MAIN http=${response.code} rawCards=${rawCards.size} items=${items.size} title=${response.document.title()}"
        )

        return newHomePageResponse(request.name, items)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/?s=$encoded"
        Log.d("UNCUTMAZA", "SEARCH $url")

        val response = app.get(
            url,
            headers = browserHeaders,
            referer = "$mainUrl/"
        )

        val rawCards = response.document.select("article.thumb-block")
        val items = rawCards.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        Log.d("UNCUTMAZA", "SEARCH rawCards=${rawCards.size} items=${items.size}")
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("UNCUTMAZA", "LOAD $url")

        val document = app.get(
            url,
            headers = browserHeaders,
            referer = "$mainUrl/"
        ).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("[itemprop=thumbnailUrl]")?.attr("content")
        )

        val plot = document.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("UNCUTMAZA", "LINKS page=$data")

        val document = app.get(
            data,
            headers = browserHeaders,
            referer = "$mainUrl/"
        ).document

        // Sitenin detay sayfasında gerçek CDN MP4 adresi doğrudan
        // <meta itemprop="contentUrl" content="...mp4"> olarak bulunuyor.
        val candidates = linkedSetOf<String>()

        document.selectFirst("meta[itemprop=contentUrl]")
            ?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?.let { candidates.add(it) }

        // Site HTML'i ileride değişirse basit video/source fallback'leri.
        document.select("video source[src], video[src]").forEach { node ->
            val src = node.attr("src")
            if (src.isNotBlank()) candidates.add(src)
        }

        val links = candidates
            .mapNotNull { fixUrlNull(it) }
            .filter { it.startsWith("http") }
            .distinct()

        if (links.isEmpty()) {
            Log.d("UNCUTMAZA", "LINKS no direct source found")
            return false
        }

        links.forEachIndexed { index, videoUrl ->
            Log.d("UNCUTMAZA", "LINKS direct[$index]=$videoUrl")

            callback(
                newExtractorLink(
                    source = name,
                    name = if (links.size == 1) name else "$name ${index + 1}",
                    url = videoUrl,
                    type = if (videoUrl.contains(".m3u8", ignoreCase = true))
                        ExtractorLinkType.M3U8
                    else
                        ExtractorLinkType.VIDEO
                ) {
                    // Kullanıcının tarayıcı kaydındaki çalışan istekle aynı kritik başlıklar.
                    headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to browserHeaders.getValue("User-Agent"),
                        "Accept" to "*/*"
                    )
                    quality = Qualities.Unknown.value
                }
            )
        }

        return true
    }
}

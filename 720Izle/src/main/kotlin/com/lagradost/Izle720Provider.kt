package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

class Izle720Provider : MainAPI() {
    override var mainUrl = "https://720izle.com"
    override var name = "720izle"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override var hasMainPage = true

    // 1. ANASAYFA KARTLARI (MainPageRequest parametresi ile güncellendi)
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) mainUrl else "$mainUrl/sayfa/$page"
        val document = app.get(url).document

        val home = document.select("div.movie-poster, div.movie-box, article.item, div.poster-pop").mapNotNull { element ->
            val title = element.selectFirst("a.film-title, h2, .title, .name")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrlNull(element.selectFirst("a")?.attr("href")) ?: return@mapNotNull null

            val poster = element.selectFirst("img")?.let { img ->
                fixUrlNull(
                    img.attr("data-src").ifEmpty {
                        img.attr("data-original").ifEmpty {
                            img.attr("src")
                        }
                    }
                )
            }

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    name = request.name,
                    list = home
                )
            ),
            hasNext = true
        )
    }

    // 2. ARAMA
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        return document.select("div.movie-poster, div.movie-box, article.item, div.poster-pop").mapNotNull { element ->
            val title = element.selectFirst("a.film-title, h2, .title, .name")?.text()?.trim() ?: return@mapNotNull null
            val href = fixUrlNull(element.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val poster = element.selectFirst("img")?.let { img ->
                fixUrlNull(
                    img.attr("data-src").ifEmpty {
                        img.attr("data-original").ifEmpty {
                            img.attr("src")
                        }
                    }
                )
            }

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // 3. FİLM DETAYI
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1, .entry-title")?.text()?.trim() ?: "Bilinmeyen Film"
        val poster = document.selectFirst("div.poster img, div.movie-poster img")?.let { img ->
            fixUrlNull(img.attr("data-src").ifEmpty { img.attr("src") })
        }
        val description = document.selectFirst("div.entry-content, div.story, div.description")?.text()?.trim()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // 4. VİDEO LİNKLERİ
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

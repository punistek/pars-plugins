package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Izle720Provider : MainAPI() {

    override var mainUrl = "https://720izle.com"
    override var name = "720izle"
    override var lang = "tr"

    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Yeni Filmler"
    )

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "$mainUrl/"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page <= 1) request.data else "$mainUrl/page/$page/"
        val document = app.get(url, headers = headers).document
        val movies = parseMovieCards(document)

        return newHomePageResponse(
            request.name,
            movies,
            hasNext = movies.isNotEmpty()
        )
    }

    private fun parseMovieCards(document: Document): List<SearchResponse> {
        return document
            .select("a[href*='/filmler11/']")
            .mapNotNull(::toMovieCard)
            .distinctBy { it.url }
    }

    private fun toMovieCard(link: Element): SearchResponse? {
        val href = link.absUrl("href").ifBlank {
            fixUrl(link.attr("href"))
        }

        if (href.isBlank() || !href.contains("/filmler11/")) return null

        val img = link.selectFirst("img")
            ?: link.parent()?.selectFirst("img")
            ?: return null

        val title = link.attr("title")
            .ifBlank { img.attr("alt") }
            .ifBlank {
                link.parent()
                    ?.selectFirst(".title, .movie-title, h2, h3")
                    ?.text()
                    .orEmpty()
            }
            .trim()

        if (title.isBlank()) return null

        val poster = img.attr("abs:data-src")
            .ifBlank { img.attr("abs:data-lazy-src") }
            .ifBlank { img.attr("abs:data-original") }
            .ifBlank { img.attr("abs:src") }
            .ifBlank {
                img.attr("data-src")
                    .takeIf { it.isNotBlank() }
                    ?.let(::fixUrl)
                    .orEmpty()
            }
            .ifBlank {
                img.attr("src")
                    .takeIf { it.isNotBlank() }
                    ?.let(::fixUrl)
                    .orEmpty()
            }

        return newMovieSearchResponse(
            title,
            href,
            TvType.Movie
        ) {
            posterUrl = poster.takeIf { it.isNotBlank() }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val encoded = URLEncoder.encode(q, "UTF-8")
        val document = app.get(
            "$mainUrl/?s=$encoded",
            headers = headers
        ).document

        return parseMovieCards(document)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document
            .selectFirst("meta[property='og:title']")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.title().trim().takeIf { it.isNotBlank() }
            ?: return null

        val poster = document
            .selectFirst("meta[property='og:image']")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst(".poster img, .movie-poster img, article img")
                ?.let { img ->
                    img.attr("abs:data-src").ifBlank { img.attr("abs:src") }
                }
                ?.takeIf { it.isNotBlank() }

        val plot = document
            .selectFirst("meta[property='og:description']")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document
                .selectFirst("meta[name='description']")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val year = Regex("""\\b(19|20)\\d{2}\\b""")
            .find(title)
            ?.value
            ?.toIntOrNull()

        return newMovieLoadResponse(
            title = title,
            url = url,
            type = TvType.Movie,
            dataUrl = url
        ) {
            posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCdn: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val document = response.document

        val iframeUrl = document
            .selectFirst("iframe[src*=hotstream.club]")
            ?.attr("abs:src")
            ?: Regex("""https?://hotstream\\.club/embed/[^"'\\\\\\s<>]+""")
                .find(document.html())
                ?.value

        if (iframeUrl != null) {
            val fixedIframeUrl =
                if (iframeUrl.startsWith("//")) "https:$iframeUrl"
                else iframeUrl

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

package com.generated.filmmakinesi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class FilmmakinesiProvider : MainAPI() {
    override var mainUrl = "https://filmmakinesi.to"
    override var name = "Filmmakinesi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private fun isSeriesUrl(url: String): Boolean =
        url.contains("/dizi/", true) || url.contains("/yabanci-dizi", true)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val target = if (page <= 1) mainUrl else "$mainUrl/page/$page/"
        val document = app.get(target).document
        val items = document.select("a.item").mapNotNull { card ->
            val href = card.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = card.selectFirst(".title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val img = card.selectFirst("img")
            val poster = listOf("data-src", "data-lazy-src", "src")
                .firstNotNullOfOrNull { a -> img?.attr(a)?.takeIf { it.isNotBlank() } }
                ?.let(::fixUrl)
            val type = if (isSeriesUrl(href)) TvType.TvSeries else TvType.Movie
            newMovieSearchResponse(title, fixUrl(href), type) { posterUrl = poster }
        }
        return newHomePageResponse(HomePageList("Ana Sayfa", items))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/arama/", params = mapOf("s" to query)).document
        return document.select("a.item").mapNotNull { card ->
            val href = card.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = card.selectFirst(".title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val poster = card.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
            }?.takeIf { it.isNotBlank() }?.let(::fixUrl)
            newMovieSearchResponse(
                title, fixUrl(href),
                if (isSeriesUrl(href)) TvType.TvSeries else TvType.Movie
            ) { posterUrl = poster }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("#info--box h1.title, h1.title, h1")
            ?.ownText()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let(::fixUrl)
        val plot = document.selectFirst("meta[name=description]")?.attr("content")
            ?.takeIf { it.isNotBlank() }

        // Keep the page URL as data: loadLinks resolves all player alternatives at click time.
        return newMovieLoadResponse(title, url, if (isSeriesUrl(url)) TvType.TvSeries else TvType.Movie, url) {
            posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val embeds = linkedSetOf<String>()

        document.select("a[data-video_url], [data-video-url], iframe[src], iframe[data-src]").forEach { e ->
            val raw = e.attr("data-video_url")
                .ifBlank { e.attr("data-video-url") }
                .ifBlank { e.attr("src") }
                .ifBlank { e.attr("data-src") }
            if (raw.isNotBlank() && !raw.contains("youtube.com", true)) embeds.add(fixUrl(raw))
        }

        var attempted = false
        embeds.forEach { embed ->
            try {
                attempted = true
                loadExtractor(embed, data, subtitleCallback, callback)
            } catch (_: Throwable) {
                // One mirror failing must not prevent the next mirror.
            }
        }
        return attempted
    }
}

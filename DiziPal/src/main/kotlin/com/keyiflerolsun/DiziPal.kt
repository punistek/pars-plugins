package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink

class DiziPal : MainAPI() {
    override var mainUrl = "https://dizipal2126.com"
    override var name = "DiziPal"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/filmler" to "Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else {
            val sep = if (request.data.contains("?")) "&" else "?"
            "${request.data}${sep}page=$page"
        }

        val document = app.get(url, referer = "$mainUrl/").document
        val items = document.select("li.content-card").mapNotNull { card ->
            val a = card.selectFirst("a.card-link") ?: return@mapNotNull null
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val title = card.selectFirst("h3.card-title")?.text()?.trim()
                ?: card.selectFirst("img")?.attr("alt")?.removeSuffix(" izle")?.trim()
                ?: return@mapNotNull null
            val poster = card.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.let(::fixUrlNull)

            if (href.contains("/dizi/")) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    posterUrl = poster
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    posterUrl = poster
                }
            }
        }

        val totalPages = document.selectFirst("#contentGrid")?.attr("data-total-pages")?.toIntOrNull()
        return newHomePageResponse(
            request.name,
            items,
            hasNext = totalPages?.let { page < it } ?: items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/arama?q=${java.net.URLEncoder.encode(query, "UTF-8")}",
            referer = "$mainUrl/"
        ).document

        return document.select("li.content-card").mapNotNull { card ->
            val a = card.selectFirst("a.card-link") ?: return@mapNotNull null
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            val title = card.selectFirst("h3.card-title")?.text()?.trim()
                ?: card.selectFirst("img")?.attr("alt")?.removeSuffix(" izle")?.trim()
                ?: return@mapNotNull null
            val poster = card.selectFirst("img")?.let {
                it.attr("data-src").ifBlank { it.attr("src") }
            }?.let(::fixUrlNull)

            if (href.contains("/dizi/")) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBefore(" izle")?.substringBefore(" |")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("h2.series-title")?.text()?.trim()
            ?: return null

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let(::fixUrlNull)

        val description = document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        if (url.contains("/dizi/")) {
            val episodes = document.select("a.episode-item").mapNotNull { a ->
                val epHref = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val label = a.selectFirst(".ep-label")?.text()?.trim()
                    ?: a.text().trim()

                val season = Regex("""(\d+)\.\s*Sezon""", RegexOption.IGNORE_CASE)
                    .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()
                val episode = Regex("""(\d+)\.\s*Bölüm""", RegexOption.IGNORE_CASE)
                    .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()

                newEpisode(epHref) {
                    name = label
                    this.season = season
                    this.episode = episode
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                posterUrl = poster
                plot = description
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            posterUrl = poster
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("DZP2126", "loadLinks data=$data")

        val document = app.get(data, referer = "$mainUrl/").document
        val cfg = document.selectFirst("#videoContainer[data-cfg]")?.attr("data-cfg").orEmpty()

        if (cfg.isBlank()) {
            Log.e("DZP2126", "videoContainer data-cfg bulunamadı")
            return false
        }

        val decoded = decodeBase64Url(cfg)
        Log.d("DZP2126", "data-cfg decoded=$decoded")

        val iframeUrl = Regex(""""v"\s*:\s*"([^"]+)"""")
            .find(decoded)?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            .orEmpty()

        if (iframeUrl.isBlank()) {
            Log.e("DZP2126", "data-cfg içinden v/player URL bulunamadı")
            return false
        }

        Log.d("DZP2126", "player=$iframeUrl")

        return DizipalPlayer().extract(
            url = iframeUrl,
            pageReferer = data,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
    }

    private fun decodeBase64Url(value: String): String {
        val normalized = value.trim()
        val pad = (4 - normalized.length % 4) % 4
        val padded = normalized + "=".repeat(pad)

        return try {
            String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        } catch (_: Exception) {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        }
    }
}

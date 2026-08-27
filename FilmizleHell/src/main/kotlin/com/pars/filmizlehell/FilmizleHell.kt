package com.pars.filmizlehell

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class FilmizleHell : MainAPI() {

    override var mainUrl = "https://filmizlehell.com"
    override var name = "FilmizleHell"
    override val hasMainPage = true
    override var lang = "tr"

    override val supportedTypes = setOf(
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/filmler" to "Filmler",
        "$mainUrl/tur/11-yerli-film-001" to "Yerli Filmler",
        "$mainUrl/tur/1-aksiyon-002" to "Aksiyon",
        "$mainUrl/tur/8-bilim-kurgu-001" to "Bilim Kurgu",
        "$mainUrl/tur/10-dram-001" to "Dram",
        "$mainUrl/tur/12-gerilim-001" to "Gerilim",
        "$mainUrl/tur/7-macera-001" to "Macera",
        "$mainUrl/tur/9-savas-001" to "Savaş"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = pageUrl(request.data, page)

        val document = app.get(
            url,
            headers = DEFAULT_HEADERS
        ).document

        val items = parseMovieCards(document)

        return newHomePageResponse(
            request.name,
            items
        )
    }

    private fun pageUrl(baseUrl: String, page: Int): String {
        if (page <= 1) return baseUrl

        return if (baseUrl.contains("?")) {
            "$baseUrl&page=$page"
        } else {
            "$baseUrl?page=$page"
        }
    }

    private fun parseMovieCards(document: Document): List<SearchResponse> {
        val results = LinkedHashMap<String, SearchResponse>()

        document.select("a[href*='/film/']").forEach { anchor ->
            val item = anchor.toMovieSearchResponse() ?: return@forEach
            results.putIfAbsent(item.url, item)
        }

        return results.values.toList()
    }

    private fun Element.toMovieSearchResponse(): SearchResponse? {
        val href = attr("href")
            .trim()
            .takeIf { it.contains("/film/") }
            ?.let(::fixUrl)
            ?: return null

        val card = parent()

        val image =
            selectFirst("img")
                ?: card?.selectFirst("img")
                ?: card?.parent()?.selectFirst("img")

        val poster = image?.let(::posterUrl)

        val title =
            attr("aria-label")
                .removeSuffix(" izle")
                .trim()
                .takeIf { it.isNotBlank() }
                ?: card?.selectFirst("h2,h3,h4,h5")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: image?.attr("alt")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: return null

        return newMovieSearchResponse(
            cleanTitle(title),
            href,
            TvType.Movie
        ) {
            this.posterUrl = poster
        }
    }

    private fun posterUrl(image: Element): String? {
        val candidates = listOf(
            image.attr("data-src"),
            image.attr("data-lazy-src"),
            image.attr("data-original"),
            image.attr("src")
        )

        val raw = candidates.firstOrNull {
            it.isNotBlank() && !it.startsWith("data:image")
        } ?: return null

        return fixUrl(raw)
    }

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val encoded = URLEncoder.encode(
            query,
            Charsets.UTF_8.name()
        )

        val url = pageUrl(
            "$mainUrl/arama?q=$encoded",
            page
        )

        val document = app.get(
            url,
            headers = DEFAULT_HEADERS
        ).document

        val results = parseMovieCards(document)

        val hasNext =
            document.selectFirst(
                "a[rel=next], a[href*='page=${page + 1}']"
            ) != null

        return newSearchResponseList(
            results,
            hasNext
        )
    }

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(
            url,
            headers = DEFAULT_HEADERS
        ).document

        val title =
            document.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.let(::cleanTitle)
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("h1")
                    ?.text()
                    ?.let(::cleanTitle)
                    ?.takeIf { it.isNotBlank() }
                ?: return null

        val poster =
            document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let(::fixUrl)

        val description =
            document.selectFirst("meta[name=description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val response = app.get(
            data,
            referer = mainUrl,
            headers = DEFAULT_HEADERS
        )

        val html = response.text
        val embeds = LinkedHashSet<String>()

        Regex(
            """https://p\.playturka\.space/[^'"\s<>]*#[A-Za-z0-9_-]+""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach {
            embeds += it.value
                .replace("&amp;", "&")
                .trim()
        }

        response.document
            .select("iframe[src*='p.playturka.space']")
            .forEach { iframe ->
                iframe.attr("src")
                    .replace("&amp;", "&")
                    .trim()
                    .takeIf { it.isNotBlank() }
                    ?.let(embeds::add)
            }

        Regex(
            """activeSource\s*:\s*['"]([^'"]*p\.playturka\.space[^'"]*#[A-Za-z0-9_-]+)['"]""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach {
            embeds += it.groupValues[1]
                .replace("&amp;", "&")
                .trim()
        }

        if (embeds.isEmpty()) {
            return false
        }

        var found = false

        embeds.forEach { embedUrl ->
            loadExtractor(
                embedUrl,
                data,
                subtitleCallback
            ) { link ->
                found = true
                callback(link)
            }
        }

        return found
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(
                Regex(
                    """(?i)\s*(Türkçe\s*(Dublaj|Altyazılı?)\s*)?izle.*$"""
                ),
                ""
            )
            .replace(
                Regex("""\s*\((19|20)\d{2}\)\s*$"""),
                ""
            )
            .trim()
    }

    companion object {
        private val DEFAULT_HEADERS = mapOf(
            "User-Agent" to
                "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
            "Accept" to
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.7"
        )
    }
}

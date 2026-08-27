package com.pars.filmmakinesi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FilmMakinesi : MainAPI() {

    override var mainUrl = "https://filmmakinesi.to"
    override var name = "FilmMakinesi"
    override val hasMainPage = true
    override var lang = "tr"

    override val supportedTypes = setOf(
        TvType.Movie
    )

    /*
     * FilmMakinesi sitesindeki gerçek bölümler.
     *
     * İlk sürümde film tarafını sağlam kuruyoruz.
     * Dizi/sezon/bölüm yapısını daha sonra aynı provider'a ekleyebiliriz.
     */
    override val mainPage = mainPageOf(
        "$mainUrl/filmler-1/" to "Filmler",
        "$mainUrl/ulke/turkiye-fm4/" to "Yerli Filmler",
        "$mainUrl/tur/aksiyon-fmy54y/film/" to "Aksiyon",
        "$mainUrl/tur/bilim-kurgu-fm3/film/" to "Bilim Kurgu",
        "$mainUrl/tur/dram-fm1/film/" to "Dram",
        "$mainUrl/tur/gerilim-fm1/film/" to "Gerilim",
        "$mainUrl/tur/gizem/film/" to "Gizem",
        "$mainUrl/tur/komedi-fm1/film/" to "Komedi",
        "$mainUrl/tur/korku-fm2/film/" to "Korku",
        "$mainUrl/tur/macera-fm1/film/" to "Macera",
        "$mainUrl/tur/romantik-fm1/film/" to "Romantik",
        "$mainUrl/seri-filmler-izle-1/" to "Seri Filmler",
        "$mainUrl/film-izle/olmeden-izlenmesi-gerekenler-fm1/" to "Ölmeden İzle",
        "$mainUrl/yil/2026/film/" to "2026 Filmleri",
        "$mainUrl/yil/2025-fm5/film/" to "2025 Filmleri",
    )

    // ------------------------------------------------------------
    // ANA SAYFA / KATEGORİLER
    // ------------------------------------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = pageUrl(request.data, page)
        val document = app.get(url).document

        val items = parseMovieCards(document)

        return newHomePageResponse(
            request.name,
            items
        )
    }

    /**
     * FilmMakinesi sayfalarında pagination yapısı değişse bile
     * /page/N/ biçimini destekle.
     */
    private fun pageUrl(baseUrl: String, page: Int): String {
        if (page <= 1) return baseUrl

        val clean = baseUrl
            .substringBefore("?")
            .removeSuffix("/")

        return "$clean/page/$page/"
    }

    /**
     * Site sınıf isimlerini değiştirse bile sadece "/film/" bağlantısı
     * ve poster resmi olan gerçek içerikleri seçmeye çalışır.
     *
     * Önce yaygın kart selector'larını dener, sonuç yoksa güvenli fallback
     * olarak film URL'si içeren posterli bağlantıları tarar.
     */
    private fun parseMovieCards(document: Document): List<SearchResponse> {
        val result = LinkedHashMap<String, SearchResponse>()

        val selectors = listOf(
            ".film-list .item",
            ".movie-list .item",
            ".post-item",
            ".movie-item",
            ".film-item",
            ".poster-item",
            "article"
        )

        selectors.forEach { selector ->
            document.select(selector).forEach { element ->
                element.toMovieResult()?.let { response ->
                    result.putIfAbsent(response.url, response)
                }
            }
        }

        // Fallback: FilmMakinesi film sayfaları /film/... şeklinde.
        document.select("a[href*='/film/']").forEach { anchor ->
            anchor.toMovieResultFromAnchor()?.let { response ->
                result.putIfAbsent(response.url, response)
            }
        }

        return result.values.toList()
    }

    private fun Element.toMovieResult(): SearchResponse? {
        val anchor = selectFirst(
            "a[href*='/film/'], a[href*='/film-izle/']"
        ) ?: return null

        return anchor.toMovieResultFromAnchor(this)
    }

    private fun Element.toMovieResultFromAnchor(
        container: Element? = null
    ): SearchResponse? {

        val hrefRaw = attr("href").trim()
        if (hrefRaw.isBlank()) return null

        val href = fixUrl(hrefRaw)

        // Kategori / yardımcı sayfaları içerik sanma.
        if (!href.contains("/film/")) return null

        val root = container ?: parent() ?: this

        val image =
            root.selectFirst("img")
                ?: selectFirst("img")
                ?: return null

        val poster = image.posterUrl()

        var title = listOf(
            attr("title"),
            image.attr("alt"),
            root.selectFirst("h1, h2, h3, h4")?.text().orEmpty(),
            root.selectFirst(".title, .film-title, .movie-title, .name")?.text().orEmpty()
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        title = cleanTitle(title)

        if (title.isBlank()) return null

        return newMovieSearchResponse(
            title,
            href,
            TvType.Movie
        ) {
            this.posterUrl = poster
        }
    }

    private fun Element.posterUrl(): String? {
        val candidates = listOf(
            attr("data-src"),
            attr("data-lazy-src"),
            attr("data-original"),
            attr("src")
        )

        val raw = candidates.firstOrNull {
            it.isNotBlank() &&
                !it.startsWith("data:image")
        } ?: return null

        return fixUrl(raw)
    }

    // ------------------------------------------------------------
    // ARAMA
    // ------------------------------------------------------------

    override suspend fun search(
        query: String,
        page: Int
    ): SearchResponseList {

        val encoded = java.net.URLEncoder.encode(
            query,
            Charsets.UTF_8.name()
        )

        /*
         * FilmMakinesi'nin formu:
         * action="/arama/" name="s"
         */
        val base = "$mainUrl/arama/?s=$encoded"

        val url = if (page <= 1) {
            base
        } else {
            "$mainUrl/arama/page/$page/?s=$encoded"
        }

        val document = app.get(url).document
        val results = parseMovieCards(document)

        val hasNext =
            document.selectFirst(
                "a[rel=next], .pagination a.next, " +
                    ".pagination .next, a:matchesOwn(^Sonraki$)"
            ) != null

        return newSearchResponseList(
            results,
            hasNext
        )
    }

    // ------------------------------------------------------------
    // FİLM DETAYI
    // ------------------------------------------------------------

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val title =
            document.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.let(::cleanTitle)
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("h1")
                    ?.text()
                    ?.let(::cleanTitle)
                ?: return null

        val poster =
            document.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?.let(::fixUrl)

        val description =
            document.selectFirst("meta[name=description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotBlank() }

        val year = extractYear(document, title)

        val tags = extractGenres(document)

        val recommendations =
            document.select("a[href*='/film/']")
                .mapNotNull {
                    it.toMovieResultFromAnchor()
                }
                .filter {
                    it.url != url
                }
                .distinctBy {
                    it.url
                }
                .take(20)

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    private fun extractYear(
        document: Document,
        title: String
    ): Int? {

        // Önce title / meta description içinden 4 haneli yılı bul.
        val text = buildString {
            append(title)
            append(" ")
            append(
                document.selectFirst("meta[name=description]")
                    ?.attr("content")
                    .orEmpty()
            )
            append(" ")
            append(document.body()?.text().orEmpty())
        }

        return Regex("""\b(19|20)\d{2}\b""")
            .find(text)
            ?.value
            ?.toIntOrNull()
    }

    private fun extractGenres(
        document: Document
    ): List<String> {

        val genres = LinkedHashSet<String>()

        document.select(
            "a[href*='/tur/'], " +
                "[itemprop=genre] a, " +
                "[itemprop=genre]"
        ).forEach { element ->

            val text = element.text().trim()

            if (
                text.isNotBlank() &&
                text.length <= 40 &&
                !text.equals("Tür", ignoreCase = true)
            ) {
                genres.add(text)
            }
        }

        return genres.toList()
    }

    // ------------------------------------------------------------
    // PLAYER / EMBED KAYNAKLARI
    // ------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(
            data,
            referer = mainUrl
        ).document

        /*
         * Motor City HTML'inde doğrulanan yapı:
         *
         * <div class="video-parts">
         *   <a data-video_url="https://closeload...">Altyazılı Close</a>
         *   <a data-video_url="https://rapid...">Altyazılı Rapid</a>
         * </div>
         */
        val embeds = LinkedHashSet<String>()

        document.select(
            ".video-parts a[data-video_url]"
        ).forEach { element ->

            element.attr("data-video_url")
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let(::fixUrl)
                ?.let(embeds::add)
        }

        /*
         * Varsayılan iframe'i de ekle.
         * FilmMakinesi HTML'inde iframe URL'si data-src içinde bulunabiliyor.
         */
        document.select(
            ".after-player iframe, " +
                ".player--area iframe, " +
                "#player-section iframe"
        ).forEach { iframe ->

            val iframeUrl =
                iframe.attr("data-src")
                    .ifBlank {
                        iframe.attr("src")
                    }
                    .trim()

            if (
                iframeUrl.isNotBlank() &&
                iframeUrl != "about:blank"
            ) {
                embeds.add(fixUrl(iframeUrl))
            }
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

    // ------------------------------------------------------------
    // YARDIMCILAR
    // ------------------------------------------------------------

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(
                Regex(
                    """(?i)\s*(filmi)?\s*(1080p)?\s*(full\s*hd)?\s*izle.*$"""
                ),
                ""
            )
            .replace(
                Regex("""(?i)\s*[-|]\s*FilmMakinesi.*$"""),
                ""
            )
            .trim()
    }
}

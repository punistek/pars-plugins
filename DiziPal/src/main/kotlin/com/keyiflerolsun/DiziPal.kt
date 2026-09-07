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

    /*
     * ÖNEMLİ:
     * Eski sürümde /diziler ve /filmler iki ayrı MainPageRequest idi.
     * PARS host tarafındaki cache/quarantine akışında bu bazen yalnız bir
     * request'in görünmesine yol açıyordu. Artık TEK request ile iki sayfayı
     * beraber çekip iki HomePageList olarak tek cevapta döndürüyoruz.
     */
    override val mainPage = mainPageOf(
        "$mainUrl" to "DiziPal"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val diziUrl = pageUrl("$mainUrl/diziler", page)
        val filmUrl = pageUrl("$mainUrl/filmler", page)

        val diziDoc = app.get(diziUrl, referer = "$mainUrl/").document
        val filmDoc = app.get(filmUrl, referer = "$mainUrl/").document

        val diziler = parseCards(diziDoc, onlySeries = true)
        val filmler = parseCards(filmDoc, onlySeries = false)

        Log.d(
            TAG,
            "MAIN page=$page diziler=${diziler.size} filmler=${filmler.size}"
        )

        val sections = arrayListOf<HomePageList>()

        if (diziler.isNotEmpty()) {
            sections += HomePageList(
                name = "Diziler",
                list = diziler,
                isHorizontalImages = false
            )
        }

        if (filmler.isNotEmpty()) {
            sections += HomePageList(
                name = "Filmler",
                list = filmler,
                isHorizontalImages = false
            )
        }

        return HomePageResponse(
            items = sections,
            hasNext = hasNextPage(diziDoc, page) || hasNextPage(filmDoc, page)
        )
    }

    private fun pageUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return "$base?page=$page"
    }

    private fun hasNextPage(document: org.jsoup.nodes.Document, page: Int): Boolean {
        val total = document
            .selectFirst("#contentGrid[data-total-pages]")
            ?.attr("data-total-pages")
            ?.toIntOrNull()

        if (total != null) return page < total

        return document.select("li.content-card").isNotEmpty()
    }

    private fun parseCards(
        document: org.jsoup.nodes.Document,
        onlySeries: Boolean
    ): List<SearchResponse> {

        return document.select("li.content-card").mapNotNull { card ->
            val a = card.selectFirst("a.card-link")
                ?: card.selectFirst("a[href*=/dizi/], a[href*=/film/]")
                ?: return@mapNotNull null

            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

            if (onlySeries && !href.contains("/dizi/")) {
                return@mapNotNull null
            }
            if (!onlySeries && !href.contains("/film/")) {
                return@mapNotNull null
            }

            val title = card
                .selectFirst("h3.card-title")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: card.selectFirst("img")
                    ?.attr("alt")
                    ?.removeSuffix(" izle")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val poster = card.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let(::fixUrlNull)

            if (href.contains("/dizi/")) {
                newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            } else {
                newMovieSearchResponse(
                    title,
                    href,
                    TvType.Movie
                ) {
                    posterUrl = poster
                }
            }
        }.distinctBy { it.url }
    }

    override suspend fun search(query: String): List<SearchResponse> {

        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val document = app.get(
            "$mainUrl/arama?q=$encoded",
            referer = "$mainUrl/"
        ).document

        return document.select("li.content-card").mapNotNull { card ->
            val a = card.selectFirst("a.card-link")
                ?: card.selectFirst("a[href*=/dizi/], a[href*=/film/]")
                ?: return@mapNotNull null

            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null

            val title = card
                .selectFirst("h3.card-title")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: card.selectFirst("img")
                    ?.attr("alt")
                    ?.removeSuffix(" izle")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val poster = card.selectFirst("img")?.let { img ->
                img.attr("data-src").ifBlank { img.attr("src") }
            }?.let(::fixUrlNull)

            when {
                href.contains("/dizi/") -> newTvSeriesSearchResponse(
                    title,
                    href,
                    TvType.TvSeries
                ) { posterUrl = poster }

                href.contains("/film/") -> newMovieSearchResponse(
                    title,
                    href,
                    TvType.Movie
                ) { posterUrl = poster }

                else -> null
            }
        }.distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> =
        search(query)

    override suspend fun load(url: String): LoadResponse? {

        Log.d(TAG, "LOAD_BEGIN url=$url")

        val document = app.get(
            url,
            referer = "$mainUrl/"
        ).document

        val title = document
            .selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.substringBefore(" izle")
            ?.substringBefore(" |")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("h2.series-title")?.text()?.trim()
            ?: return null

        val poster = document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.let(::fixUrlNull)

        val description = document
            .selectFirst("meta[name=description]")
            ?.attr("content")
            ?.trim()

        if (url.contains("/dizi/")) {

            /*
             * Gerçek 2126 HTML:
             *
             * <div class="detail-episode-item-wrap">
             *   <a href="/bolum/monsters-of-god-1-sezon-1-bolum"
             *      class="detail-episode-item ">
             *     ...
             *     <div class="detail-episode-subtitle">
             *       1. Sezon 1. Bölüm
             *     </div>
             *   </a>
             * </div>
             *
             * Selector'ı sadece class'a bağlamıyoruz.
             * /bolum/ href taşıyan bütün gerçek bölüm linklerini topluyoruz.
             */
            val episodeAnchors = document.select(
                ".detail-episode-item-wrap a[href*=/bolum/], " +
                    "a.detail-episode-item[href*=/bolum/], " +
                    "a[href*=/bolum/]"
            )

            val episodes = episodeAnchors.mapNotNull { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                if (!href.contains("/bolum/")) return@mapNotNull null

                val subtitle = a
                    .selectFirst(".detail-episode-subtitle")
                    ?.text()
                    ?.trim()
                    .orEmpty()

                val epTitle = a
                    .selectFirst(".detail-episode-title")
                    ?.text()
                    ?.trim()
                    .orEmpty()

                val label = subtitle
                    .ifBlank { epTitle }
                    .ifBlank { a.text().trim() }

                val season = extractSeason(label, href)
                val episode = extractEpisode(label, href)

                newEpisode(href) {
                    name = if (
                        season != null &&
                        episode != null
                    ) {
                        "$season. Sezon $episode. Bölüm"
                    } else {
                        label.ifBlank { "Bölüm" }
                    }

                    this.season = season
                    this.episode = episode
                }
            }
                .distinctBy { it.data }
                .sortedWith(
                    compareBy<Episode> {
                        it.season ?: Int.MAX_VALUE
                    }.thenBy {
                        it.episode ?: Int.MAX_VALUE
                    }
                )

            Log.d(
                TAG,
                "SERIES_DETAIL title=$title anchors=${episodeAnchors.size} episodes=${episodes.size} url=$url"
            )

            if (episodes.isEmpty()) {
                Log.e(
                    TAG,
                    "SERIES_DETAIL_EMPTY url=$url htmlTitle=${document.title()}"
                )
            }

            return newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = poster
                plot = description
            }
        }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Movie,
            url
        ) {
            posterUrl = poster
            plot = description
        }
    }

    private fun extractSeason(
        label: String,
        href: String
    ): Int? {

        return Regex(
            """(\d+)\.?\s*Sezon""",
            RegexOption.IGNORE_CASE
        ).find(label)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex(
                """-(\d+)-sezon-""",
                RegexOption.IGNORE_CASE
            ).find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    private fun extractEpisode(
        label: String,
        href: String
    ): Int? {

        return Regex(
            """(\d+)\.?\s*Bölüm""",
            RegexOption.IGNORE_CASE
        ).find(label)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex(
                """-(\d+)-bolum(?:/|$)""",
                RegexOption.IGNORE_CASE
            ).find(href)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        Log.d(TAG, "loadLinks data=$data")

        /*
         * PARS tarafında dizi kartı yanlışlıkla doğrudan loadLinks'e düşerse
         * artık "videoContainer yok" diye provider'ı karantinaya sokmuyoruz.
         *
         * Önce gerçek ilk /bolum/ adresini bulup onu resolve ediyoruz.
         * Normal akışta ise host load() cevabındaki episode listesini göstermeli.
         */
        if (data.contains("/dizi/")) {
            val seriesDoc = app.get(
                data,
                referer = "$mainUrl/"
            ).document

            val firstEpisode = seriesDoc
                .select(
                    ".detail-episode-item-wrap a[href*=/bolum/], " +
                        "a.detail-episode-item[href*=/bolum/], " +
                        "a[href*=/bolum/]"
                )
                .mapNotNull { fixUrlNull(it.attr("href")) }
                .firstOrNull { it.contains("/bolum/") }

            if (firstEpisode == null) {
                Log.e(
                    TAG,
                    "SERIES_FALLBACK_NO_EPISODE data=$data"
                )
                return false
            }

            Log.w(
                TAG,
                "SERIES_DIRECT_RESOLVE_FALLBACK series=$data firstEpisode=$firstEpisode"
            )

            return resolvePlayablePage(
                firstEpisode,
                subtitleCallback,
                callback
            )
        }

        return resolvePlayablePage(
            data,
            subtitleCallback,
            callback
        )
    }

    private suspend fun resolvePlayablePage(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val document = app.get(
            data,
            referer = "$mainUrl/"
        ).document

        val cfg = document
            .selectFirst("#videoContainer[data-cfg]")
            ?.attr("data-cfg")
            .orEmpty()

        if (cfg.isBlank()) {
            Log.e(
                TAG,
                "PLAYABLE_PAGE_NO_CFG data=$data title=${document.title()}"
            )
            return false
        }

        val decoded = decodeBase64Url(cfg)

        Log.d(
            TAG,
            "data-cfg decoded=$decoded"
        )

        val iframeUrl = Regex(
            """"v"\s*:\s*"([^"]+)""""
        )
            .find(decoded)
            ?.groupValues
            ?.getOrNull(1)
            ?.replace("\\/", "/")
            ?.replace("\\u0026", "&")
            .orEmpty()

        if (iframeUrl.isBlank()) {
            Log.e(
                TAG,
                "PLAYABLE_PAGE_NO_PLAYER data=$data decoded=$decoded"
            )
            return false
        }

        Log.d(
            TAG,
            "player=$iframeUrl"
        )

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
            String(
                Base64.decode(
                    padded,
                    Base64.URL_SAFE or Base64.NO_WRAP
                ),
                Charsets.UTF_8
            )
        } catch (_: Exception) {
            String(
                Base64.decode(
                    padded,
                    Base64.DEFAULT
                ),
                Charsets.UTF_8
            )
        }
    }

    companion object {
        private const val TAG = "DZP2126"
    }
}

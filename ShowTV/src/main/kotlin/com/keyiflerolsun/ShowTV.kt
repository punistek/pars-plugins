package com.keyiflerolsun

// FIXED_FOR_PARS_CLOUDSTREAM_20260828

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class ShowTV : MainAPI() {

    private val buildFixTag = "PARS-SHOWTV-FIX-20260828"

    override var mainUrl = "https://www.showtv.com.tr"
    override var name = "Show TV"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/diziler" to "Show TV Dizileri"
    )

    private data class SeriesCard(
        val title: String,
        val url: String,
        val poster: String?
    )

    private data class EpisodeCard(
        val title: String,
        val url: String,
        val poster: String?,
        val season: Int?,
        val episode: Int?
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(
            request.data,
            headers = defaultHeaders()
        ).document

        val series = parseSeries(document)
            .map { item ->
                newTvSeriesSearchResponse(
                    item.title,
                    item.url,
                    TvType.TvSeries
                ) {
                    this.posterUrl = item.poster
                }
            }

        return newHomePageResponse(
            request.name,
            series
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get(
            "$mainUrl/diziler",
            headers = defaultHeaders()
        ).document

        val normalized = query.trim().lowercase()

        return parseSeries(document)
            .filter {
                normalized.isBlank() ||
                    it.title.lowercase().contains(normalized)
            }
            .map { item ->
                newTvSeriesSearchResponse(
                    item.title,
                    item.url,
                    TvType.TvSeries
                ) {
                    this.posterUrl = item.poster
                }
            }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> {
        return search(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = defaultHeaders(referer = "$mainUrl/diziler")
        ).document

        val title = extractSeriesTitle(document)
            ?: return null

        val poster = extractSeriesPoster(document)
        val plot = extractMeta(document, "meta[name=description]", "content")
            ?: extractMeta(document, "meta[property=og:description]", "content")

        val episodes = parseEpisodes(document)

        // Bazı tanıtım sayfalarında bölüm listesi eksik/tembel yüklenmiş olabilir.
        // "Son Bölüm" veya başka bir bölüm bağlantısı varsa o sayfaya geçip
        // bütün bölüm kartlarını tekrar toplamayı dene.
        val finalEpisodes = if (episodes.isNotEmpty()) {
            episodes
        } else {
            val episodeUrl = document
                .selectFirst("a[href*=/dizi/tum_bolumler/]")
                ?.attr("href")
                ?.let(::absoluteUrl)

            if (episodeUrl != null) {
                val episodeDocument = app.get(
                    episodeUrl,
                    headers = defaultHeaders(referer = url)
                ).document
                parseEpisodes(episodeDocument)
            } else {
                emptyList()
            }
        }

        val cloudEpisodes = finalEpisodes.map { item ->
            newEpisode(item.url) {
                this.name = item.title
                this.season = item.season
                this.episode = item.episode
                this.posterUrl = item.poster
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            cloudEpisodes
        ) {
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
        val document = app.get(
            data,
            headers = defaultHeaders(referer = mainUrl)
        ).document

        var found = false

        // Show TV'nin kendi Hope Player konfigürasyonu.
        // data-hope-video içinde:
        // media.m3u8[].src
        // subtitles[].src
        val configRaw = document
            .selectFirst("[data-hope-video]")
            ?.attr("data-hope-video")
            ?.trim()
            .orEmpty()

        if (configRaw.isNotBlank()) {
            val config = try {
                jacksonObjectMapper().readValue(configRaw, HopeVideoConfig::class.java)
            } catch (_: Exception) {
                null
            }

            config?.media?.m3u8.orEmpty().forEach { stream ->
                val streamUrl = stream.src
                    ?.replace("\\/", "/")
                    ?.trim()
                    ?.takeIf { it.startsWith("http") }
                    ?: return@forEach

                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = stream.label?.ifBlank { name } ?: name,
                        url = streamUrl,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = INFER_TYPE
                    )
                )
                found = true
            }

            config?.subtitles.orEmpty().forEach { sub ->
                val subtitleUrl = sub.src
                    ?.replace("\\/", "/")
                    ?.trim()
                    ?.takeIf { it.startsWith("http") }
                    ?: return@forEach

                subtitleCallback.invoke(
                    SubtitleFile(
                        sub.label?.ifBlank { "Türkçe" } ?: "Türkçe",
                        subtitleUrl,
                        defaultHeaders(referer = data)
                    )
                )
            }
        }

        // HTML yapısı değişirse basit bir yedek çözüm.
        if (!found) {
            val html = document.html()
                .replace("\\/", "/")

            val m3u8Regex =
                Regex("""https?://[^"'\\\s<>]+\.m3u8(?:\?[^"'\\\s<>]*)?""")

            val urls = m3u8Regex
                .findAll(html)
                .map { it.value }
                .distinct()
                .toList()

            urls.forEachIndexed { index, streamUrl ->
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = if (urls.size == 1) name else "$name ${index + 1}",
                        url = streamUrl,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = INFER_TYPE
                    )
                )
                found = true
            }
        }

        // Son yedek: schema.org VideoObject içindeki contentUrl MP4.
        if (!found) {
            val html = document.html()
                .replace("\\/", "/")

            val mp4 = Regex(
                """"contentUrl"\s*:\s*"(https?://[^"]+\.mp4(?:\?[^"]*)?)""""
            ).find(html)?.groupValues?.getOrNull(1)

            if (!mp4.isNullOrBlank()) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name MP4",
                        url = mp4,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        type = INFER_TYPE
                    )
                )
                found = true
            }
        }

        return found
    }

    private fun parseSeries(document: Document): List<SeriesCard> {
        val result = LinkedHashMap<String, SeriesCard>()

        document
            .select("a[href^=/dizi/tanitim/]")
            .forEach { anchor ->
                val href = anchor.attr("href")
                    .takeIf { it.isNotBlank() }
                    ?: return@forEach

                val url = absoluteUrl(href)

                val title = anchor.attr("title")
                    .trim()
                    .ifBlank {
                        anchor.closest("li, article, div")
                            ?.selectFirst("figcaption span, h2, h3, h4")
                            ?.text()
                            ?.trim()
                            .orEmpty()
                    }
                    .ifBlank { return@forEach }

                val scope = anchor.closest("li, article, figure, div") ?: anchor

                val poster = (
                    anchor.selectFirst("img")
                        ?: scope.selectFirst("img")
                    )
                    ?.let(::imageUrl)

                result[url] = SeriesCard(
                    title = title,
                    url = url,
                    poster = poster
                )
            }

        return result.values.toList()
    }

    private fun parseEpisodes(document: Document): List<EpisodeCard> {
        val result = LinkedHashMap<String, EpisodeCard>()

        document
            .select("a[href*=/dizi/tum_bolumler/]")
            .forEach { anchor ->
                val href = anchor.attr("href")
                    .takeIf { it.isNotBlank() }
                    ?: return@forEach

                val url = absoluteUrl(href)

                val parsed = parseSeasonEpisode(url)

                val title = anchor.attr("title")
                    .trim()
                    .ifBlank {
                        when {
                            parsed.second != null -> "${parsed.second}. Bölüm"
                            else -> anchor.text().trim()
                        }
                    }
                    .ifBlank { "Bölüm" }

                val scope = anchor.closest("li, article, figure, div") ?: anchor

                val poster = (
                    anchor.selectFirst("img")
                        ?: scope.selectFirst("img")
                    )
                    ?.let(::imageUrl)

                result[url] = EpisodeCard(
                    title = title,
                    url = url,
                    poster = poster,
                    season = parsed.first,
                    episode = parsed.second
                )
            }

        return result.values
            .sortedWith(
                compareBy<EpisodeCard>(
                    { it.season ?: 0 },
                    { it.episode ?: 0 }
                )
            )
    }

    private fun parseSeasonEpisode(url: String): Pair<Int?, Int?> {
        val match = Regex(
            """sezon-(\d+)-bolum-(\d+)""",
            RegexOption.IGNORE_CASE
        ).find(url)

        val season = match?.groupValues?.getOrNull(1)?.toIntOrNull()
        val episode = match?.groupValues?.getOrNull(2)?.toIntOrNull()

        return season to episode
    }

    private fun extractSeriesTitle(document: Document): String? {
        // Önce TVSeries JSON-LD içindeki name değerini dene.
        document
            .select("script[type=application/ld+json]")
            .forEach { script ->
                val text = script.data().ifBlank { script.html() }

                if (
                    text.contains("\"@type\":\"TVSeries\"") ||
                    text.contains("\"@type\": \"TVSeries\"")
                ) {
                    Regex(
                        """"name"\s*:\s*"([^"]+)""""
                    ).find(text)?.groupValues?.getOrNull(1)?.let {
                        return decodeJsonText(it)
                    }
                }
            }

        return extractMeta(document, "meta[property=og:title]", "content")
            ?.substringBefore(" - Show TV")
            ?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.title().substringBefore(" - Show TV").trim()
    }

    private fun extractSeriesPoster(document: Document): String? {
        return extractMeta(document, "meta[property=og:image]", "content")
            ?: document
                .selectFirst("script[type=application/ld+json]")
                ?.html()
                ?.replace("\\/", "/")
                ?.let { text ->
                    Regex(
                        """"(?:thumbnailUrl|image)"\s*:\s*"(https?://[^"]+)""""
                    ).find(text)?.groupValues?.getOrNull(1)
                }
    }

    private fun extractMeta(
        document: Document,
        selector: String,
        attr: String
    ): String? {
        return document
            .selectFirst(selector)
            ?.attr(attr)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun imageUrl(img: Element): String? {
        val raw = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("src")
        ).firstOrNull {
            it.isNotBlank() &&
                !it.contains("transparent.gif", ignoreCase = true)
        } ?: return null

        return absoluteUrl(raw)
    }

    private fun absoluteUrl(url: String): String {
        val clean = url
            .replace("&amp;", "&")
            .trim()

        return when {
            clean.startsWith("https://") || clean.startsWith("http://") -> clean
            clean.startsWith("//") -> "https:$clean"
            clean.startsWith("/") -> "$mainUrl$clean"
            else -> "$mainUrl/$clean"
        }
    }

    private fun decodeJsonText(value: String): String {
        return value
            .replace("\\u015f", "ş")
            .replace("\\u015e", "Ş")
            .replace("\\u011f", "ğ")
            .replace("\\u011e", "Ğ")
            .replace("\\u0131", "ı")
            .replace("\\u0130", "İ")
            .replace("\\u00fc", "ü")
            .replace("\\u00dc", "Ü")
            .replace("\\u00f6", "ö")
            .replace("\\u00d6", "Ö")
            .replace("\\u00e7", "ç")
            .replace("\\u00c7", "Ç")
            .replace("\\/", "/")
            .replace("\\\"", "\"")
    }

    private fun defaultHeaders(
        referer: String = mainUrl
    ): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to referer
        )
    }
}

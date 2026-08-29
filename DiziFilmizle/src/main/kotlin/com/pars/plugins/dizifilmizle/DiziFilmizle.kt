package com.pars.plugins.dizifilmizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class DiziFilmizle : MainAPI() {
    override var mainUrl = "https://dizifilmizle.to"
    override var name = "DiziFilmizle"
    override val supportedTypes = setOf(TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/efsane-diziler" to "Efsane Diziler",
        "$mainUrl/imdb-7" to "IMDB 7+ Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Bu site katalog sayfasında çok büyük bir Next.js payload döndürüyor.
        // Aynı sayfayı page=2 diye bölmek yerine tüm benzersiz /dizi/ kartlarını tek istekte topluyoruz.
        val doc = app.get(request.data, headers = headers).document
        val items = parseSeriesCards(doc)
        return newHomePageResponse(request.name, items, false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        // Önce sitenin kendi arama uçlarını dene. Site değişirse katalog fallback'i eksik sonuç bırakmaz.
        val candidates = listOf(
            "$mainUrl/arama?q=${encodeQuery(q)}",
            "$mainUrl/search?q=${encodeQuery(q)}",
            "$mainUrl/yabanci-dizi-izle"
        )

        val out = LinkedHashMap<String, SearchResponse>()
        for (url in candidates) {
            runCatching {
                val doc = app.get(url, headers = headers).document
                parseSeriesCards(doc).forEach { r ->
                    if (r.name.contains(q, ignoreCase = true) || url.endsWith("yabanci-dizi-izle")) {
                        if (r.name.contains(q, ignoreCase = true)) out[r.url] = r
                    }
                }
            }
            if (out.isNotEmpty()) break
        }
        return out.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        val title = firstNonBlank(
            doc.selectFirst("h1")?.text(),
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("|")?.trim(),
            slugTitle(url)
        )

        val poster = absolute(
            firstNonBlank(
                doc.selectFirst("meta[property=og:image]")?.attr("content"),
                doc.select("img").firstOrNull {
                    it.attr("alt").contains(title, true)
                }?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            )
        )

        val plot = firstNonBlank(
            doc.selectFirst("meta[name=description]")?.attr("content"),
            doc.select("h2").firstOrNull { it.text().contains("Konusu", true) }
                ?.parent()?.selectFirst("p")?.text(),
            doc.select("p").map { it.text() }
                .firstOrNull { it.length > 120 }
        )

        val yearText = doc.body().text()
        val year = Regex("""\b(19|20)\d{2}\b""").find(yearText)?.value?.toIntOrNull()

        val tags = doc.select("a[href*=/tur/], a[href*=/genre/]")
            .map { it.text().trim() }.filter { it.isNotBlank() }.distinct()

        // Tüm sezonları gerçekten dolaş. Böylece sadece ekranda seçili olan sezon gelmez.
        val seasonUrls = LinkedHashSet<String>()
        seasonUrls += url.substringBefore("/sezon-")
        doc.select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { absolute(a.attr("href")) ?: "" }
            if (href.matches(Regex("""https?://[^/]+/dizi/[^/]+/sezon-\d+/?$"""))) {
                seasonUrls += href.trimEnd('/')
            }
        }

        // Ana dizi sayfasında görünen bölüm linklerini de al.
        val episodes = LinkedHashMap<String, Episode>()
        collectEpisodes(doc, episodes)

        // Sezon linkleri varsa her sezonu ayrı çek. Bazı diziler ana sayfada yalnız 1. sezonu render ediyor.
        val realSeasonUrls = seasonUrls.filter { it.contains("/sezon-") }
        for (seasonUrl in realSeasonUrls) {
            runCatching {
                val seasonDoc = app.get(seasonUrl, headers = headers).document
                collectEpisodes(seasonDoc, episodes)
            }
        }

        // Ana sayfada sezon linkleri DOM'a basılmamış ama metinde 1..N sezon görünüyorsa,
        // bilinen URL düzenini kontrollü şekilde tara.
        val seasonNumbers = Regex("""(\d+)\.?\s*Sezon""", RegexOption.IGNORE_CASE)
            .findAll(doc.body().text())
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..30 }.toSet()

        for (s in seasonNumbers) {
            val seasonUrl = "${url.trimEnd('/')}/sezon-$s"
            if (realSeasonUrls.any { it.trimEnd('/') == seasonUrl }) continue
            runCatching {
                val r = app.get(seasonUrl, headers = headers)
                if (r.code in 200..299) collectEpisodes(r.document, episodes)
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.values.toList()) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = absolute(data) ?: data
        val text = app.get(pageUrl, headers = headers).text

        // Next.js kaynakta alanlar \"embed_player_url_1\":\"...\" biçiminde kaçışlı da gelebiliyor.
        val normalized = text
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")

        val embeds = LinkedHashSet<String>()

        Regex(
            """"embed_player_url_[123]"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE
        ).findAll(normalized).forEach {
            cleanJsonUrl(it.groupValues[1])?.let(embeds::add)
        }

        // HTML iframe fallback
        runCatching {
            org.jsoup.Jsoup.parse(text, pageUrl).select("iframe[src]").forEach { iframe ->
                val u = iframe.absUrl("src").ifBlank { iframe.attr("src") }
                if (u.contains("vidmixi", true)) absolute(u)?.let(embeds::add)
            }
        }

        // Vidmizi embed URL'si sayfada farklı biçimde geçerse kaçırma.
        Regex("""https?://(?:www\.)?vidmi(?:xi|zi)\.com/embed/[A-Za-z0-9_-]+""",
            RegexOption.IGNORE_CASE
        ).findAll(normalized).forEach { embeds += it.value }

        var found = false

        // Önce CloudStream extractor zincirine ver. Kendi VidmiziExtractor'ımız kayıtlıdır.
        for (embed in embeds) {
            runCatching {
                loadExtractor(embed, pageUrl, subtitleCallback) { link ->
                    found = true
                    callback(link)
                }
            }
        }

        // Site bir gün doğrudan HLS/MP4 basarsa da çalışsın.
        val directPatterns = listOf(
            Regex("""https?://[^"'\\\s]+\.m3u8(?:\?[^"'\\\s]*)?""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\\\s]+\.mp4(?:\?[^"'\\\s]*)?""", RegexOption.IGNORE_CASE)
        )
        for (re in directPatterns) {
            re.findAll(normalized).forEach { m ->
                val direct = m.value.replace("\\u0026", "&").replace("\\/", "/")
                found = true
                callback(
                    newExtractorLink(
                        name,
                        if (direct.contains(".m3u8", true)) "DiziFilmizle HLS" else "DiziFilmizle MP4",
                        direct
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.Unknown.value
                        this.type = if (direct.contains(".m3u8", true))
                            ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    }
                )
            }
        }

        return found || embeds.isNotEmpty()
    }

    private fun parseSeriesCards(doc: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()

        // En güvenilir kaynak: gerçek /dizi/{slug} anchor'ları.
        doc.select("a[href]").forEach { a ->
            val href0 = a.attr("href").trim()
            if (!href0.matches(Regex("""/?dizi/[^/?#]+/?"""))) return@forEach

            val url = absolute(href0) ?: return@forEach
            val card = a.closest("div")
            val img = (a.selectFirst("img") ?: card?.selectFirst("img"))
            val imgAlt = img?.attr("alt")?.replace(Regex("""\s+izle$""", RegexOption.IGNORE_CASE), "")?.trim()

            val title = firstNonBlank(
                a.attr("aria-label"),
                a.attr("title"),
                imgAlt,
                card?.selectFirst("h2,h3")?.text(),
                slugTitle(url)
            )

            if (title.isBlank() || title.equals("İzle", true)) return@forEach

            val poster = absolute(
                firstNonBlank(
                    img?.attr("src"),
                    img?.attr("data-src"),
                    img?.attr("data-lazy-src")
                )
            )

            val year = Regex("""\b(19|20)\d{2}\b""")
                .find(card?.text().orEmpty())?.value?.toIntOrNull()

            out[url] = newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        }

        // Next.js payload fallback: DOM kart yapısı değişse bile slug/title/poster verilerini yakala.
        val raw = doc.html()
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")

        val objectRegex = Regex(
            """"slug"\s*:\s*"([^"]+)".{0,2500}?"title"\s*:\s*"([^"]+)".{0,3500}?"poster_url"\s*:\s*"([^"]+)"""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )

        objectRegex.findAll(raw).forEach { m ->
            val slug = m.groupValues[1]
            val title = unescape(m.groupValues[2])
            val poster = cleanJsonUrl(m.groupValues[3])
            if (slug.isBlank() || title.isBlank()) return@forEach
            val url = "$mainUrl/dizi/$slug"
            out.putIfAbsent(
                url,
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            )
        }

        return out.values.toList()
    }

    private fun collectEpisodes(doc: Document, out: LinkedHashMap<String, Episode>) {
        doc.select("a[href*=/sezon-][href*=/bolum-]").forEach { a ->
            val url = a.absUrl("href").ifBlank { absolute(a.attr("href")) ?: "" }
            if (url.isBlank()) return@forEach

            val m = Regex("""/sezon-(\d+)/bolum-(\d+)""", RegexOption.IGNORE_CASE).find(url)
                ?: return@forEach
            val season = m.groupValues[1].toIntOrNull()
            val episode = m.groupValues[2].toIntOrNull()

            val container = a.closest("article,li,div") ?: a
            val rawTitle = firstNonBlank(
                a.attr("title"),
                a.selectFirst("h2,h3,h4")?.text(),
                container.selectFirst("h2,h3,h4")?.text(),
                a.text()
            )

            val cleanedTitle = rawTitle
                .replace(Regex("""^\s*#?\d+\s*"""), "")
                .replace(Regex("""\b\d+\.\s*Sezon\s*\d+\.\s*Bölüm\b""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\b\d+\s*Sezon\s*\d+\s*Bölüm\b""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { "${episode ?: ""}. Bölüm" }

            val poster = container.selectFirst("img")?.let {
                absolute(it.attr("src").ifBlank { it.attr("data-src") })
            }

            val description = container.select("p").joinToString(" ") { it.text() }
                .trim().takeIf { it.length > 20 }

            out[url] = newEpisode(url) {
                this.name = cleanedTitle
                this.season = season
                this.episode = episode
                this.posterUrl = poster
                this.description = description
            }
        }
    }

    private fun absolute(url: String?): String? {
        val s = url?.trim()?.replace("\\u0026", "&")?.replace("\\/", "/")
            ?.takeIf { it.isNotBlank() } ?: return null
        return when {
            s.startsWith("http://") || s.startsWith("https://") -> s
            s.startsWith("//") -> "https:$s"
            s.startsWith("/") -> "$mainUrl$s"
            else -> "$mainUrl/$s"
        }
    }

    private fun cleanJsonUrl(s: String?): String? =
        s?.replace("\\u0026", "&")?.replace("\\/", "/")?.replace("\\", "")
            ?.trim()?.takeIf { it.startsWith("http") }

    private fun unescape(s: String): String =
        s.replace("\\u0026", "&").replace("\\/", "/").replace("\\\"", "\"")
            .replace("\\n", " ").replace("\\t", " ").replace("\\\\", "\\")

    private fun slugTitle(url: String): String =
        runCatching { URI(url).path.substringAfterLast('/').replace('-', ' ') }
            .getOrDefault(url.substringAfterLast('/').replace('-', ' '))
            .split(' ').joinToString(" ") { w ->
                w.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun firstNonBlank(vararg values: String?): String =
        values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
}

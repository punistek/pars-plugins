package com.pars.plugins.dizifilmizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

class DiziFilmizle : MainAPI() {
    override var mainUrl = "https://dizifilmizle.to"
    override var name = "DiziFilmizle"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "tr"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.7,en;q=0.6"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/#pars-popular" to "Popüler Filmler",
        "$mainUrl/#pars-hd" to "HD Film izle",
        "$mainUrl/seriler" to "Film Serileri ve Koleksiyonlar",

        "$mainUrl/tur/aile" to "Aile",
        "$mainUrl/tur/aksiyon" to "Aksiyon",
        "$mainUrl/tur/animasyon" to "Animasyon",
        "$mainUrl/tur/belgesel" to "Belgesel",
        "$mainUrl/tur/bilim-kurgu" to "Bilim Kurgu",
        "$mainUrl/tur/biyografi" to "Biyografi",
        "$mainUrl/tur/dram" to "Dram",
        "$mainUrl/tur/fantastik" to "Fantastik",
        "$mainUrl/tur/gerilim" to "Gerilim",
        "$mainUrl/tur/gizem" to "Gizem",
        "$mainUrl/tur/komedi" to "Komedi",
        "$mainUrl/tur/korku" to "Korku",
        "$mainUrl/tur/macera" to "Macera",
        "$mainUrl/tur/muzikal" to "Müzik",
        "$mainUrl/tur/romantik" to "Romantik",
        "$mainUrl/tur/savas" to "Savaş",
        "$mainUrl/tur/spor" to "Spor",
        "$mainUrl/tur/suc" to "Suç",

        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/efsane-diziler" to "Efsane Diziler",
        "$mainUrl/imdb-7" to "IMDB 7+ Diziler"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val realUrl = request.data.substringBefore('#')
        val doc = app.get(realUrl, headers = headers).document

        val allItems = when {
            request.data.endsWith("#pars-popular") ->
                parseMovieSection(doc, "Popüler Filmler")
                    .ifEmpty { parseMovieCards(doc) }

            request.data.endsWith("#pars-hd") ->
                parseMovieSection(doc, "HD Film izle")
                    .ifEmpty { parseMovieCards(doc) }

            realUrl.trimEnd('/') == "$mainUrl/seriler" -> {
                val collections = parseCollectionCards(doc)
                if (collections.isNotEmpty()) collections else parseMovieCards(doc)
            }

            realUrl.contains("/yabanci-dizi-izle") ||
                realUrl.contains("/efsane-diziler") ||
                realUrl.contains("/imdb-7") ->
                parseSeriesCards(doc)

            realUrl.contains("/tur/") -> parseMovieCards(doc)

            else -> parseMixedCards(doc)
        }

        val pageSize = 24
        val safePage = page.coerceAtLeast(1)
        val from = (safePage - 1) * pageSize

        if (from >= allItems.size) {
            return newHomePageResponse(request.name, emptyList(), false)
        }

        val to = minOf(from + pageSize, allItems.size)
        val items = allItems.subList(from, to)
        val hasNext = to < allItems.size

        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val candidates = listOf(
            "$mainUrl/arama?q=${encodeQuery(q)}",
            "$mainUrl/search?q=${encodeQuery(q)}",
            mainUrl,
            "$mainUrl/yabanci-dizi-izle"
        )

        val out = LinkedHashMap<String, SearchResponse>()

        for (url in candidates) {
            runCatching {
                val doc = app.get(url, headers = headers).document
                parseMixedCards(doc).forEach { r ->
                    if (r.name.contains(q, ignoreCase = true)) {
                        out[r.url] = r
                    }
                }
            }
            if (out.size >= 30) break
        }

        return out.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers).document

        if (url.contains("/dizi/")) {
            return loadSeries(url, doc)
        }

        if (isCollectionUrl(url)) {
            return loadCollection(url, doc)
        }

        return loadMovie(url, doc)
    }

    private suspend fun loadMovie(url: String, doc: Document): LoadResponse {
        val title = pageTitle(doc, url)
        val poster = pagePoster(doc, title)
        val plot = pagePlot(doc)
        val year = pageYear(doc)
        val tags = pageTags(doc)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    private suspend fun loadCollection(url: String, doc: Document): LoadResponse {
        val title = pageTitle(doc, url)
        val poster = pagePoster(doc, title)
        val plot = pagePlot(doc)
        val movies = parseMovieCards(doc)

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.recommendations = movies
        }
    }

    private suspend fun loadSeries(url: String, doc: Document): LoadResponse {
        val title = pageTitle(doc, url)
        val poster = pagePoster(doc, title)
        val plot = pagePlot(doc)
        val year = pageYear(doc)
        val tags = pageTags(doc)

        val seasonUrls = LinkedHashSet<String>()
        seasonUrls += url.substringBefore("/sezon-")

        doc.select("a[href]").forEach { a ->
            val href = a.absUrl("href").ifBlank { absolute(a.attr("href")) ?: "" }
            if (href.matches(Regex("""https?://[^/]+/dizi/[^/]+/sezon-\d+/?$"""))) {
                seasonUrls += href.trimEnd('/')
            }
        }

        val episodes = LinkedHashMap<String, Episode>()
        collectEpisodes(doc, episodes)

        val realSeasonUrls = seasonUrls.filter { it.contains("/sezon-") }
        for (seasonUrl in realSeasonUrls) {
            runCatching {
                val seasonDoc = app.get(seasonUrl, headers = headers).document
                collectEpisodes(seasonDoc, episodes)
            }
        }

        val seasonNumbers = Regex("""(\d+)\.?\s*Sezon""", RegexOption.IGNORE_CASE)
            .findAll(doc.body().text())
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 1..30 }
            .toSet()

        for (s in seasonNumbers) {
            val seasonUrl = "${url.trimEnd('/')}/sezon-$s"
            if (realSeasonUrls.any { it.trimEnd('/') == seasonUrl }) continue

            runCatching {
                val r = app.get(seasonUrl, headers = headers)
                if (r.code in 200..299) collectEpisodes(r.document, episodes)
            }
        }

        return newTvSeriesLoadResponse(
            title,
            url,
            TvType.TvSeries,
            episodes.values.toList()
        ) {
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

        // KRİTİK WEB FALLBACK:
        // Embed bulundu ama extractor gerçek m3u8/mp4 üretemediyse loadLinks'i boş
        // bırakma. Embed sayfasını PlayerSource olarak gönder. Uygulamadaki genel
        // Media3 -> WebView fallback mekanizması bu URL'yi açabilir.
        //
        // Böylece "loadLink yayın adresi döndürülemedi" aşamasında player daha
        // açılmadan işlem kesilmez.
        if (!found && embeds.isNotEmpty()) {
            embeds.forEach { embed ->
                callback(
                    newExtractorLink(
                        name,
                        "DiziFilmizle Web",
                        embed
                    ) {
                        this.referer = pageUrl
                        this.quality = Qualities.Unknown.value
                        this.type = ExtractorLinkType.VIDEO
                        this.headers = mapOf(
                            "Referer" to pageUrl,
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }
            found = true
        }

        // Son güvenlik ağı:
        // Sayfanın statik HTML/Next payload'ından embed dahi çıkarılamazsa yine
        // boş dönme. Filmin gerçek sayfasını Web fallback kaynağı olarak gönder.
        // WebView tarafında sitenin kendi JS player'ı çalışır.
        if (!found) {
            callback(
                newExtractorLink(
                    name,
                    "DiziFilmizle Web",
                    pageUrl
                ) {
                    this.referer = pageUrl
                    this.quality = Qualities.Unknown.value
                    this.type = ExtractorLinkType.VIDEO
                    this.headers = mapOf(
                        "Referer" to pageUrl,
                        "User-Agent" to USER_AGENT
                    )
                }
            )
            found = true
        }

        return found
    }

    private fun parseMixedCards(doc: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        parseMovieCards(doc).forEach { out[it.url] = it }
        parseSeriesCards(doc).forEach { out[it.url] = it }
        return out.values.toList()
    }

    private fun parseMovieSection(
        doc: Document,
        headingText: String
    ): List<SearchResponse> {
        val heading = doc.select("h1,h2,h3,h4").firstOrNull {
            it.text().contains(headingText, ignoreCase = true)
        } ?: return emptyList()

        val containers = listOfNotNull(
            heading.closest("section"),
            heading.parent(),
            heading.parent()?.parent()
        )

        for (container in containers) {
            val parsed = parseMovieCards(container)
            if (parsed.isNotEmpty()) return parsed
        }

        return emptyList()
    }

    private fun parseMovieCards(
        root: org.jsoup.nodes.Element
    ): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()

        // 1) Normal DOM kartları.
        root.select("a[href]").forEach { a ->
            val href0 = a.attr("href").trim()
            if (!href0.matches(Regex("""/?film/[^/?#]+/?"""))) return@forEach

            val url = absolute(href0) ?: return@forEach
            val card = a.closest("article,li,div")
            val img = a.selectFirst("img") ?: card?.selectFirst("img")

            val title = firstNonBlank(
                a.attr("aria-label"),
                a.attr("title"),
                img?.attr("alt")?.replace(
                    Regex("""\s+izle$""", RegexOption.IGNORE_CASE),
                    ""
                ),
                card?.selectFirst("h2,h3,h4")?.text(),
                slugTitle(url)
            )

            if (title.isBlank() || title.equals("İzle", true)) return@forEach

            val poster = absolute(
                firstNonBlank(
                    img?.attr("src"),
                    img?.attr("data-src"),
                    img?.attr("data-lazy-src"),
                    img?.attr("data-original")
                )
            )

            val year = Regex("""\b(19|20)\d{2}\b""")
                .find(card?.text().orEmpty())
                ?.value
                ?.toIntOrNull()

            out[url] = newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }

        // 2) Next.js / React payload fallback.
        // "Daha Fazla Film Göster" ile sonradan açılan kartların önemli bir
        // bölümü HTML içindeki serialize edilmiş payload'da bulunabiliyor.
        val raw = root.html()
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("\\\"", "\"")

        val slugMatches = Regex(
            """/film/([a-zA-Z0-9ğüşöçıİĞÜŞÖÇ_-]+)""",
            RegexOption.IGNORE_CASE
        ).findAll(raw)

        slugMatches.forEach { match ->
            val slug = match.groupValues[1].trim()
            if (slug.isBlank()) return@forEach

            val url = "$mainUrl/film/$slug"
            if (out.containsKey(url)) return@forEach

            val begin = maxOf(0, match.range.first - 2200)
            val finish = minOf(raw.length, match.range.last + 3500)
            val window = raw.substring(begin, finish)

            val title = firstNonBlank(
                Regex(
                    """"(?:title|name)"\s*:\s*"([^"]+)"""",
                    RegexOption.IGNORE_CASE
                ).find(window)?.groupValues?.getOrNull(1)?.let(::unescape),
                slugTitle(url)
            )

            val posterRaw = Regex(
                """"(?:poster_url|poster|image|image_url)"\s*:\s*"([^"]+)"""",
                RegexOption.IGNORE_CASE
            ).find(window)?.groupValues?.getOrNull(1)

            val poster = cleanJsonUrl(posterRaw)
                ?: posterRaw?.let(::absolute)

            val year = Regex("""\b(19|20)\d{2}\b""")
                .find(window)
                ?.value
                ?.toIntOrNull()

            out[url] = newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }

        return out.values.toList()
    }

    private fun parseCollectionCards(doc: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()

        doc.select("a[href]").forEach { a ->
            val href0 = a.attr("href").trim()
            val url = absolute(href0) ?: return@forEach
            if (url.trimEnd('/') == "$mainUrl/seriler") return@forEach

            val card = a.closest("article,li,div")
            val cardText = card?.text().orEmpty()

            // Sitedeki koleksiyon kartlarında "11 Film", "9 Film", "4 Film"
            // gibi sayaç rozeti var. URL yapısı değişse bile bunu kullan.
            val hasFilmCount = Regex(
                """\b\d+\s*Film\b""",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(cardText)

            if (!isCollectionPath(href0) && !hasFilmCount) return@forEach

            // Tek film/dizi detayını koleksiyon sanma.
            if (href0.matches(Regex("""/?film/[^/?#]+/?"""))) return@forEach
            if (href0.matches(Regex("""/?dizi/[^/?#]+/?"""))) return@forEach

            val img = a.selectFirst("img") ?: card?.selectFirst("img")

            val title = firstNonBlank(
                a.attr("aria-label"),
                a.attr("title"),
                card?.selectFirst("h2,h3,h4")?.text(),
                img?.attr("alt"),
                a.text(),
                slugTitle(url)
            ).replace(
                Regex("""\s+\d+\s*Film\s*$""", RegexOption.IGNORE_CASE),
                ""
            ).trim()

            if (title.isBlank() || title.equals("İzle", true)) return@forEach

            val poster = absolute(
                firstNonBlank(
                    img?.attr("src"),
                    img?.attr("data-src"),
                    img?.attr("data-lazy-src"),
                    img?.attr("data-original")
                )
            )

            out[url] = newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
            }
        }

        return out.values.toList()
    }

    private fun isCollectionPath(path: String): Boolean {
        val p = path.substringBefore('?').substringBefore('#').trim()

        return p.matches(
            Regex("""/?seriler/[^/?#]+/?""", RegexOption.IGNORE_CASE)
        ) || p.matches(
            Regex("""/?seri/[^/?#]+/?""", RegexOption.IGNORE_CASE)
        ) || p.matches(
            Regex("""/?koleksiyon/[^/?#]+/?""", RegexOption.IGNORE_CASE)
        ) || p.matches(
            Regex(
                """/?[^/?#]*(?:seri|koleksiyon)[^/?#]*/[^/?#]+/?""",
                RegexOption.IGNORE_CASE
            )
        )
    }

    private fun isCollectionUrl(url: String): Boolean {
        val path = runCatching { URI(url).path }.getOrDefault(url)
        return isCollectionPath(path)
    }

    private fun pageTitle(doc: Document, url: String): String =
        firstNonBlank(
            doc.selectFirst("h1")?.text(),
            doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBefore("|")?.trim(),
            slugTitle(url)
        )

    private fun pagePoster(doc: Document, title: String): String? =
        absolute(
            firstNonBlank(
                doc.selectFirst("meta[property=og:image]")?.attr("content"),
                doc.select("img").firstOrNull {
                    it.attr("alt").contains(title, true)
                }?.let { it.attr("src").ifBlank { it.attr("data-src") } }
            )
        )

    private fun pagePlot(doc: Document): String =
        firstNonBlank(
            doc.selectFirst("meta[name=description]")?.attr("content"),
            doc.select("h2").firstOrNull { it.text().contains("Konusu", true) }
                ?.parent()?.selectFirst("p")?.text(),
            doc.select("p").map { it.text() }.firstOrNull { it.length > 120 }
        )

    private fun pageYear(doc: Document): Int? =
        Regex("""\b(19|20)\d{2}\b""")
            .find(doc.body().text())
            ?.value
            ?.toIntOrNull()

    private fun pageTags(doc: Document): List<String> =
        doc.select("a[href*=/tur/], a[href*=/genre/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

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

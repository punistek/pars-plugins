package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element

class FullHDFilmizle : MainAPI() {
    override var mainUrl = "https://fullhdfilmizle.now"
    override var name = "FullHDFilmizle"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val ua = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/152.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "/" to "Filmler",
        "/yabanci-dizi-izle" to "Diziler",
        "/tur/aksiyon" to "Aksiyon",
        "/tur/dram" to "Dram",
        "/tur/komedi" to "Komedi",
        "/tur/korku" to "Korku",
        "/tur/macera" to "Macera",
        "/tur/bilim-kurgu" to "Bilim Kurgu"
    )

    private fun headers() = mapOf(
        "User-Agent" to ua,
        "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = selectFirst("a.mc-link")?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val title = selectFirst(".film-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: selectFirst("a.mc-link")?.attr("title")?.removeSuffix(" izle")?.trim()
            ?: return null
        val poster = selectFirst("img.mc-afis")?.let { img ->
            listOf("data-src", "data-original", "src").firstNotNullOfOrNull { key ->
                img.attr(key).takeIf { it.isNotBlank() }
            }
        }

        return newMovieSearchResponse(title, fixUrl(href), TvType.Movie) {
            this.posterUrl = poster?.let(::fixUrl)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data
        val url = if (page <= 1) fixUrl(path) else {
            val sep = if (path.contains("?")) "&" else "?"
            fixUrl("$path${sep}page=$page")
        }

        val doc = app.get(url, headers = headers()).document
        val items = doc.select("article.movie-card").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            "$mainUrl/arama?q=${query.replace(" ", "+")}",
            headers = headers()
        ).document
        return doc.select("article.movie-card").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers()).document

        val title = doc.selectFirst(".film-title-h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.replace(Regex("""\s+Full HD.*$"""), "")
                ?.trim()
            ?: throw ErrorLoadingException("Başlık bulunamadı")

        val poster = doc.selectFirst(".detail-poster img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

        val backdrop = doc.selectFirst("""link[rel=preload][as=image]""")?.attr("href")
            ?.takeIf { it.isNotBlank() }

        val overview = doc.selectFirst(".detail-synopsis")?.text()?.trim()
            ?: doc.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = Regex("""\((\d{4})\)""")
            .find(doc.title())?.groupValues?.getOrNull(1)?.toIntOrNull()

        val face = doc.selectFirst(".vp-face")
        val srcId = face?.attr("data-src-id").orEmpty()
        val srcToken = face?.attr("data-src-token").orEmpty()
        Log.i("FHD_REPO", "DETAIL srcId=$srcId tokenPresent=${srcToken.isNotBlank()}")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster?.let(::fixUrl)
            this.backgroundPosterUrl = backdrop?.let(::fixUrl)
            this.plot = overview
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers()).document
        var emitted = false

        /*
         * 1) Eski/doğrudan embed mantığını koru.
         * Sayfada iframe/vidmixi/rapidvid doğrudan görünüyorsa önce normal
         * CloudStream extractor zincirini deniyoruz.
         */
        val embeds = linkedSetOf<String>()

        doc.select("iframe[src], iframe[data-src]").forEach { frame ->
            listOf(frame.attr("src"), frame.attr("data-src"))
                .filter { it.isNotBlank() }
                .mapTo(embeds) { fixUrl(it) }
        }

        val html = doc.html()
        Regex(
            """https?://(?:www\.)?(?:vidmixi\.com|rapidvid\.(?:org|net))/[^"'\\\s<]+""",
            RegexOption.IGNORE_CASE
        ).findAll(html).forEach {
            embeds += it.value.replace("\\/", "/")
        }

        Log.i("FHD_REPO", "LOAD_LINKS directEmbeds=${embeds.size} $embeds")

        embeds.forEach { embed ->
            loadExtractor(
                embed,
                data,
                subtitleCallback
            ) { link ->
                emitted = true
                callback(link)
            }
        }

        if (emitted) {
            return true
        }

        /*
         * 2) Güncel FullHDFilmizle oynatıcı akışı:
         *
         * detail page -> .vp-face click -> site JS -> VidMixi embed
         * -> https://vidmixi.com/list/<token>
         *
         * Token algoritmasını taklit etmiyoruz. Sitenin kendi JavaScript'i
         * çalışıyor; CloudStream WebViewResolver sadece oluşan gerçek /list/
         * HLS isteğini yakalıyor.
         *
         * Kullanıcının yakaladığı /list/ cevabı doğrudan #EXTM3U master
         * playlist olduğundan player'a M3U8 olarak veriyoruz.
         */
        val playerButton = doc.selectFirst(".vp-face")
        if (playerButton == null) {
            Log.e("FHD_REPO", "VIDMIXI no .vp-face on detail page")
            return false
        }

        val srcId = playerButton.attr("data-src-id")
        val srcToken = playerButton.attr("data-src-token")
        Log.i(
            "FHD_REPO",
            "VIDMIXI bootstrap srcId=$srcId tokenPresent=${srcToken.isNotBlank()}"
        )

        val clickScript = """
            (function() {
                try {
                    var button = document.querySelector('.vp-face');
                    if (!button) return 'vp-face-not-found';
                    button.click();
                    return 'vp-face-clicked';
                } catch (e) {
                    return 'vp-face-error:' + String(e);
                }
            })();
        """.trimIndent()

        val listRegex = Regex(
            """^https://(?:www\.)?vidmixi\.com/list/.+""",
            RegexOption.IGNORE_CASE
        )

        val m3uRegex = Regex(
            """^https://(?:www\.)?vidmixi\.com/m3u/.+""",
            RegexOption.IGNORE_CASE
        )

        val resolver = WebViewResolver(
            interceptUrl = listRegex,
            additionalUrls = listOf(m3uRegex),
            userAgent = ua,
            useOkhttp = false,
            script = clickScript,
            timeout = 25_000L
        )

        val (listRequest, extraRequests) = resolver.resolveUsingWebView(
            url = data,
            referer = mainUrl,
            headers = headers()
        )

        val request = listRequest
            ?: extraRequests.firstOrNull { req ->
                listRegex.containsMatchIn(req.url.toString())
            }
            ?: extraRequests.firstOrNull { req ->
                m3uRegex.containsMatchIn(req.url.toString())
            }

        if (request == null) {
            Log.e(
                "FHD_REPO",
                "VIDMIXI resolver failed: no /list/ or /m3u/ request captured"
            )
            return false
        }

        val mediaUrl = request.url.toString()
        val requestHeaders = request.headers.names()
            .associateWith { key -> request.header(key).orEmpty() }
            .filterValues { it.isNotBlank() }

        val mediaReferer = request.header("Referer")
            ?.takeIf { it.isNotBlank() }
            ?: "https://vidmixi.com/"

        Log.i(
            "FHD_REPO",
            "VIDMIXI HLS captured url=$mediaUrl referer=$mediaReferer"
        )

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = "VidMixi",
                url = mediaUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mediaReferer
                this.headers = requestHeaders
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }
}

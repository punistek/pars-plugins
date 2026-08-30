package com.pars.plugins.izlemac

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class IzleMac : MainAPI() {

    override var mainUrl = "https://www.ardaspor30.top"
    override var name = "ArdaSpor"

    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.Live
    )

    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/151.0.0.0 Safari/537.36"

    private val pageHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to browserUserAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Referer" to "$mainUrl/"
        )

    private val streamHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to browserUserAgent,
            "Accept" to "*/*",
            "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/"
        )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Canlı Kanallar"
    )

    // ============================================================
    // ANA SAYFA
    // ============================================================

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        if (page > 1) {
            return newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }

        val document = app.get(
            request.data,
            headers = pageHeaders
        ).document

        val channels = document
            .select(".t2-kanal-kart[data-kanal]")
            .mapNotNull(::toChannelResult)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            channels,
            hasNext = false
        )
    }

    // ============================================================
    // KANAL KARTI
    // ============================================================

    private fun toChannelResult(
        element: Element
    ): SearchResponse? {

        val channelId = element
            .attr("data-kanal")
            .trim()

        if (channelId.isBlank()) {
            return null
        }

        val title =
            element
                .attr("title")
                .trim()
                .takeIf { it.isNotBlank() }
                ?: element
                    .selectFirst("img[alt]")
                    ?.attr("alt")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: element
                    .selectFirst(".t2-kanal-ad")
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                ?: channelId

        val poster = element
            .selectFirst("img[src]")
            ?.attr("src")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::absoluteUrl)

        /*
         * ÖNEMLİ:
         * Burada data-m3u8 KULLANMIYORUZ.
         *
         * CloudStream'e sadece kanalın /matches sayfasını taşıyoruz.
         * Gerçek yayın URL'si loadLinks içinde çözülecek.
         */
        val playerPage =
            "$mainUrl/matches?id=${urlEncode(channelId)}"

        return newLiveSearchResponse(
            title,
            playerPage,
            TvType.Live
        ) {
            posterUrl = poster
        }
    }

    // ============================================================
    // ARAMA
    // ============================================================

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val q = query.trim()

        if (q.isBlank()) {
            return emptyList()
        }

        val document = app.get(
            "$mainUrl/",
            headers = pageHeaders
        ).document

        return document
            .select(".t2-kanal-kart[data-kanal]")
            .mapNotNull(::toChannelResult)
            .filter {
                it.name.contains(
                    q,
                    ignoreCase = true
                )
            }
            .distinctBy { it.url }
    }

    // ============================================================
    // LOAD
    // ============================================================

    override suspend fun load(
        url: String
    ): LoadResponse {

        val channelId = getQueryParameter(
            url,
            "id"
        )

        var title = channelId
            ?.replace("-", " ")
            ?.uppercase()
            ?.takeIf { it.isNotBlank() }
            ?: name

        var poster: String? = null

        try {

            val document = app.get(
                "$mainUrl/",
                headers = pageHeaders
            ).document

            val card = document
                .select(".t2-kanal-kart[data-kanal]")
                .firstOrNull {

                    it.attr("data-kanal")
                        .trim()
                        .equals(
                            channelId,
                            ignoreCase = true
                        )
                }

            if (card != null) {

                title =
                    card
                        .attr("title")
                        .trim()
                        .takeIf { it.isNotBlank() }
                        ?: card
                            .selectFirst("img[alt]")
                            ?.attr("alt")
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                        ?: title

                poster = card
                    .selectFirst("img[src]")
                    ?.attr("src")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::absoluteUrl)
            }

        } catch (_: Throwable) {
        }

        return newLiveStreamLoadResponse(
            title,
            url,
            url
        ) {
            posterUrl = poster
            plot = "ArdaSpor canlı yayın"
        }
    }

    // ============================================================
    // LOAD LINKS
    // ============================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        /*
         * data örneği:
         *
         * https://www.ardaspor30.top/matches?id=bein1
         *
         * Kesinlikle ana sayfadaki data-m3u8 okunmayacak.
         */

        val playerResponse = try {

            app.get(
                data,
                headers = mapOf(
                    "User-Agent" to browserUserAgent,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                    "Accept-Language" to "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
                    "Cache-Control" to "no-cache",
                    "Pragma" to "no-cache",
                    "Referer" to "$mainUrl/"
                )
            )

        } catch (_: Throwable) {

            return false
        }

        val html = playerResponse.text

        /*
         * Öncelik:
         *
         * <video>
         *   <source src="GERCEK_URL.m3u8">
         * </video>
         */
        var streamUrl = playerResponse.document
            .selectFirst("video source[src]")
            ?.attr("src")
            ?.trim()
            ?.takeIf {
                it.contains(
                    ".m3u8",
                    ignoreCase = true
                )
            }

        /*
         * Bazı sayfalarda video etiketi farklı olabilir.
         */
        if (streamUrl.isNullOrBlank()) {

            streamUrl = playerResponse.document
                .selectFirst("source[src]")
                ?.attr("src")
                ?.trim()
                ?.takeIf {
                    it.contains(
                        ".m3u8",
                        ignoreCase = true
                    )
                }
        }

        /*
         * Son çare:
         * HTML / Javascript içinde doğrudan m3u8 ara.
         */
        if (streamUrl.isNullOrBlank()) {

            streamUrl = findM3u8(
                html
            )
        }

        if (streamUrl.isNullOrBlank()) {
            return false
        }

        streamUrl = normalizeStreamUrl(
            streamUrl
        )

        if (
            !streamUrl.startsWith("http://") &&
            !streamUrl.startsWith("https://")
        ) {
            return false
        }

        /*
         * GERÇEK m3u8 artık CloudStream'e veriliyor.
         *
         * Senin curl kaydındaki kritik header'lar:
         *
         * Origin:
         * https://www.ardaspor30.top
         *
         * Referer:
         * https://www.ardaspor30.top/
         */

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                headers = streamHeaders
                quality = Qualities.Unknown.value
            }
        )

        return true
    }

    // ============================================================
    // M3U8 BUL
    // ============================================================

    private fun findM3u8(
        html: String
    ): String? {

        val cleaned = html
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")

        /*
         * Önce <source src="">
         */
        val sourceRegex = Regex(
            """(?is)<source[^>]+src\s*=\s*["']([^"']+?\.m3u8(?:\?[^"']*)?)["']"""
        )

        val sourceMatch = sourceRegex
            .find(cleaned)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()

        if (!sourceMatch.isNullOrBlank()) {
            return sourceMatch
        }

        /*
         * Sonra herhangi bir HTTPS m3u8.
         */
        val directRegex = Regex(
            """https?://[^"'\\<>\s]+?\.m3u8(?:\?[^"'\\<>\s]*)?""",
            RegexOption.IGNORE_CASE
        )

        return directRegex
            .find(cleaned)
            ?.value
            ?.trim()
    }

    // ============================================================
    // STREAM URL NORMALIZE
    // ============================================================

    private fun normalizeStreamUrl(
        url: String
    ): String {

        val value = url
            .trim()
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")

        if (
            value.startsWith("http://") ||
            value.startsWith("https://")
        ) {
            return value
        }

        if (value.startsWith("//")) {
            return "https:$value"
        }

        return absoluteUrl(
            value
        )
    }

    // ============================================================
    // NORMAL URL
    // ============================================================

    private fun absoluteUrl(
        url: String
    ): String {

        val value = url.trim()

        if (value.isBlank()) {
            return ""
        }

        if (
            value.startsWith("http://") ||
            value.startsWith("https://")
        ) {
            return value
        }

        if (value.startsWith("//")) {
            return "https:$value"
        }

        return "$mainUrl/${value.trimStart('/')}"
    }

    // ============================================================
    // QUERY PARAM
    // ============================================================

    private fun getQueryParameter(
        url: String,
        key: String
    ): String? {

        return url
            .substringAfter(
                "?",
                ""
            )
            .split("&")
            .mapNotNull { part ->

                val pieces = part.split(
                    "=",
                    limit = 2
                )

                if (
                    pieces.size == 2 &&
                    pieces[0] == key
                ) {

                    try {

                        java.net.URLDecoder.decode(
                            pieces[1],
                            "UTF-8"
                        )

                    } catch (_: Throwable) {

                        pieces[1]
                    }

                } else {

                    null
                }
            }
            .firstOrNull()
    }

    // ============================================================
    // URL ENCODE
    // ============================================================

    private fun urlEncode(
        value: String
    ): String {

        return try {

            java.net.URLEncoder.encode(
                value,
                "UTF-8"
            )

        } catch (_: Throwable) {

            value
        }
    }
}

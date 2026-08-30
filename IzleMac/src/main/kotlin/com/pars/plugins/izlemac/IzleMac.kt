package com.pars.plugins.izlemac

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
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

    private val headers: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer" to "$mainUrl/"
        )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Canlı Kanallar"
    )

    // ------------------------------------------------------------
    // ANA SAYFA
    // ------------------------------------------------------------

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
            headers = headers
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

    // ------------------------------------------------------------
    // KANAL KARTI
    // ------------------------------------------------------------

    private fun toChannelResult(
        element: Element
    ): SearchResponse? {

        val channelId = element
            .attr("data-kanal")
            .trim()

        if (channelId.isBlank()) {
            return null
        }

        val title = element
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
            ?: return null

        val poster = element
            .selectFirst("img[src]")
            ?.attr("src")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let(::absoluteUrl)

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

    // ------------------------------------------------------------
    // ARAMA
    // ------------------------------------------------------------

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val q = query.trim()

        if (q.isBlank()) {
            return emptyList()
        }

        val document = app.get(
            "$mainUrl/",
            headers = headers
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

    // ------------------------------------------------------------
    // LOAD
    // ------------------------------------------------------------

    override suspend fun load(
        url: String
    ): LoadResponse {

        val channelId = getQueryParameter(
            url,
            "id"
        )

        var title = channelId
            ?.replace('-', ' ')
            ?.uppercase()
            ?.takeIf { it.isNotBlank() }
            ?: name

        var poster: String? = null

        try {
            val home = app.get(
                "$mainUrl/",
                headers = headers
            ).document

            val card = home
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

                title = card
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

        } catch (_: Exception) {
            // Ana sayfa bilgisi alınamazsa player yine denenir.
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

    // ------------------------------------------------------------
    // GERÇEK YAYIN ÇÖZÜMLEME
    // ------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val playerHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

        val response = app.get(
            data,
            headers = playerHeaders
        )

        val document = response.document

        val streamUrl =
            findM3u8(document)
                ?: findM3u8(response.text)
                ?: return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = data
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to data,
                    "Origin" to mainUrl
                )
            }
        )

        return true
    }

    // ------------------------------------------------------------
    // HTML İÇİNDEN M3U8
    // ------------------------------------------------------------

    private fun findM3u8(
        document: Document
    ): String? {

        // <video><source src="....m3u8"></video>
        document
            .select("video source[src]")
            .forEach { element ->

                val src = element
                    .attr("src")
                    .trim()

                if (
                    src.contains(
                        ".m3u8",
                        ignoreCase = true
                    )
                ) {
                    return absoluteStreamUrl(src)
                }
            }

        // src attribute içinde doğrudan m3u8
        document
            .select("[src]")
            .forEach { element ->

                val src = element
                    .attr("src")
                    .trim()

                if (
                    src.contains(
                        ".m3u8",
                        ignoreCase = true
                    )
                ) {
                    return absoluteStreamUrl(src)
                }
            }

        // href içinde m3u8
        document
            .select("a[href]")
            .forEach { element ->

                val href = element
                    .attr("href")
                    .trim()

                if (
                    href.contains(
                        ".m3u8",
                        ignoreCase = true
                    )
                ) {
                    return absoluteStreamUrl(href)
                }
            }

        // HTML / JavaScript içinden son kez ara.
        return findM3u8(
            document.html()
        )
    }

    private fun findM3u8(
        html: String
    ): String? {

        val cleaned = html
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val regex = Regex(
            """https?://[^"'\\<>\s]+?\.m3u8(?:\?[^"'\\<>\s]*)?""",
            RegexOption.IGNORE_CASE
        )

        return regex
            .find(cleaned)
            ?.value
            ?.trim()
    }

    // ------------------------------------------------------------
    // URL YARDIMCILARI
    // ------------------------------------------------------------

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

    private fun absoluteStreamUrl(
        url: String
    ): String {

        val value = url.trim()

        if (
            value.startsWith("http://") ||
            value.startsWith("https://")
        ) {
            return value
        }

        if (value.startsWith("//")) {
            return "https:$value"
        }

        return absoluteUrl(value)
    }

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

                val pieces =
                    part.split(
                        "=",
                        limit = 2
                    )

                if (
                    pieces.size == 2 &&
                    pieces[0] == key
                ) {
                    java.net.URLDecoder.decode(
                        pieces[1],
                        "UTF-8"
                    )
                } else {
                    null
                }
            }
            .firstOrNull()
    }

    private fun urlEncode(
        value: String
    ): String {

        return java.net.URLEncoder.encode(
            value,
            "UTF-8"
        )
    }
}

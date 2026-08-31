package com.pars.plugins.izlemac

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Element
import com.pars.common.ChannelNormalizer
import com.pars.common.HlsQuality
import java.net.URLDecoder
import java.net.URLEncoder

class IzleMac : MainAPI() {

    override var mainUrl = "https://www.ardaspor30.top"
    override var name = "ArdaSpor"

    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.Live
    )

    private val channelApi =
        "https://teletv5.top/load/yayinlink.php"

    private val browserUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/151.0.0.0 Safari/537.36"

    /*
     * ArdaSpor ana sayfasını çekerken kullanılacak header'lar.
     */
    private val pageHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to browserUserAgent,
            "Accept" to
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to
                "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Referer" to "$mainUrl/"
        )

    /*
     * yayinlink.php endpoint'i için.
     */
    private val apiHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to browserUserAgent,
            "Accept" to "*/*",
            "Accept-Language" to
                "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "Origin" to mainUrl,
            "Referer" to "$mainUrl/"
        )

    /*
     * Gerçek M3U8 ve segment isteklerinde kullanılacak header'lar.
     *
     * Tarayıcıda çalışan istekte gördüğümüz:
     *
     * Origin  = https://www.ardaspor30.top
     * Referer = https://www.ardaspor30.top/
     */
    private val streamHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to browserUserAgent,
            "Accept" to "*/*",
            "Accept-Language" to
                "tr-TR,tr;q=0.9,en-US;q=0.8,en;q=0.7",
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
            "$mainUrl/",
            headers = pageHeaders
        ).document

        val channels = document
            .select(".t2-kanal-kart[data-kanal]")
            .mapNotNull { element ->
                createChannelResult(element)
            }
            .distinctBy { ChannelNormalizer.key(it.name) }

        return newHomePageResponse(
            request.name,
            channels,
            hasNext = false
        )
    }

    // ============================================================
    // KANAL KARTINI CLOUDSTREAM ITEM'INA ÇEVİR
    // ============================================================

    private fun createChannelResult(
        element: Element
    ): SearchResponse? {

        /*
         * Örnek:
         *
         * data-kanal="bein-sports-1"
         *
         * Buradaki ID bizim için kritik.
         *
         * M3U8 ALMIYORUZ.
         */
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
                    .replace("-", " ")
                    .uppercase()

        val poster = element
            .selectFirst("img[src]")
            ?.attr("src")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                absoluteUrl(it)
            }

        /*
         * CloudStream içinde taşıdığımız URL.
         *
         * Bu URL açılmayacak.
         * Sadece channelId bilgisini load/loadLinks'e taşır.
         */
        val cloudstreamUrl =
            "$mainUrl/channel?id=${urlEncode(channelId)}"

        return newLiveSearchResponse(
            title,
            cloudstreamUrl,
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

        val searchText = query.trim()

        if (searchText.isBlank()) {
            return emptyList()
        }

        val document = app.get(
            "$mainUrl/",
            headers = pageHeaders
        ).document

        return document
            .select(".t2-kanal-kart[data-kanal]")
            .mapNotNull { element ->
                createChannelResult(element)
            }
            .filter {
                it.name.contains(
                    searchText,
                    ignoreCase = true
                )
            }
            .distinctBy { it.url }
    }

    // ============================================================
    // KANALA TIKLANDI
    // ============================================================

    override suspend fun load(
        url: String
    ): LoadResponse {

        val channelId = getQueryParameter(
            url,
            "id"
        )

        var title =
            channelId
                ?.replace("-", " ")
                ?.uppercase()
                ?: "ArdaSpor"

        var poster: String? = null

        /*
         * Kanal adını ve logosunu ana sayfadan tekrar buluyoruz.
         */
        if (!channelId.isNullOrBlank()) {

            try {

                val document = app.get(
                    "$mainUrl/",
                    headers = pageHeaders
                ).document

                val card = document
                    .select(".t2-kanal-kart[data-kanal]")
                    .firstOrNull { element ->

                        element
                            .attr("data-kanal")
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
                            ?: card
                                .selectFirst(".t2-kanal-ad")
                                ?.text()
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                            ?: title

                    poster = card
                        .selectFirst("img[src]")
                        ?.attr("src")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            absoluteUrl(it)
                        }
                }

            } catch (_: Throwable) {
                // İsim bulunamazsa yayın yine açılabilsin.
            }
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
    // GERÇEK YAYIN URL'SİNİ AL
    // ============================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        /*
         * Örnek data:
         *
         * https://www.ardaspor30.top/channel?id=bein-sports-1
         */

        val channelId = getQueryParameter(
            data,
            "id"
        ) ?: return false

        if (channelId.isBlank()) {
            return false
        }

        /*
         * GERÇEK ENDPOINT:
         *
         * https://teletv5.top/load/yayinlink.php?id=bein-sports-1
         */
        val apiUrl =
            "$channelApi?id=${urlEncode(channelId)}"

        val response = try {

            app.get(
                apiUrl,
                headers = apiHeaders
            )

        } catch (_: Throwable) {

            return false
        }

        /*
         * Gelen cevap örneği:
         *
         * {
         *   "deismackanal":
         *   "https://corestream.ardastream.live//beintv/tracks-v1a1/mono.m3u8"
         * }
         */
        val streamUrl = try {

            val json = JSONObject(
                response.text
            )

            json
                .optString(
                    "deismackanal",
                    ""
                )
                .trim()

        } catch (_: Throwable) {

            ""
        }

        if (streamUrl.isBlank()) {
            return false
        }

        val finalStreamUrl =
            normalizeStreamUrl(
                streamUrl
            )

        /*
         * Sadece gerçek HTTP/HTTPS stream kabul ediyoruz.
         */
        if (
            !finalStreamUrl.startsWith(
                "https://",
                ignoreCase = true
            ) &&
            !finalStreamUrl.startsWith(
                "http://",
                ignoreCase = true
            )
        ) {
            return false
        }

        /*
         * M3U8 CloudStream'e gönderiliyor.
         */
        // Master playlist ise varyantları okuyup gerçek kalite seçeneklerini çıkar.
        // Mono/media playlist ise eski davranış korunur ve tek link döner.
        val variants = try {
            val manifest = app.get(finalStreamUrl, headers = streamHeaders, timeout = 8).text
            HlsQuality.parse(finalStreamUrl, manifest)
        } catch (_: Throwable) {
            emptyList()
        }

        if (variants.isNotEmpty()) {
            variants.forEach { variant ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = if (variant.quality > 0) "$name ${variant.quality}p" else name,
                        url = variant.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        referer = "$mainUrl/"
                        headers = streamHeaders
                        quality = if (variant.quality > 0) variant.quality else Qualities.Unknown.value
                    }
                )
            }
        } else {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = finalStreamUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = "$mainUrl/"
                    headers = streamHeaders
                    quality = Qualities.Unknown.value
                }
            )
        }

        return true
    }

    // ============================================================
    // STREAM URL TEMİZLE
    // ============================================================

    private fun normalizeStreamUrl(
        url: String
    ): String {

        var value = url.trim()

        value = value
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")

        if (
            value.startsWith(
                "https://",
                ignoreCase = true
            ) ||
            value.startsWith(
                "http://",
                ignoreCase = true
            )
        ) {
            return value
        }

        if (value.startsWith("//")) {
            return "https:$value"
        }

        return value
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
            value.startsWith(
                "https://",
                ignoreCase = true
            ) ||
            value.startsWith(
                "http://",
                ignoreCase = true
            )
        ) {
            return value
        }

        if (value.startsWith("//")) {
            return "https:$value"
        }

        return "$mainUrl/${value.trimStart('/')}"
    }

    // ============================================================
    // QUERY PARAMETER OKU
    // ============================================================

    private fun getQueryParameter(
        url: String,
        key: String
    ): String? {

        val query = url.substringAfter(
            "?",
            ""
        )

        if (query.isBlank()) {
            return null
        }

        return query
            .split("&")
            .mapNotNull { part ->

                val pieces = part.split(
                    "=",
                    limit = 2
                )

                if (
                    pieces.size != 2 ||
                    pieces[0] != key
                ) {
                    return@mapNotNull null
                }

                try {

                    URLDecoder.decode(
                        pieces[1],
                        "UTF-8"
                    )

                } catch (_: Throwable) {

                    pieces[1]
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

            URLEncoder.encode(
                value,
                "UTF-8"
            )

        } catch (_: Throwable) {

            value
        }
    }
}

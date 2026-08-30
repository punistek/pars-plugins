package com.pars.plugins.izlemac

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element

class IzleMac : MainAPI() {

    override var mainUrl = "https://izlemac549.sbs"
    override var name = "IzleMac"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(TvType.Live)

    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "TV Kanalları"
    )

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
            .select("div.item.live")
            .mapNotNull(::toChannelResult)
            .distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            channels,
            hasNext = false
        )
    }

    private fun toChannelResult(root: Element): SearchResponse? {
        val link = root.selectFirst("a.dblock[href], a[href]") ?: return null

        val pageUrl = link.absUrl("href").ifBlank {
            absoluteUrl(link.attr("href"))
        }

        if (
            pageUrl.isBlank() ||
            !pageUrl.contains("/canli-mac-izle/")
        ) return null

        val title = root
            .selectFirst("strong.name.tvcp, strong.name")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        /*
         * Site kanal kimliğini CSS sınıfında da taşıyor:
         *
         * tvicx-5062
         *
         * Bunu URL'ye eklemiyoruz. Kanal kartının data alanı gerçek
         * kanal sayfası olarak kalıyor. load() içinde player URL'si
         * sayfanın kendi data-player-url değerinden okunuyor.
         */
        return newLiveSearchResponse(
            title,
            pageUrl,
            TvType.Live
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()

        val document = app.get(
            "$mainUrl/",
            headers = headers
        ).document

        return document
            .select("div.item.live")
            .mapNotNull(::toChannelResult)
            .filter { it.name.contains(q, ignoreCase = true) }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(
            url,
            headers = headers
        ).document

        val title = document
            .selectFirst("strong.name.tvcp, h1, meta[property=og:title]")
            ?.let { element ->
                if (element.tagName() == "meta") {
                    element.attr("content").substringBefore("|").trim()
                } else {
                    element.text().trim()
                }
            }
            ?.takeIf { it.isNotBlank() }
            ?: url.substringAfterLast("/canli-mac-izle/")
                .trim('/')
                .replace('-', ' ')
                .ifBlank { name }

        val description = document
            .selectFirst("meta[name=description]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val poster = document
            .selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.let(::absoluteUrl)
            ?.takeIf { it.isNotBlank() }

        /*
         * Kanal sayfasının ilan ettiği player adresini bul.
         *
         * Örnek:
         * /wp-content/themes/ikisifirbirdokuz/match-center.php?id=5062
         *
         * Selector'ları biraz geniş tuttuk; site attribute'u farklı bir
         * elemente taşısa bile public HTML'deki player URL'sini okuyabilir.
         */
        val playerUrl = findPlayerUrl(document)
            ?: throw ErrorLoadingException(
                "Kanal player adresi bulunamadı."
            )

        /*
         * Runtime'a artık kanal sayfasını değil, sayfanın açıkça ilan
         * ettiği player URL'sini data olarak veriyoruz.
         */
        return newLiveStreamLoadResponse(
            title,
            url,
            playerUrl
        ) {
            posterUrl = poster
            plot = description
        }
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        // Öncelik: data-player-url
        document.select("[data-player-url]").forEach { element ->
            val value = element.attr("data-player-url").trim()
            if (value.isNotBlank()) {
                return absoluteUrl(value)
            }
        }

        // Site yapısı değişirse match-center bağlantısını doğrudan ara.
        document.select("a[href], iframe[src], [src]").forEach { element ->
            val raw = when {
                element.hasAttr("href") -> element.attr("href")
                element.hasAttr("src") -> element.attr("src")
                else -> ""
            }.trim()

            if (raw.contains("match-center.php", ignoreCase = true)) {
                return absoluteUrl(raw)
            }
        }

        // Son fallback: HTML içinden match-center.php?id=... değerini bul.
        val html = document.html()

        val regex = Regex(
            """(?:https?:)?//[^"'<> ]*match-center\.php\?id=\d+|/[^"'<> ]*match-center\.php\?id=\d+""",
            RegexOption.IGNORE_CASE
        )

        return regex.find(html)
            ?.value
            ?.replace("&amp;", "&")
            ?.let(::absoluteUrl)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {

        /*
         * data artık:
         *
         * https://izlemac549.sbs/.../match-center.php?id=XXXX
         *
         * Burada korumalı/dinamik üçüncü taraf HLS adresi çıkarmıyoruz.
         * Runtime'ın bir web/player URL'si açma desteği varsa bu data
         * değeri doğrudan ona teslim edilebilir.
         */
        return false
    }

    private fun absoluteUrl(url: String): String {
        val value = url.trim()

        if (value.isBlank()) return ""

        if (
            value.startsWith("http://") ||
            value.startsWith("https://")
        ) return value

        if (value.startsWith("//")) {
            return "https:$value"
        }

        return "$mainUrl/${value.trimStart('/')}"
    }
}

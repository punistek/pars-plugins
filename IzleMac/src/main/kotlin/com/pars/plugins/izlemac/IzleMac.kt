package com.pars.plugins.izlemac

import com.lagradost.cloudstream3.*
import org.jsoup.nodes.Element

class IzleMac : MainAPI() {
    override var mainUrl = "https://izlemac549.sbs"
    override var name = "IzleMac"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.Live
    )

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
        // Kanal listesi tek sayfada bulunduğu için yalnız ilk sayfayı kullan.
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

        val href = link.absUrl("href").ifBlank {
            absoluteUrl(link.attr("href"))
        }

        if (
            href.isBlank() ||
            !href.contains("/canli-mac-izle/")
        ) return null

        val title = root
            .selectFirst("strong.name.tvcp, strong.name")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        // Site kanal görsellerini CSS class/ID ile tanımlıyor.
        // Sabit olmayan bir logo URL'si uydurmak yerine katalog güvenli biçimde
        // kanal adı + sayfa URL'si üzerinden oluşturuluyor.
        return newLiveSearchResponse(
            title,
            href,
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
            ?.let {
                if (it.tagName() == "meta") {
                    it.attr("content").substringBefore("|").trim()
                } else {
                    it.text().trim()
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

        return newLiveStreamLoadResponse(
            title,
            url,
            url
        ) {
            posterUrl = poster
            plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (com.lagradost.cloudstream3.utils.ExtractorLink) -> Unit
    ): Boolean {
        // V1 yalnız sitenin açık HTML kanal kataloğunu sağlar.
        // Dinamik/korumalı üçüncü taraf yayın URL'leri çözülmez.
        return false
    }

    private fun absoluteUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        return if (url.startsWith("//")) {
            "https:$url"
        } else {
            "$mainUrl/${url.trimStart('/')}"
        }
    }
}

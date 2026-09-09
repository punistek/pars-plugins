// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import android.util.Base64
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

class FullHDFilmizlesene : MainAPI() {
    override var mainUrl              = "https://fullhdfilmizle.now"
    override var name                 = "FullHDFilmizlesene"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/"                  to "En Yeni Filmler",
        "${mainUrl}/tur/aksiyon"       to "Aksiyon",
        "${mainUrl}/tur/dram"          to "Dram",
        "${mainUrl}/tur/gerilim"       to "Gerilim",
        "${mainUrl}/tur/komedi"        to "Komedi",
        "${mainUrl}/tur/korku"         to "Korku",
        "${mainUrl}/tur/macera"        to "Macera",
        "${mainUrl}/tur/fantastik"     to "Fantastik",
        "${mainUrl}/tur/bilim-kurgu"   to "Bilim Kurgu",
        "${mainUrl}/tur/gizem"         to "Gizem",
        "${mainUrl}/tur/romantik"      to "Romantik",
        "${mainUrl}/tur/suc"           to "Suç",
        "${mainUrl}/tur/savas"         to "Savaş",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = when {
            page <= 1 -> request.data
            request.data.endsWith("/") -> "${request.data}sayfa/${page}"
            else -> "${request.data}/sayfa/${page}"
        }

        val document = app.get(pageUrl).document
        val home = document.select("article.movie-card").mapNotNull { it.toSearchResult() }

        Log.d("FHD", "MAIN page=$page url=$pageUrl cards=${home.size}")
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst(".film-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val href = fixUrlNull(
            this.selectFirst("a.mc-link")?.attr("href")
                ?.takeIf { it.isNotBlank() }
                ?: this.selectFirst("a")?.attr("href")
        ) ?: return null

        val image = this.selectFirst("img.mc-afis")
            ?: this.selectFirst("img")

        val posterUrl = fixUrlNull(
            image?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: image?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: image?.attr("src")
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "${mainUrl}/arama?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=1"
        val document = app.get(searchUrl).document

        return document.select("article.movie-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(".film-title-h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst(".detail-poster img")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
        )

        val year = document
            .selectFirst(".film-facts a[href^='/yil/']")
            ?.text()
            ?.trim()
            ?.toIntOrNull()

        val description = document
            .selectFirst(".detail-synopsis")
            ?.text()
            ?.trim()

        val tags = document
            .select(".film-facts a[href^='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val rating = document
            .selectFirst(".ib-score")
            ?.text()
            ?.trim()
            ?.toRatingInt()

        val duration = Regex("""(\d{2,3})\s*(?:dk|dakika)""", RegexOption.IGNORE_CASE)
            .find(document.selectFirst(".film-facts")?.text().orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val trailer = Regex(
            """"(?:embedUrl|trailer)"\s*:\s*"([^"]+)"""",
            RegexOption.IGNORE_CASE
        ).find(document.html())?.groupValues?.getOrNull(1)
            ?.replace("\\/", "/")

        val actors = document
            .select("a[href^='/oyuncu/'], a[href*='/oyuncu/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .map { Actor(it) }

        val recommendations = document
            .select("article.movie-card")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.rating = rating
            this.duration = duration
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private fun atob(s: String): String {
        return String(Base64.decode(s, Base64.DEFAULT))
    }

    private fun rtt(s: String): String {
        fun rot13Char(c: Char): Char {
            return when (c) {
                in 'a'..'z' -> ((c - 'a' + 13) % 26 + 'a'.code).toChar()
                in 'A'..'Z' -> ((c - 'A' + 13) % 26 + 'A'.code).toChar()
                else -> c
            }
        }

        return s.map { rot13Char(it) }.joinToString("")
    }

    private fun getVideoLinks(document: Document): List<Map<String, String>> {
        val scriptContent = document
            .select("script")
            .asSequence()
            .map { script ->
                script.data().ifBlank { script.html() }
            }
            .firstOrNull {
                it.contains("scx", ignoreCase = false) &&
                        Regex("""(?:var\s+)?scx\s*=""").containsMatchIn(it)
            }
            ?.trim()
            ?: return emptyList()

        val scxData = Regex(
            """(?:var\s+)?scx\s*=\s*(\{.*?\})\s*;""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(scriptContent)?.groupValues?.getOrNull(1)
            ?: return emptyList()

        val scxMap: SCXData = jacksonObjectMapper().readValue(scxData)
        val keys             = listOf("atom", "advid", "advidprox", "proton", "fast", "fastly", "tr", "en")

        val linkList = mutableListOf<Map<String, String>>()

        for (key in keys) {
            val t = when (key) {
                "atom"      -> scxMap.atom?.sx?.t
                "advid"     -> scxMap.advid?.sx?.t
                "advidprox" -> scxMap.advidprox?.sx?.t
                "proton"    -> scxMap.proton?.sx?.t
                "fast"      -> scxMap.fast?.sx?.t
                "fastly"    -> scxMap.fastly?.sx?.t
                "tr"        -> scxMap.tr?.sx?.t
                "en"        -> scxMap.en?.sx?.t
                else        -> null
            }

            when (t) {
                is List<*> -> {
                    val links = t.filterIsInstance<String>().map { link -> atob(rtt(link)) }
                    linkList.add(mapOf(key to links.joinToString(",")))
                }
                is Map<*, *> -> {
                    val links = t.mapValues { (_, value) ->
                        if (value is String) atob(rtt(value)) else ""
                    }
                    val safeLinks = links.mapKeys { (key, _) ->
                        key?.toString() ?: "Unknown"
                    }
                    linkList.add(safeLinks)
                }
            }
        }

        return linkList
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("FHD", "data » $data")
        val document    = app.get(data).document
        val videoLinks = getVideoLinks(document)
        Log.d("FHD", "videoLinks » $videoLinks")
        if (videoLinks.isEmpty()) return false


        for (videoMap in videoLinks) {
            for ((key, value) in videoMap) {
                val videoUrl = fixUrlNull(value) ?: continue
                if (videoUrl.contains("turbo.imgz.me")) {
                    loadExtractor("${key}||${videoUrl}", "${mainUrl}/", subtitleCallback, callback)
                } else {
                    loadExtractor(videoUrl, "${mainUrl}/", subtitleCallback, callback)
                }
            }
        }

        return true
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SCXData(
        @JsonProperty("atom")      val atom: AtomData?      = null,
        @JsonProperty("advid")     val advid: AtomData?     = null,
        @JsonProperty("advidprox") val advidprox: AtomData? = null,
        @JsonProperty("proton")    val proton: AtomData?    = null,
        @JsonProperty("fast")      val fast: AtomData?      = null,
        @JsonProperty("fastly")    val fastly: AtomData?    = null,
        @JsonProperty("tr")        val tr: AtomData?        = null,
        @JsonProperty("en")        val en: AtomData?        = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class AtomData(
        @JsonProperty("sx") var sx: SXData
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class SXData(
        @JsonProperty("t") var t: Any
    )
}

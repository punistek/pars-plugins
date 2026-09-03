package com.pars.ddizi

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

class Ddizi : MainAPI() {
    override var mainUrl = "https://www.ddizi.im"
    override var name = "Ddizi"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/yeni-eklenenler7" to "Yeni Eklenenler",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/eski.diziler" to "Eski Diziler"
    )

    private fun pickImageUrl(img: org.jsoup.nodes.Element?): String? {
        if (img == null) return null
        val candidates = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("data-lazy"),
            img.attr("data-echo"),
            img.attr("srcset").substringBefore(" "),
            img.attr("src")
        )
        val raw = candidates.firstOrNull { it.isNotBlank() && !it.startsWith("data:image") }
            ?: return null
        return fixUrl(raw)
    }

    // Show/dizi sayfalarini kart olarak parse eder. Site yapisinda
    // /diziler/{id}/{slug} = dizi ana sayfasi (tum bolumler burada listelenir).
    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        doc.select("a[href*=/diziler/]").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!Regex("""/diziler/\d+/""").containsMatchIn(href)) return@forEach
            val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img")
            val title = (a.attr("title").ifBlank { a.text() }.ifBlank { img?.attr("alt").orEmpty() }).trim()
                .removeSuffix("son bölüm izle").removeSuffix("izle").trim()
            if (title.isBlank()) return@forEach
            out[href] = newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = pickImageUrl(img)
            }
        }
        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data).document
        return newHomePageResponse(request.name, parseCards(doc))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/arama/${query.replace(" ", "+")}").document
        return parseCards(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()
            ?.substringBefore(" son bölüm izle")
            ?.substringBefore(" izle")
            ?.trim()
            ?: doc.title()

        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrl(it) }

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        // Bolum linkleri /izle/{id}/{slug}.htm formatinda.
        val episodes = doc.select("a[href*=/izle/]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (!Regex("""/izle/\d+/""").containsMatchIn(href)) return@mapNotNull null
            val text = (a.attr("title").ifBlank { a.text() }).trim()
            if (text.isBlank()) return@mapNotNull null
            val epNum = Regex("""(\d+)\s*\.?\s*[Bb]ölüm""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            href to (epNum to text)
        }.distinctBy { it.first }.map { (href, info) ->
            val (epNum, text) = info
            newEpisode(href) {
                name = text
                episode = epNum
            }
        }.reversed() // en eski bolum once gelsin

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Gercek player URL'i sayfanin og:video meta etiketinde duz metin
        // olarak duruyor - JS gerektirmiyor. Ayrica sayfa icindeki iframe'e
        // de yedek olarak bakiyoruz.
        val playerUrl = doc.selectFirst("meta[property=og:video]")?.attr("content")
            ?: doc.selectFirst("iframe")?.attr("src")?.let { fixUrl(it) }
            ?: return false

        // Bu, /player/oynat/{hash} gibi kendi-domain bir yol - dogrudan
        // extractor'a (referer olarak bolum sayfasi ile) yolluyoruz.
        return loadExtractor(playerUrl, data, subtitleCallback, callback)
    }
}

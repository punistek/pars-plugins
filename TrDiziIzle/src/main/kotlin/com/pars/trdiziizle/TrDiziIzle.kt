package com.pars.trdiziizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document

/**
 * trdiziizle.tv icin CloudStream provider'i.
 *
 * Site yapisi (Ddizi.im ile ayni WP tema ailesinden - ayni "/player/oynat/{hash}"
 * yolu, ayni "Sinema Modu" / "wpfpaction=add&postid=" metinleri gozlemlendi):
 *   - Dizi sayfasi: /diziler/{slug}/            (bolum tablosu burada)
 *   - Bolum sayfasi: /{slug}/                   (kok seviyede, id yok)
 *   - Ana sayfa: /tr1/                          ("Son Eklenen Diziler" + "Son Eklenen Bolumler")
 *   - Tam dizi arsivi: /dizi-arsivi-01/
 *   - Tum bolumler: /tum-bolumler/              (dizi kartlarina donmuyor, mainPage'e eklenmedi)
 *
 * NOT: /player/oynat/{hash} sayfasinin ic yapisi (Referer korumasi nedeniyle statik
 * analizle gorulemedi) dogrudan cihazda test edilmeli. Bu nedenle extractor,
 * Ddizi'deki gibi birden fazla olasi deseni ayni anda deniyor.
 */
class TrDiziIzle : MainAPI() {
    override var mainUrl = "https://www.trdiziizle.tv"
    override var name = "TrDiziIzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/tr1/" to "Yeni Eklenenler",
        "$mainUrl/dizi-arsivi-01/" to "Tüm Diziler"
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

    // Show/dizi kartlarini parse eder. Site sayfa basina /diziler/ linki olarak
    // hem gercek "kart"lari (afis resimli) hem de A-Z atlama menusu / footer'daki
    // duz metin dizi listesini iceriyor. Ikisini ayirt etmek icin "afis resmi var mi"
    // kontrolu kullaniyoruz - nav/footer linklerinde img yok.
    private fun parseCards(doc: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()
        doc.select("a[href*=/diziler/]").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!Regex("""/diziler/[^/]+/?$""").containsMatchIn(href)) return@forEach
            val img = a.selectFirst("img") ?: a.parent()?.selectFirst("img")
            if (img == null) return@forEach // metin-only nav/footer linkini eleyerek gercek kartlari al
            val title = (a.attr("title").ifBlank { a.text() }.ifBlank { img.attr("alt") }).trim()
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
        // Site icinde ozel bir /arama/ uc noktasi gozlenmedi; WordPress varsayilan
        // arama parametresi (?s=) deneniyor.
        val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return parseCards(doc)
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()
            ?.substringBefore(" Son Bölüm izle")
            ?.substringBefore(" son bölüm izle")
            ?.substringBefore(" izle")
            ?.trim()
            ?: doc.title().substringBefore("|").trim()

        // og:image meta bu sitede yok - kapak resmi icerik alanindaki ilk
        // wp-content/uploads resmiden aliniyor ("afis" gorseli).
        val poster = doc.selectFirst("img[src*=wp-content/uploads]")?.let { pickImageUrl(it) }

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        // Bolum linkleri kok seviyede (id yok), tek ayirt edici ozellikleri
        // link metninde "Bölüm" gecmesi (ör. "Tuzlu Kahve 1.Bölüm 01 Eylül 2026").
        val episodes = doc.select("a[href]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (!href.startsWith(mainUrl)) return@mapNotNull null
            if (href.contains("/diziler/")) return@mapNotNull null
            val text = (a.attr("title").ifBlank { a.text() }).trim()
            if (!text.contains("Bölüm", ignoreCase = true)) return@mapNotNull null
            href to text
        }.distinctBy { it.first }.map { (href, text) ->
            val epNum = Regex("""(\d+)\s*\.?\s*[Bb]ölüm""").find(text)?.groupValues?.get(1)?.toIntOrNull()
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

        // Gercek player URL'i genelde bir <iframe src="/player/oynat/{hash}">
        // olarak sayfada duruyor. og:video meta'sini da yedek olarak deniyoruz
        // (Ddizi.im'de bu sekilde calisiyordu, bu sitede simdilik gozlenmedi
        // ama olmasi durumu bozmaz).
        val playerUrl = doc.selectFirst("meta[property=og:video]")?.attr("content")
            ?: doc.selectFirst("iframe[src*=/player/]")?.attr("src")?.let { fixUrl(it) }
            ?: doc.selectFirst("iframe")?.attr("src")?.let { fixUrl(it) }
            ?: return false

        return loadExtractor(playerUrl, data, subtitleCallback, callback)
    }
}

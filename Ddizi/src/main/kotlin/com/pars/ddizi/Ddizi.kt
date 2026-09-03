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

    // ddizi.im'deki dizi/bolum resimleri lazy-load ile geliyor; statik <img src="">
    // her zaman BOS - gercek adres bilinmeyen bir data-* ozniteliginde olabilir.
    // Bu yuzden once bilinen lazy-load isimlerini deniyoruz, sonra elementin
    // (ve ebeveyninin) TUM ozniteliklerini tarayip bir resim URL'sine benzeyen
    // ilk degeri, en son da olasi bir "style=background-image:url(...)" degerini
    // kullaniyoruz. Bu, sitenin tam olarak hangi ozel ozniteligi kullandigini
    // bilmesek de gercek posteri yakalama sansini artirir.
    private val imgExtRegex = Regex("""\.(jpe?g|png|webp|gif)(\?[^"'\s]*)?$""", RegexOption.IGNORE_CASE)
    private val bgUrlRegex = Regex("""url\(['"]?(https?://[^'")]+)['"]?\)""")

    private fun pickImageUrl(el: org.jsoup.nodes.Element?): String? {
        if (el == null) return null

        val known = listOf(
            "data-src", "data-lazy-src", "data-original", "data-lazy",
            "data-echo", "data-thumb", "data-thumbnail", "data-img",
            "data-image", "data-bg", "data-background", "data-poster"
        )
        for (name in known) {
            val v = el.attr(name)
            if (v.isNotBlank() && !v.startsWith("data:image")) return fixUrl(v.substringBefore(" "))
        }

        val srcset = el.attr("srcset").substringBefore(" ")
        if (srcset.isNotBlank() && !srcset.startsWith("data:image")) return fixUrl(srcset)

        val src = el.attr("src")
        if (src.isNotBlank() && !src.startsWith("data:image")) return fixUrl(src)

        // Genel yedek: elementin ve ebeveyninin TUM ozniteliklerini tara.
        val allAttrs = el.attributes().asList() + (el.parent()?.attributes()?.asList().orEmpty())
        allAttrs.map { it.value }
            .firstOrNull { it.startsWith("http") && imgExtRegex.containsMatchIn(it) }
            ?.let { return fixUrl(it) }

        // style="background-image:url(...)" biciminde olabilir.
        val style = el.attr("style") + (el.parent()?.attr("style") ?: "")
        bgUrlRegex.find(style)?.groupValues?.get(1)?.let { return fixUrl(it) }

        return null
    }

    // Show/dizi sayfalarini kart olarak parse eder. Site yapisinda
    // /diziler/{id}/{slug} = dizi ana sayfasi (tum bolumler burada listelenir).
    //
    // NOT: Sitenin HER sayfasinda (yeni-eklenenler7, yabanci-dizi-izle, eski.diziler,
    // hatta her bolum sayfasi) ayni "Yerli Diziler" A-Z metin listesi tekrar tekrar
    // cikiyor - bunlarin resmi (img etiketi) yok, sadece duz metin link. Gercek
    // kartlarin (Eski Diziler arsivi, Yabanci Diziler izgarasi) ise -kaynak bos olsa
    // bile- bir <img> etiketi VAR. Bu yuzden img etiketi OLMAYAN linkleri atlayarak
    // tekrar eden kenar-cubugu gurultusunu eliyoruz.
    private fun cleanTitle(raw: String): String {
        return raw
            .trim()
            .removeSuffix(" son bölüm izle")
            .removeSuffix(" Son Bölüm İzle")
            .removeSuffix(" izle")
            .removeSuffix(" İzle")
            .trim()
    }

    private fun parseSeriesGrid(doc: Document): List<SearchResponse> {
        val out = LinkedHashMap<String, SearchResponse>()

        // SADECE gerçek dizi kartları.
        // left_sidebar / Bugünkü Bölümler / footer gibi alanlar bu selector'a girmez.
        doc.select(".dizi-boxpost > a[href*=/diziler/]").forEach { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@forEach
            if (!Regex("""/diziler/\d+/""").containsMatchIn(href)) return@forEach

            val img = a.selectFirst("img") ?: return@forEach
            val title = cleanTitle(
                a.attr("title")
                    .ifBlank { img.attr("alt") }
                    .ifBlank { a.ownText() }
            )
            if (title.isBlank()) return@forEach

            val poster = pickImageUrl(img)
            out[href] = newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                posterUrl = poster
            }
        }

        return out.values.toList()
    }

    private fun normalizeWords(value: String): Set<String> {
        val normalized = value
            .lowercase()
            .replace('ç', 'c')
            .replace('ğ', 'g')
            .replace('ı', 'i')
            .replace('ö', 'o')
            .replace('ş', 's')
            .replace('ü', 'u')
            .replace(Regex("""[^a-z0-9 ]+"""), " ")

        val stop = setOf("ile", "ve", "bir", "son", "bolum", "sezon", "izle", "hd", "full")
        return normalized
            .split(Regex("""\s+"""))
            .map { it.trim() }
            .filter { it.length >= 2 && it !in stop && it.toIntOrNull() == null }
            .toSet()
    }

    private fun parseNewlyAddedSeries(doc: Document): List<SearchResponse> {
        // Yeni Eklenenler sayfası gerçek dizi kartı değil /izle/ bölüm kartları içeriyor.
        // Bunları sayfadaki "Yerli Diziler" ana dizi URL'leriyle eşleştirip,
        // bölüm kartındaki data-src posterini dizi kartına taşıyoruz.
        val seriesLinks = doc.select(".left_sidebar a[href*=/diziler/]")
            .mapNotNull { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                if (!Regex("""/diziler/\d+/""").containsMatchIn(href)) return@mapNotNull null
                val title = cleanTitle(a.attr("title").ifBlank { a.text() })
                if (title.isBlank()) return@mapNotNull null
                Triple(href, title, normalizeWords(title))
            }

        val out = LinkedHashMap<String, SearchResponse>()

        doc.select(".dizi-boxpost > a[href*=/izle/]").forEach { episodeCard ->
            val img = episodeCard.selectFirst("img") ?: return@forEach
            val episodeTitle = cleanTitle(
                episodeCard.attr("title")
                    .ifBlank { img.attr("alt") }
                    .ifBlank { episodeCard.ownText() }
            )
            val episodeWords = normalizeWords(episodeTitle)
            if (episodeWords.isEmpty()) return@forEach

            val best = seriesLinks
                .map { candidate ->
                    val common = candidate.third.intersect(episodeWords).size
                    val denom = minOf(candidate.third.size, episodeWords.size).coerceAtLeast(1)
                    val score = common.toDouble() / denom.toDouble()
                    Pair(candidate, score)
                }
                .filter { (candidate, score) ->
                    val common = candidate.third.intersect(episodeWords).size
                    score >= 0.60 && (common >= 2 || candidate.third.size == 1 || episodeWords.size == 1)
                }
                .maxByOrNull { it.second }
                ?.first
                ?: return@forEach

            val poster = pickImageUrl(img)
            out[best.first] = newTvSeriesSearchResponse(
                best.second,
                best.first,
                TvType.TvSeries
            ) {
                posterUrl = poster
            }
        }

        return out.values.toList()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // "Eski Diziler" arsivi cok sayfali: /eski.diziler (1. sayfa), /eski.diziler/2,
        // /eski.diziler/3 ... (gozlemde 31 sayfaya kadar gitti, ~900 dizi). "page"
        // parametresini kullanmadigimiz icin uygulama hep ilk ~30 diziyi gosteriyordu -
        // "tam dizi listesi yok" hatasinin ana kaynagi buydu. Digerlerinde (Yeni
        // Eklenenler, Yabanci Diziler) sayfalama gozlenmedi, tek sayfa olarak kalir.
        val isEskiDiziler = request.data == "$mainUrl/eski.diziler"
        val pageUrl = if (isEskiDiziler && page > 1) "$mainUrl/eski.diziler/$page" else request.data

        val doc = app.get(pageUrl).document

        val cards = when (request.data) {
            "$mainUrl/yeni-eklenenler7" -> parseNewlyAddedSeries(doc)
            else -> parseSeriesGrid(doc)
        }

        // "Sonraki »" linki varsa gidilecek baska sayfa var demektir.
        val hasNext = isEskiDiziler && doc.select("a").any { it.text().contains("Sonraki", ignoreCase = true) }

        return newHomePageResponse(request.name, cards, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/arama/${query.replace(" ", "+")}").document
        return parseSeriesGrid(doc)
    }

    // Her dizi sayfasinin sag tarafinda "Bugünkü Bölümler" adinda TAMAMEN BASKA
    // dizilerin bolum linklerini iceren bir widget var (ayni /izle/{id}/ formatinda,
    // yani onceki regex bunlari da yakaliyordu -> "bir diziye tiklayinca ustte
    // baska dizi bolumu cikmasi" hatasinin sebebi buydu). Bir bolum linkinin
    // GERCEKTEN bu diziye ait olup olmadigini iki bagimsiz sinyalle dogruluyoruz:
    //   1) href'in son parcasi (slug), dizi URL'sinden turetilen "temel slug" ile basliyor mu?
    //   2) baglanti metni dizinin (temizlenmis) basligini iceriyor mu?
    // Ikisinden biri yeterli - yanlis-negatifi (gercek bolumu atlamayi) engellemek icin.
    private fun episodeBelongsToShow(
        href: String,
        text: String,
        baseSlug: String,
        showTitle: String
    ): Boolean {
        val hrefSlug = href
            .trimEnd('/')
            .substringAfterLast("/")
            .removeSuffix(".htm")
            .lowercase()

        // Bugünkü Bölümler widget'ındaki MasterChef / Doğanın Kanunu gibi
        // başka dizileri kesin olarak dışarıda bırak.
        if (baseSlug.isNotBlank() && hrefSlug.startsWith(baseSlug.lowercase())) {
            return true
        }

        // Çok istisnai eski sayfalarda URL slug farklıysa başlık tabanlı yedek.
        // Tek kelime eşleşmesini kabul etmiyoruz; yanlış dizi karışmasını önler.
        val showWords = normalizeWords(showTitle)
        val textWords = normalizeWords(text)
        if (showWords.size < 2 || textWords.isEmpty()) return false

        val common = showWords.intersect(textWords).size
        return common >= 2 && common.toDouble() / showWords.size.toDouble() >= 0.60
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1")?.text()
            ?.substringBefore(" son bölüm izle")
            ?.substringBefore(" izle")
            ?.trim()
            ?: doc.title().substringBefore("|").trim()

        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")

        // Dizi URL'sinden bolum linklerini eslestirmek icin "temel slug" cikar.
        // Ornekler:
        //   .../diziler/1856/teskilat-188-son-bolum-izle -> "teskilat"
        //   .../diziler/2126/aynadaki-yabanci-son-bolum-izle -> "aynadaki-yabanci"
        //   .../diziler/1957/1899 -> "1899" (yabanci diziler icin ek sonek yok)
        val urlSlug = url.trimEnd('/').substringAfterLast("/")
        val baseSlug = urlSlug
            .removeSuffix("-son-bolum-izle")
            .removeSuffix("-izle")
            .replace(Regex("""-\\d+-son-bolum-izle$"""), "")
            .replace(Regex("""-\\d+$"""), "")
            .replace(Regex("""-hd\\d*$""", RegexOption.IGNORE_CASE), "")
            .trim('-')

        // Bolum linkleri /izle/{id}/{slug}.htm formatinda. Tek sayfada tum bolumler
        // olmayabilir - uzun soluklu diziler (ör. Teşkilat) icin site
        // .../sayfa-1, .../sayfa-2 ... seklinde sayfalara bolunmus. Once mevcut
        // sayfadaki (page 1 = sayfa-0) bolumleri, sonra tum diger sayfalari topluyoruz.
        val collected = LinkedHashMap<String, String>() // href -> baglanti metni

        fun harvest(d: Document) {
            d.select("a[href*=/izle/]").forEach { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@forEach
                if (!Regex("""/izle/\d+/""").containsMatchIn(href)) return@forEach
                val text = (a.attr("title").ifBlank { a.text() }).trim()
                if (text.isBlank()) return@forEach
                if (!episodeBelongsToShow(href, text, baseSlug, title)) return@forEach
                collected[href] = text
            }
        }
        harvest(doc)

        // Sayfalama linklerini bul: "{dizi-url}/sayfa-N". En yuksek N'i tespit edip
        // eksik sayfalari sirayla cekiyoruz (asiri istekten kacinmak icin makul bir
        // ust sinir koyuyoruz).
        val baseShowUrl = url.trimEnd('/')
        val maxPage = doc.select("a[href*=/sayfa-]").mapNotNull { a ->
            val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
            if (!href.startsWith(baseShowUrl)) return@mapNotNull null
            Regex("""/sayfa-(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull() ?: 0

        val safeMaxPage = minOf(maxPage, 60) // guvenlik siniri
        for (i in 1..safeMaxPage) {
            try {
                val pageDoc = app.get("$baseShowUrl/sayfa-$i").document
                harvest(pageDoc)
            } catch (e: Throwable) {
                // bu sayfa cekilemedi, digerleriyle devam et
            }
        }

        // Dizi sayfasinda (/diziler/) og:image meta etiketi YOK - bu yuzden poster
        // her zaman null donuyordu. Ancak BOLUM sayfalarinda (/izle/) og:image
        // GERCEK, statik bir CDN linki olarak duruyor (ör. .../diziresimleri/
        // teskilatkapak-min.jpg) - lazy-load tahmini gerektirmiyor. Ilk bolumun
        // sayfasini bir kez cekip oradan alıyoruz; basarisiz olursa lazy-load
        // tahminine (pickImageUrl) dusuyoruz.
        val selfPoster = doc
            .select(".dizi-boxpost > a[href*=/diziler/]")
            .firstOrNull { a ->
                fixUrlNull(a.attr("href"))?.trimEnd('/') == baseShowUrl
            }
            ?.selectFirst("img")
            ?.let(::pickImageUrl)

        val firstEpisodeHref = collected.keys.firstOrNull()
        val poster = selfPoster ?: firstEpisodeHref?.let {
            try {
                val episodeDoc = app.get(it).document
                episodeDoc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?.takeIf { value -> value.isNotBlank() }
                    ?.let { img -> fixUrl(img) }
                    ?: episodeDoc.selectFirst(".dizi-boxpost img, img[data-src*=/diziresimleri/]")
                        ?.let(::pickImageUrl)
            } catch (e: Throwable) {
                null
            }
        } ?: run {
            val posterEl = doc.select("a[href*=/izle/]").firstOrNull { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@firstOrNull false
                Regex("""/izle/\d+/""").containsMatchIn(href) &&
                    episodeBelongsToShow(href, (a.attr("title").ifBlank { a.text() }), baseSlug, title)
            }?.selectFirst("img")
            pickImageUrl(posterEl)
        }

        val episodes = collected.entries.map { (href, text) ->
            val epNum = Regex("""(\d+)\s*\.?\s*[Bb]ölüm""").find(text)?.groupValues?.get(1)?.toIntOrNull()
            Triple(href, text, epNum)
        }.sortedWith(compareBy(nullsLast()) { it.third }) // bolum numarasina gore artan sirala
            .map { (href, text, epNum) ->
                newEpisode(href) {
                    name = text
                    episode = epNum
                }
            }

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

        // Bazi bolumlerde gercek video "/player/telif/index.php?id={disaridaki_url}"
        // seklinde bir proxy uzerinden DOGRUDAN disaridaki bir barindiriciya (cogunlukla
        // YouTube) sarilarak sunuluyor (ör. id=https://www.youtube.com/watch?v=...).
        // Bunu tespit edip ic ice URL'yi cozup CloudStream'in kendi (YouTube vb.)
        // extractor'ina dogrudan yolluyoruz - en guvenilir yol bu.
        val telifEl = doc.selectFirst("a[href*=/player/telif/]") ?: doc.selectFirst("iframe[src*=/player/telif/]")
        val telifHref = telifEl?.attr("href")?.ifBlank { telifEl.attr("src") }
        val telifInnerUrl = telifHref?.let { Regex("""[?&]id=([^&]+)""").find(it)?.groupValues?.get(1) }
            ?.let { runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrNull() ?: it }
        if (!telifInnerUrl.isNullOrBlank() && telifInnerUrl.startsWith("http")) {
            if (loadExtractor(telifInnerUrl, data, subtitleCallback, callback)) return true
        }

        // Gercek player URL'i sayfanin og:video meta etiketinde duz metin
        // olarak duruyor - JS gerektirmiyor. Ayrica sayfa icindeki iframe'e
        // de yedek olarak bakiyoruz.
        val playerUrl = doc.selectFirst("meta[property=og:video]")?.attr("content")
            ?: doc.selectFirst("iframe[src*=/player/oynat/]")?.attr("src")?.let { fixUrl(it) }
            ?: doc.selectFirst("iframe")?.attr("src")?.let { fixUrl(it) }
            ?: return false

        // Bu, /player/oynat/{hash} gibi kendi-domain bir yol - dogrudan
        // extractor'a (referer olarak bolum sayfasi ile) yolluyoruz.
        return loadExtractor(playerUrl, data, subtitleCallback, callback)
    }
}

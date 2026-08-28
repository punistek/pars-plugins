package com.pars.p720pizle

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLEncoder

class P720pizle : MainAPI() {
    override var mainUrl = "https://720izle.com"
    override var name = "720pizle"
    override val hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "https://720izle.com/kategori/turkce-netflix-filmleri-izle/" to "Netflix",
        "https://720izle.com/kategori/animasyon/" to "Animasyon",
        "https://720izle.com/kategori/aksiyon/" to "Aksiyon",
        "https://720izle.com/kategori/komedi-filmleri-izlee/" to "Komedi",
        "https://720izle.com/kategori/macera-filmleri/" to "Macera",
        "https://720izle.com/kategori/korku/" to "Korku",
        "https://720izle.com/kategori/yerli-filmler/" to "Yerli Filmler"
    )

    override suspend fun getMainPage(page:Int,request:MainPageRequest):HomePageResponse {
        val url = if(page<=1) request.data else request.data + (if(request.data.contains("?")) "&" else "?") + "page=$page"
        val doc=app.get(url,headers=HEADERS).document
        return newHomePageResponse(request.name,parseCards(doc))
    }

    private fun pickImageUrl(img: org.jsoup.nodes.Element?): String? {
        if (img == null) return null
        // Siteler poster'i farkli lazy-load ozniteliklerinde tutabiliyor
        // (data-src, data-lazy-src, data-original, srcset, ya da duz src).
        // Hepsini sirayla dene, ilk dolu olani kullan.
        val candidates = listOf(
            img.attr("data-src"),
            img.attr("data-lazy-src"),
            img.attr("data-original"),
            img.attr("data-lazy"),
            img.attr("data-echo"),
            img.attr("srcset").substringBefore(" "),
            img.attr("src")
        )
        val raw = candidates.firstOrNull {
            it.isNotBlank() && !it.startsWith("data:image")
        } ?: return null
        return fixUrl(raw)
    }

    private fun parseCards(doc:Document):List<SearchResponse> {
        val out=LinkedHashMap<String,SearchResponse>()
        doc.select("a[href]").forEach { a ->
            val href=fixUrl(a.attr("href"))
            if(!DETAIL_RX.containsMatchIn(href)) return@forEach
            val img=a.selectFirst("img") ?: a.parent()?.selectFirst("img")
            val title=(a.attr("aria-label").ifBlank { a.text() }.ifBlank { img?.attr("alt").orEmpty() }).trim()
            if(title.isBlank()) return@forEach
            out[href]=newMovieSearchResponse(title,href,TvType.Movie){ posterUrl=pickImageUrl(img) }
        }
        return out.values.toList()
    }

    override suspend fun search(query:String,page:Int):SearchResponseList {
        val q=URLEncoder.encode(query,Charsets.UTF_8.name())
        val doc=app.get("$mainUrl/?s=$q",headers=HEADERS).document
        return newSearchResponseList(parseCards(doc),false)
    }

    override suspend fun load(url:String):LoadResponse? {
        val doc=app.get(url,headers=HEADERS).document
        val title=doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim() ?: return null
        val poster=doc.selectFirst("meta[property=og:image]")?.attr("content")?.let(::fixUrl)
        val plot=doc.selectFirst("meta[name=description]")?.attr("content")
        return newMovieLoadResponse(title,url,TvType.Movie,url){ posterUrl=poster; this.plot=plot }
    }

    override suspend fun loadLinks(data:String,isCasting:Boolean,subtitleCallback:(SubtitleFile)->Unit,callback:(ExtractorLink)->Unit):Boolean {
        val response=app.get(data,referer=mainUrl,headers=HEADERS)
        val html=response.text.replace("\\/", "/")
        val embeds=LinkedHashSet<String>()
        response.document.select("iframe[src]").forEach { embeds += fixUrl(it.attr("src")) }
        EMBED_RX.findAll(html).forEach { embeds += it.value }
        var found=false
        embeds.forEach { e -> loadExtractor(e,data,subtitleCallback){found=true;callback(it)} }
        return found
    }

    companion object {
        private val DETAIL_RX=Regex("""/filmler11/""",RegexOption.IGNORE_CASE)
        private val EMBED_RX=Regex("""https?://[^'"\s<>]*(?:embed|player|play|video|vid|stream)[^'"\s<>]*""",RegexOption.IGNORE_CASE)
        private val HEADERS=mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36")
    }
}

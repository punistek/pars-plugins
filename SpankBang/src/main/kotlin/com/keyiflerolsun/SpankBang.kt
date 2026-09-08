package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SpankBang : MainAPI() {

    override var mainUrl = "https://spankbang.com"
    override var name = "SpankBang"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/152.0.0.0 Safari/537.36"

    private val pageHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9,tr;q=0.8",
        "Cookie" to "age_pass=1; cookie_consent_required=0; show_cookie_consent_modal=0"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/new_videos/" to "New",
        "$mainUrl/trending_videos/" to "Trend",
        "$mainUrl/most_popular/" to "Popular",
        "$mainUrl/s/asian/" to "Asian",
        "$mainUrl/s/teen/" to "Teen",
        "$mainUrl/s/onlyfans/" to "OnlyFans",
        "$mainUrl/s/amateur/" to "Amateur",
        "$mainUrl/s/milf/" to "MILF",
        "$mainUrl/s/lesbian/" to "Lesbian",
        "$mainUrl/s/anal/" to "Anal",
        "$mainUrl/s/creampie/" to "Creampie"
    )

    private fun pageUrl(base: String, page: Int, order: String = "popular"): String {
        val clean = base.trimEnd('/')
        return if (page <= 1) {
            "$clean/?o=$order&p=w&d=10"
        } else {
            "$clean/$page/?o=$order&p=w&d=10"
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = pageUrl(request.data, page)
        Log.d("SkBg", "MAIN » $url")

        val document = app.get(
            url,
            headers = pageHeaders,
            referer = "$mainUrl/"
        ).document

        val home = document
            .select("[data-testid=video-item], div.video-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        Log.d("SkBg", "MAIN items=${home.size}")

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        // Güncel yapı:
        // <div data-testid="video-item">
        //   <a href="/.../video/..." ...><picture><img src="..."></picture></a>
        //   ...
        //   <p><a href="/.../video/..." title="...">...</a></p>
        // </div>
        val videoLink = selectFirst("a[href*='/video/']") ?: return null
        val href = fixUrlNull(videoLink.attr("href")) ?: return null
        if (!href.contains("/video/")) return null

        val titleLink = selectFirst("p a[href*='/video/'][title], a[href*='/video/'][title]")
        val img = selectFirst("picture img, img")

        val title = fixTitle(
            titleLink?.attr("title").orEmpty()
                .ifBlank { titleLink?.text().orEmpty() }
                .ifBlank { img?.attr("alt").orEmpty() }
        )
        if (title.isBlank()) return null

        val posterRaw = listOf(
            img?.attr("src"),
            img?.attr("data-src"),
            img?.attr("data-original"),
            img?.attr("data-lazy-src")
        ).firstOrNull { !it.isNullOrBlank() }

        val poster = fixUrlNull(posterRaw)

        Log.d("SkBg", "ITEM » $title | $href | poster=$poster")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val safeQuery = query.trim().replace(" ", "+")

        for (page in 1..5) {
            val base = "$mainUrl/s/$safeQuery/"
            val url = if (page == 1) {
                "${base}?o=new&d=10"
            } else {
                "${base}${page}/?o=new&d=10"
            }

            val document = app.get(
                url,
                headers = pageHeaders,
                referer = "$mainUrl/"
            ).document

            val pageResults = document
                .select("[data-testid=video-item], div.video-item")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }

            if (pageResults.isEmpty()) break

            val before = results.size
            pageResults.forEach { item ->
                if (results.none { it.url == item.url }) results.add(item)
            }
            if (results.size == before) break
        }

        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(
            url,
            headers = pageHeaders,
            referer = "$mainUrl/"
        ).document

        val title = document.selectFirst("[data-testid=video-title], div#video h1, h1")
            ?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("#player_cover_img")?.attr("src")
        )

        val description =
            document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: title

        val year = Regex(""""uploadDate"\s*:\s*"(\d{4})""")
            .find(document.html())
            ?.groupValues?.getOrNull(1)
            ?.toIntOrNull()

        val tags = document.select("a[href*='/s/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val durationSeconds =
            document.selectFirst("meta[property='og:video:duration']")?.attr("content")?.toIntOrNull()
                ?: Regex("""['"]length['"]\s*:\s*(\d+)""")
                    .find(document.html())
                    ?.groupValues?.getOrNull(1)
                    ?.toIntOrNull()

        val recommendations = document
            .select("[data-testid=video-item], div.video-item")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }
            .take(20)

        val actors = document.select("li.primary_actions_container").mapNotNull {
            val actorName = it.selectFirst("span.name")?.text()?.trim()
                ?.takeIf { actor -> actor.isNotBlank() }
                ?: return@mapNotNull null
            Actor(actorName, fixUrlNull(it.selectFirst("img")?.attr("src")))
        }

        Log.d("SkBg", "LOAD » $title | poster=$poster | duration=$durationSeconds")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            posterUrl = poster
            plot = description
            this.year = year
            this.tags = tags
            this.duration = durationSeconds?.div(60)
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    private fun decodeJsUrl(raw: String): String {
        return raw
            .trim()
            .trim('"', '\'', '[', ']', ' ')
            .replace("\\/", "/")
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("\\x26", "&", ignoreCase = true)
            .replace("&amp;", "&")
    }

    private fun qualityFrom(label: String, url: String): Int {
        val text = "$label $url"

        if (Regex("""(?i)(?:^|[^0-9])4k(?:[^0-9]|$)""").containsMatchIn(text)) {
            return Qualities.P2160.value
        }

        val q = Regex("""(?i)(2160|1440|1080|720|480|360|320|240)p?""")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return when (q) {
            2160 -> Qualities.P2160.value
            1440 -> Qualities.P1440.value
            1080 -> Qualities.P1080.value
            720 -> Qualities.P720.value
            480 -> Qualities.P480.value
            360 -> Qualities.P360.value
            320 -> Qualities.P360.value
            240 -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }

    private fun extractStaticStreams(html: String): List<Pair<String, String>> {
        val streams = linkedMapOf<String, String>()

        // Güncel SpankBang sayfasında doğrulanan yapı:
        // var stream_data = {'240p': ['https://...mp4?...'], ...,
        //                    'm3u8': ['https://...master.m3u8?...'], ...};
        val streamData = Regex(
            """(?is)var\s+stream_data\s*=\s*\{(.*?)\}\s*;"""
        ).find(html)?.groupValues?.getOrNull(1)

        if (!streamData.isNullOrBlank()) {
            Regex(
                """(?is)['"]([^'"]+)['"]\s*:\s*\[\s*['"]([^'"]+)['"]\s*]"""
            ).findAll(streamData).forEach { match ->
                val label = match.groupValues[1]
                val videoUrl = decodeJsUrl(match.groupValues[2])
                if (
                    videoUrl.startsWith("http") &&
                    (videoUrl.contains(".mp4", true) ||
                        videoUrl.contains(".m3u8", true) ||
                        videoUrl.contains(".mpd", true))
                ) {
                    streams[videoUrl] = label
                }
            }
        }

        // Eski sayfa yapısıyla geriye uyumluluk.
        Regex(
            """(?i)stream_url_([A-Za-z0-9_-]+)\s*=\s*["']([^"']+)["']"""
        ).findAll(html).forEach { match ->
            val label = match.groupValues[1]
            val videoUrl = decodeJsUrl(match.groupValues[2])
            if (videoUrl.startsWith("http")) streams.putIfAbsent(videoUrl, label)
        }

        // Obje/JSON biçimi fallback.
        Regex(
            """(?i)["']?((?:2160|1440|1080|720|480|360|320|240)p?|4k|m3u8[^"':,\s]*)["']?\s*:\s*(?:\[\s*)?["']([^"']+\.(?:mp4|m3u8|mpd)[^"']*)["']"""
        ).findAll(html).forEach { match ->
            val label = match.groupValues[1]
            val videoUrl = decodeJsUrl(match.groupValues[2])
            if (videoUrl.startsWith("http")) streams.putIfAbsent(videoUrl, label)
        }

        Log.d("SkBg", "STATIC streams=${streams.size}")
        return streams.map { it.key to it.value }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("SkBg", "LINKS data » $data")

        val response = app.get(
            data,
            headers = pageHeaders,
            referer = "$mainUrl/"
        )

        val html = response.text
        val streams = extractStaticStreams(html)

        if (streams.isEmpty()) {
            Log.e("SkBg", "No playable stream found in stream_data")
            return false
        }

        Log.d("SkBg", "LINKS streams=${streams.size}")

        streams
            .distinctBy { it.first }
            .sortedByDescending { qualityFrom(it.second, it.first) }
            .forEach { (videoUrl, label) ->
                val quality = qualityFrom(label, videoUrl)

                val cleanLabel = when {
                    label.equals("m3u8", true) -> "Auto HLS"
                    label.startsWith("m3u8_", true) -> label.removePrefix("m3u8_").uppercase()
                    else -> label.uppercase()
                }

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name $cleanLabel",
                        url = videoUrl,
                        type = INFER_TYPE
                    ) {
                        referer = "$mainUrl/"
                        this.quality = quality
                        headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to "$mainUrl/",
                            "Origin" to mainUrl,
                            "Accept" to "*/*"
                        )
                    }
                )
            }

        return true
    }
}

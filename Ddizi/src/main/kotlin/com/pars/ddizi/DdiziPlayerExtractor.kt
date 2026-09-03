package com.pars.ddizi

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

/**
 * ddizi.im'in kendi domaininde barinan /player/oynat/{hash} sayfasini cozer.
 * Bu sayfa dogrudan Referer'siz istekte 404 donuyor (basit hotlink korumasi,
 * FilmMakinesi/720izle'deki gibi AES sifreleme YOK) - CloudStream'in normal
 * app.get(url, referer=...) cagrisi bunu dogal olarak asmali.
 *
 * Sayfanin ic yapisini gormedigimiz icin (referer gerektirdigi icin statik
 * analizle ulasilamadi), birkac olasi deseni ayni anda deniyoruz:
 *   1) Sayfa icinde baska bir <iframe> varsa (gercek video host'una yonlendirme)
 *   2) Duz metin m3u8/mp4 linki varsa
 *   3) JWPlayer/Clappr tarzi "file:"/"source:" JS degiskeni varsa
 * Hicbiri bulunamazsa detayli log birakiyor, boylece Logcat'ten kesin
 * teshis konulabilir.
 */
class DdiziPlayerExtractor : ExtractorApi() {
    override val name = "Ddizi Player"
    override val mainUrl = "https://www.ddizi.im"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val pageReferer = referer ?: "$mainUrl/"

        val response = try {
            app.get(
                url,
                referer = pageReferer,
                headers = mapOf("User-Agent" to USER_AGENT)
            )
        } catch (e: Throwable) {
            Log.e(TAG, "FETCH_ERROR url=$url error=$e")
            return null
        }

        if (response.code !in 200..299) {
            Log.e(TAG, "FETCH_BAD_STATUS url=$url status=${response.code}")
            return null
        }

        val html = response.text.replace("\\/", "/")
        Log.i(TAG, "PLAYER_HTML url=$url len=${html.length} preview=${html.take(400)}")

        val out = mutableListOf<ExtractorLink>()

        // 1) Duz metin m3u8/mpd linki var mi?
        val m3u8 = Regex("""https?://[^"'\s<>]+\.m3u8[^"'\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.value }.toList()
        val mpd = Regex("""https?://[^"'\s<>]+\.mpd[^"'\s<>]*""", RegexOption.IGNORE_CASE)
            .findAll(html).map { it.value }.toList()

        Log.i(TAG, "PLAYER_DIRECT_LINKS m3u8=${m3u8.size} mpd=${mpd.size}")

        m3u8.forEach { link ->
            out += newExtractorLink(name, name, link, ExtractorLinkType.M3U8) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        }
        mpd.forEach { link ->
            out += newExtractorLink(name, name, link, ExtractorLinkType.DASH) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        }

        if (out.isNotEmpty()) return out

        // 2) Ic ice baska bir iframe var mi? (gercek video host'una yonlendirme)
        val nestedIframe = Regex("""<iframe[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
        if (!nestedIframe.isNullOrBlank()) {
            val nestedUrl = if (nestedIframe.startsWith("http")) nestedIframe else "$mainUrl$nestedIframe"
            Log.i(TAG, "PLAYER_NESTED_IFRAME url=$nestedUrl")
            val nested = loadExtractor(nestedUrl, url, subtitleCallback = {}) { link -> out += link }
            if (out.isNotEmpty()) return out
            Log.i(TAG, "PLAYER_NESTED_IFRAME_EXTRACT_RESULT success=$nested")
        }

        // 3) JWPlayer/Clappr tarzi "file"/"source" JS degiskeni var mi?
        val jsSource = Regex("""["'](?:file|source|src)["']\s*:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)
        if (!jsSource.isNullOrBlank()) {
            Log.i(TAG, "PLAYER_JS_SOURCE_FOUND url=$jsSource")
            val isM3u8 = jsSource.contains(".m3u8", ignoreCase = true)
            out += newExtractorLink(
                name, name, jsSource,
                if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
            return out
        }

        Log.i(TAG, "PLAYER_NO_MATCH_FOUND url=$url - hicbir desen eslesmedi, HTML'i manuel incele")
        return out.ifEmpty { null }
    }

    companion object {
        private const val TAG = "DDIZI_PLAYER"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}

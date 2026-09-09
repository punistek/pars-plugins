// ! RapidVidExtractor.kt
// RapidVid /vod sayfasindaki window._p8 verisini cozer.
// Akis: /vx -> /vod -> window._p8 -> decode -> JSON(vk/cm/tm) -> HLS master

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

open class RapidVid : ExtractorApi() {

    override val name = "RapidVid"
    override val mainUrl = "https://rapidvid.org"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: "https://www.fullhdfilmizlesene.now/"

        // FullHD tarafindan gelen URL genelde:
        // https://rapidvid.org/vx/v1x........
        // Yeni player verisi /vod/ sayfasinda window._p8 icinde.
        val vodUrl = normalizeVodUrl(url)

        Log.d("Kekik_RapidVid", "input   » $url")
        Log.d("Kekik_RapidVid", "vodUrl  » $vodUrl")
        Log.d("Kekik_RapidVid", "referer » $extRef")

        val response = app.get(
            vodUrl,
            referer = extRef,
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
        )

        val html = response.text

        if (html.isBlank()) {
            throw ErrorLoadingException("RapidVid HTML bos geldi")
        }

        if (
            html.contains("Just a moment", ignoreCase = true) ||
            html.contains("cf-chl-", ignoreCase = true)
        ) {
            throw ErrorLoadingException("RapidVid Cloudflare challenge dondu")
        }

        // Eski/acik altyazi yapisi varsa kaybetme.
        extractSubtitles(html, subtitleCallback)

        val p8 = extractP8(html)
            ?: throw ErrorLoadingException("RapidVid window._p8 bulunamadi")

        Log.d("Kekik_RapidVid", "_p8 bulundu » len=${p8.length}")

        val decodedJson = try {
            decodeP8(p8)
        } catch (e: Exception) {
            Log.e("Kekik_RapidVid", "_p8 decode hatasi", e)
            throw ErrorLoadingException("RapidVid _p8 decode edilemedi: ${e.message}")
        }

        Log.d(
            "Kekik_RapidVid",
            "_p8 JSON » ${decodedJson.take(500)}"
        )

        val data = try {
            JSONObject(decodedJson)
        } catch (e: Exception) {
            Log.e("Kekik_RapidVid", "JSON parse hatasi", e)
            throw ErrorLoadingException("RapidVid _p8 sonucu JSON degil")
        }

        val vkey = data.optString("vk", "").trim()
        val cm = data.optString("cm", "").trim()
        val tm = data.optString("tm", "").trim()

        Log.d("Kekik_RapidVid", "vkey » $vkey")
        Log.d("Kekik_RapidVid", "cm   » ${safeUrlForLog(cm)}")
        Log.d("Kekik_RapidVid", "tm   » ${safeUrlForLog(tm)}")

        /*
         * RapidVid JS:
         *
         *   var G = t.cm || "";
         *   var J = t.tm || "";
         *   ...
         *   var W = Z ? J : G;
         *   sources: [{ file: W, type: "hls" }]
         *
         * Tarayicida cm/tm ikisi de HLS master olarak kullanilabiliyor.
         * Ilk tercih cm. Bos/uygunsuzsa tm fallback.
         */
        val masterUrl = when {
            isHttpUrl(cm) -> cm
            isHttpUrl(tm) -> tm
            else -> throw ErrorLoadingException(
                "RapidVid HLS master bulunamadi (cm/tm bos)"
            )
        }

        Log.d(
            "Kekik_RapidVid",
            "MASTER » ${safeUrlForLog(masterUrl)}"
        )

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = masterUrl,
                type = ExtractorLinkType.M3U8
            ) {
                // CDN isteklerinde tarayicida Origin rapidvid.org goruldu.
                this.referer = "$mainUrl/"
                this.headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/"
                )
                this.quality = Qualities.Unknown.value
            }
        )
    }

    /**
     * /vx/v1x... -> /vod/v1x...
     *
     * /vod zaten gelirse aynen kullanir.
     * rapidvid.net gelirse rapidvid.org'a tasir.
     */
    private fun normalizeVodUrl(input: String): String {
        var out = input.trim()

        if (out.startsWith("//")) {
            out = "https:$out"
        }

        out = out
            .replace("https://www.rapidvid.org", mainUrl)
            .replace("https://rapidvid.net", mainUrl)
            .replace("https://www.rapidvid.net", mainUrl)

        out = out.replace("/vx/", "/vod/")

        return out
    }

    /**
     * RapidVid HTML:
     *
     * window._p8 = "....";
     *
     * Tek veya cift tirnak desteklenir.
     */
    private fun extractP8(html: String): String? {
        val doubleQuoted = Regex(
            """window\._p8\s*=\s*"([^"]+)""""
        ).find(html)?.groupValues?.getOrNull(1)

        if (!doubleQuoted.isNullOrBlank()) {
            return doubleQuoted
        }

        val singleQuoted = Regex(
            """window\._p8\s*=\s*'([^']+)'"""
        ).find(html)?.groupValues?.getOrNull(1)

        return singleQuoted?.takeIf { it.isNotBlank() }
    }

    /**
     * RapidVid sayfasindaki f(window._p8) fonksiyonunun Kotlin karsiligi.
     *
     * JS mantigi:
     *
     * 1. _p8 stringini ters cevir
     * 2. atob(...)
     * 3. Her karakter icin:
     *      key = "K9L"[i % 3]
     *      shift = key.charCodeAt(0) % 5 + 1
     *      charCode - shift
     * 4. Olusan stringe tekrar atob(...)
     * 5. Sonuc JSON: vk / cm / tm ...
     */
    private fun decodeP8(encoded: String): String {
        if (encoded.isBlank()) {
            throw IllegalArgumentException("_p8 bos")
        }

        // JS atob() binary-string mantigi icin ISO-8859-1 kullaniliyor.
        val firstBytes = Base64.decode(
            normalizeBase64(encoded.reversed()),
            Base64.DEFAULT
        )

        val key = "K9L"
        val transformed = StringBuilder(firstBytes.size)

        for (i in firstBytes.indices) {
            val unsignedByte = firstBytes[i].toInt() and 0xFF
            val keyCode = key[i % key.length].code
            val shift = (keyCode % 5) + 1

            transformed.append(
                (unsignedByte - shift).toChar()
            )
        }

        val secondBytes = Base64.decode(
            normalizeBase64(transformed.toString()),
            Base64.DEFAULT
        )

        return secondBytes.toString(Charsets.UTF_8)
    }

    private fun normalizeBase64(value: String): String {
        var out = value
            .trim()
            .replace("\r", "")
            .replace("\n", "")
            .replace(" ", "")
            .replace('-', '+')
            .replace('_', '/')

        val remainder = out.length % 4
        if (remainder != 0) {
            out += "=".repeat(4 - remainder)
        }

        return out
    }

    private fun extractSubtitles(
        html: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val seen = mutableSetOf<String>()

        Regex(
            """"captions","file":"([^"]+)","label":"([^"]+)""""
        ).findAll(html).forEach { match ->
            val subUrl = match.groupValues[1]
                .replace("\\/", "/")
                .replace("\\", "")

            val subLang = match.groupValues[2]
                .replace("\\u0131", "ı")
                .replace("\\u0130", "İ")
                .replace("\\u00fc", "ü")
                .replace("\\u00e7", "ç")
                .replace("\\u011f", "ğ")
                .replace("\\u015f", "ş")

            if (subUrl.isBlank() || !seen.add(subUrl)) {
                return@forEach
            }

            subtitleCallback.invoke(
                SubtitleFile(
                    lang = subLang,
                    url = subUrl
                )
            )
        }
    }

    private fun isHttpUrl(value: String): Boolean {
        return value.startsWith("https://") ||
            value.startsWith("http://")
    }

    private fun safeUrlForLog(value: String): String {
        if (value.isBlank()) return "<bos>"

        val schemeEnd = value.indexOf("://")
        if (schemeEnd == -1) {
            return value.take(80)
        }

        val pathStart = value.indexOf('/', schemeEnd + 3)
        if (pathStart == -1) {
            return value
        }

        val nextSlash = value.indexOf('/', pathStart + 1)
        return if (nextSlash == -1) {
            value
        } else {
            value.substring(0, nextSlash) + "/..."
        }
    }
}

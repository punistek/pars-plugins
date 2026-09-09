package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class RapidVid : ExtractorApi() {
    override val name            = "RapidVid"
    override val mainUrl         = "https://rapidvid.org"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: "https://www.fullhdfilmizlesene.now/"

        // Cloudflare cookie'leri (süresi dolunca güncellemek gerekir)
        val cookies = mapOf(
            "_di" to "WZZBHIIbs4KUvoH_6vIGJg",
            "cf_clearance" to "uX0_EFEFOQNbMPKTNAnQ9sOvlUp34OoPf13Wv4VK_sA-1788962711-1.2.1.1-Qti63ZDsNOpZo9SGRE4fV3ZXNM8KotXdr8ydifhMWqsXYarkMSLllxAkh20sVE96eXj0w22aoWNY_sUEIqWhO3bGL6SNuo6_FTbpjXWYZ4F7cev7tZBkz74gZArCNxoBPFi2GcwWG4hM9FZIR4tnbEwS3gXBAgbYJI8YX25rcwHBnghx1tvvhGVUBRz9HtuRFZtugKV_PNl3DB3RD6WTvFIx_gbNK_VVtoautOF2r7JaQ3bTSYmn.POO102LKEcU9hSE95nU293aKdsBQzMnifB2c4xgmvM_O69CXpPhztAxcRleossOA6_f0Nq5YFBkscr3sO9VqggTOCOE_6cZFeQo3tQ8VaMyFue9uWxU_QU"
        )

        // Sayfayı cookie ile çek
        val html = app.get(
            url,
            referer = extRef,
            headers = mapOf("Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
        ).text

        // Altyazılar
        Regex("""captions","file":"([^"]+)","label":"([^"]+)"""").findAll(html).forEach {
            val (subUrl, subLang) = it.destructured
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = subLang.replace("\\u0131", "ı").replace("\\u0130", "İ"),
                    url = subUrl.replace("\\", "")
                )
            )
        }

        // CDN linkini bul (imgscdn)
        val cdnUrl = Regex("""(https://s\d+\.imgscdn\d+\.shop/[^"']+)""").find(html)?.groupValues?.get(1)

        if (cdnUrl != null) {
            // CDN'den m3u8'yi al
            val cdnResponse = app.get(
                cdnUrl,
                referer = mainUrl,
                headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to mainUrl,
                    "Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                )
            ).text

            val finalM3u8 = Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)""").find(cdnResponse)?.groupValues?.get(1)
                ?: cdnUrl

            Log.d("Kekik_RapidVid", "M3U8: $finalM3u8")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = finalM3u8,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.headers = mapOf(
                        "Origin" to mainUrl,
                        "Referer" to mainUrl,
                        "Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                    )
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // Fallback
        val videoUrl = Regex("""file":\s*"([^"]+\.m3u8[^"]*)""").find(html)?.groupValues?.get(1)
            ?: Regex("""src":\s*"([^"]+\.m3u8[^"]*)""").find(html)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("Video kaynağı bulunamadı")

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.headers = mapOf(
                    "Origin" to mainUrl,
                    "Referer" to mainUrl,
                    "Cookie" to cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
                )
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

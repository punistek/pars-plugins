package turkspor.taraftarium

import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

data class SportsChannel(val id: String, val title: String, val category: String, val player: String, val time: String)

object SportsParser {
    private val hostPattern = Regex("^(www\\.)?(taraftarium[0-9]*\\.(xyz|ch)|inattv[0-9]+\\.xyz)$", RegexOption.IGNORE_CASE)
    fun siteUrl(value: String): String? = runCatching {
        val uri = URI(value.trim())
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443) || !hostPattern.matches(uri.host ?: "")) return null
        "https://${uri.host.lowercase(Locale.ROOT)}/"
    }.getOrNull()
    fun gatewayTargets(html: String, base: String): List<String> = Jsoup.parse(html, base)
        .select("a[href]").mapNotNull { siteUrl(it.absUrl("href")) }.distinct()

    fun channels(html: String, base: String): List<SportsChannel> {
        val doc = Jsoup.parse(html, base)
        if (!doc.title().contains("Taraftarium", true) && !doc.title().contains("iNat TV", true)) return emptyList()
        return doc.select("a.channel-item[href]").mapNotNull { a ->
            if (a.selectFirst(".channel-status")?.text()?.trim() != "7/24") return@mapNotNull null
            val player = a.absUrl("href")
            if (siteUrl(player) != siteUrl(base)) return@mapNotNull null
            val id = queryParam(player, "id") ?: return@mapNotNull null
            if (!Regex("[a-zA-Z0-9_-]{1,80}").matches(id)) return@mapNotNull null
            val title = a.selectFirst(".channel-name")?.text()?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            SportsChannel(id, title, "Spor Kanalları", player, "")
        }.distinctBy { it.id }
    }
    fun queryParam(url: String, key: String): String? = runCatching {
        URI(url).rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()
    fun streamUrl(html: String, player: String): String? {
        val base = Regex("""baseUrl\s*:\s*['"](https://[^'"]+)['"]""").find(html)?.groupValues?.get(1) ?: return null
        val uri = runCatching { URI(base) }.getOrNull() ?: return null
        if (uri.host == null || uri.userInfo != null || uri.scheme != "https") return null
        val id = queryParam(player, "id") ?: return null
        if (!Regex("[a-zA-Z0-9_-]{1,80}").matches(id) || !html.contains("/mono.m3u8")) return null
        return "${base.trimEnd('/')}/$id/mono.m3u8"
    }
}

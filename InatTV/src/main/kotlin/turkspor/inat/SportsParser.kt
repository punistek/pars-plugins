package turkspor.inat

import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

data class SportsChannel(val id: String, val title: String, val category: String, val player: String, val target: String)
data class PlayerConfig(val authUrl: String, val siteName: String)
data class StreamSession(val url: String, val token: String)

object SportsParser {
    private val hostPattern = Regex("^(www\\.)?inattvizle([0-9]{1,6})\\.top$", RegexOption.IGNORE_CASE)
    fun siteUrl(value: String): String? = runCatching {
        val uri = URI(value.trim())
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1, 443) || !hostPattern.matches(uri.host ?: "")) return null
        "https://${uri.host.lowercase(Locale.ROOT)}/"
    }.getOrNull()
    fun gatewayTargets(html: String, base: String): List<String> = Jsoup.parse(html, base)
        .select("a[href],link[rel=canonical]").mapNotNull { siteUrl(it.absUrl("href")) }.distinct()
    fun nextDomains(value: String): List<String> {
        val host = runCatching { URI(value).host }.getOrNull() ?: return emptyList()
        val number = hostPattern.matchEntire(host)?.groupValues?.get(2)?.toIntOrNull() ?: return emptyList()
        return (1..3).map { "https://www.inattvizle${number + it}.top/" }
    }
    fun httpsUrl(value: String): String? = runCatching {
        val uri = URI(value)
        if (uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.port in listOf(-1,443)) value else null
    }.getOrNull()
    fun channels(html: String, base: String): List<SportsChannel> {
        val doc = Jsoup.parse(html, base)
        if (!doc.title().contains("inatTV", true)) return emptyList()
        return doc.select("#channel-slider .channel-item[data-name][data-target][data-source]").mapNotNull { a ->
            val id = a.attr("data-url").removePrefix("#").removeSuffix("-canli-izle")
            if (!Regex("[a-zA-Z0-9_-]{1,80}").matches(id)) return@mapNotNull null
            val target = a.attr("data-target")
            val source = a.attr("data-source")
            if (target == "viptv") {
                if (!Regex("[0-9]{1,12}").matches(source)) return@mapNotNull null
            } else if (target != "m3u8" || httpsUrl(source) == null) return@mapNotNull null
            val title = a.attr("data-name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            SportsChannel(id, title, "Spor Kanalları", source, target)
        }.distinctBy { it.id }
    }
    fun config(html: String, base: String): PlayerConfig? {
        val data = Regex("""var\s+phptojs\s*=\s*\{(.*?)\};""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: return null
        fun field(key: String) = Regex("""['"]$key['"]\s*:\s*['"]([^'"]*)['"]""").find(data)?.groupValues?.get(1)
        val server = field("stream_server_cdn_url") ?: return null
        val site = field("site_name")?.takeIf { it.isNotBlank() && it.length < 100 && !it.contains('\n') && !it.contains('\r') } ?: return null
        val auth = runCatching { URI(base).resolve(server).resolve("auth.php").toString() }.getOrNull() ?: return null
        // Only use this site's public player endpoint; unrelated advertising URLs are ignored.
        if (httpsUrl(auth) == null || URI(auth).host != URI(base).host) return null
        return PlayerConfig(auth, site)
    }
    fun streamSession(json: String): StreamSession? = runCatching {
        val data = ObjectMapper().readTree(json)
        if (data.has("ERROR")) return null
        val url = httpsUrl(data.path("URL").asText()) ?: return null
        val token = data.path("TOKEN").asText("")
        if (token.length > 2048 || token.any { it == '\r' || it == '\n' }) return null
        StreamSession(url, token)
    }.getOrNull()
    fun queryParam(url: String, key: String): String? = runCatching {
        URI(url).rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()
}

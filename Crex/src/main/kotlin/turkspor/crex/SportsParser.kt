package turkspor.crex

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

data class SportsChannel(val id: String, val title: String, val category: String, val players: List<String>, val logo: String)

object SportsParser {
    private val mapper = ObjectMapper().configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
        .configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true).configure(JsonParser.Feature.ALLOW_TRAILING_COMMA, true)
    fun siteUrl(value: String): String? = runCatching {
        val uri = URI(value.trim())
        if (uri.scheme != "https" || uri.userInfo != null || uri.port !in listOf(-1,443) || !Regex("crex[0-9]*\\.vercel\\.app").matches(uri.host ?: "")) return null
        "https://${uri.host.lowercase(Locale.ROOT)}/"
    }.getOrNull()
    fun gatewayTargets(html: String, base: String): List<String> = Jsoup.parse(html, base)
        .select("a[href],link[rel=canonical]").mapNotNull { siteUrl(it.absUrl("href")) }.distinct()
    fun httpsUrl(value: String): String? = runCatching {
        val uri = URI(value)
        if (uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.port in listOf(-1,443)) value else null
    }.getOrNull()
    fun channels(html: String, base: String): List<SportsChannel> = runCatching {
        val doc = Jsoup.parse(html, base)
        if (!doc.title().contains("Crex", true)) return emptyList()
        val data = Regex("""const\s+channels\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return emptyList()
        mapper.readTree(data).mapNotNull { item ->
            val title = ChannelBranding.normalizedTitle(item.path("name").asText()).takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val id = title.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val player = httpsUrl(item.path("stream").asText()) ?: return@mapNotNull null
            val logo = httpsUrl(item.path("logo").asText()).orEmpty()
            SportsChannel(id, title, "Spor Kanalları", listOf(player), logo)
        }.groupBy { it.id }.values.map { rows -> rows.first().copy(players = rows.flatMap { it.players }.distinct()) }
    }.getOrDefault(emptyList())
    fun mappedStream(html: String, player: String): String? = runCatching {
        val key = queryParam(player, "kanal")?.lowercase(Locale.ROOT) ?: return null
        val data = Regex("""const\s+channelMap\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1) ?: return null
        httpsUrl(mapper.readTree(data).path(key).asText())
    }.getOrNull()
    fun domainEndpoint(html: String): String? = Regex("""domainUrl\s*:\s*['"](https://[^'"]+)['"]""").find(html)?.groupValues?.get(1)?.let(::httpsUrl)
    fun baseFromJson(json: String): String? = runCatching { httpsUrl(mapper.readTree(json).path("baseurl").asText()) }.getOrNull()
    fun royalStream(base: String, player: String): String? {
        val id = queryParam(player, "id") ?: return null
        if (!Regex("[a-zA-Z0-9_-]{1,80}").matches(id) || httpsUrl(base) == null) return null
        return "${base.trimEnd('/')}/$id/mono.m3u8"
    }
    fun queryParam(url: String, key: String): String? = runCatching {
        URI(url).rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == key }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
    }.getOrNull()
}

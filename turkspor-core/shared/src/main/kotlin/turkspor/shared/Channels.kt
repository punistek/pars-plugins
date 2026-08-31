package turkspor.shared

import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

object Channels {
    fun https(value: String): String? = runCatching {
        val uri = URI(value)
        if (uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.port in listOf(-1,443)) value else null
    }.getOrNull()
    fun site(spec: SourceSpec, value: String): String? = runCatching {
        val uri = URI(value.trim())
        if (https(uri.toString()) == null || !spec.host.matches(uri.host.lowercase(Locale.ROOT))) return null
        "https://${uri.host.lowercase(Locale.ROOT)}/"
    }.getOrNull()
    fun param(url: String, key: String): String? = runCatching {
        URI(url).rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == key }?.substringAfter('=')?.let { URLDecoder.decode(it,"UTF-8") }
    }.getOrNull()
    fun nextDomains(spec: SourceSpec, value: String): List<String> {
        val root = site(spec,value) ?: return emptyList()
        val host = URI(root).host
        val digits = Regex("[0-9]+").findAll(host).lastOrNull() ?: return emptyList()
        val number = digits.value.toIntOrNull() ?: return emptyList()
        return (1..3).mapNotNull { site(spec,"https://${host.replaceRange(digits.range,(number+it).toString())}/") }
    }
    fun links(spec: SourceSpec, html: String, base: String): List<String> = Jsoup.parse(html,base).select("a[href],link[rel=canonical]").mapNotNull { site(spec,it.absUrl("href")) }.distinct()
    fun externalCatalog(html: String): String? = Regex("""fetch\(['"](https://[^'"]+/channels\.php)['"]""").find(html)?.groupValues?.get(1)?.let(::https)
    fun read(spec: SourceSpec, html: String, base: String): List<Channel> {
        val doc = Jsoup.parse(html,base)
        val rows = when(spec.mode) {
            SourceMode.WORDPRESS -> doc.select("a[href*=/tv/]").mapNotNull { a ->
                val url = a.absUrl("href")
                if (site(spec,url) != site(spec,base)) return@mapNotNull null
                val id = Regex("^/tv/([a-z0-9-]+)/?$").matchEntire(URI(url).path)?.groupValues?.get(1) ?: return@mapNotNull null
                val title = a.text().trim().ifEmpty { a.selectFirst("img")?.attr("alt").orEmpty() }
                if (title.isEmpty()) return@mapNotNull null
                Channel(id,Branding.normalize(title),listOf(url),a.selectFirst("img")?.absUrl("src").orEmpty())
            }
            SourceMode.ROYAL -> doc.select("a.channel-item[href],a.single-match[href]").mapNotNull { a ->
                if (a.selectFirst(".channel-status")?.text() != "7/24" && !(a.hasClass("single-match") && a.text().contains("7/24"))) return@mapNotNull null
                val url = a.absUrl("href")
                if (site(spec,url) != site(spec,base)) return@mapNotNull null
                val id = param(url,"id")?.takeIf { Regex("[a-zA-Z0-9_-]{1,80}").matches(it) } ?: return@mapNotNull null
                val title = a.selectFirst(".channel-name,.home")?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Channel(id,Branding.normalize(title),listOf(url),a.selectFirst(".away img")?.absUrl("src").orEmpty())
            }
            SourceMode.INTER -> doc.select(".single-channel[data-channel=true][data-streamx]").mapNotNull { a ->
                val id = a.attr("data-stream").takeIf { Regex("[a-zA-Z0-9_-]{1,80}").matches(it) } ?: return@mapNotNull null
                val url = https(a.attr("data-streamx")) ?: return@mapNotNull null
                val title = a.attr("data-name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Channel(id,Branding.normalize(title),listOf(url),a.selectFirst("img")?.absUrl("src").orEmpty())
            }
            SourceMode.BEYAZ -> doc.select("a.channel-card[href^=/kanal/]").mapNotNull { a ->
                val url = a.absUrl("href")
                if (site(spec,url) != site(spec,base)) return@mapNotNull null
                val raw = a.selectFirst("h2,h3,h4")?.text() ?: a.selectFirst("img")?.attr("alt") ?: a.text()
                val title = Branding.normalize(raw)
                val id = java.text.Normalizer.normalize(title.lowercase(Locale.ROOT),java.text.Normalizer.Form.NFD).replace(Regex("[^a-z0-9]+"),"-").trim('-')
                if (id.isEmpty()) return@mapNotNull null
                Channel(id,title,listOf(url),a.selectFirst("img")?.absUrl("src").orEmpty())
            }
        }
        return rows.groupBy { it.id }.values.map { list -> list.first().copy(players=list.flatMap { it.players }.distinct()) }
    }
}

package turkspor.shared

import com.fasterxml.jackson.core.Base64Variants
import com.fasterxml.jackson.databind.ObjectMapper
import org.jsoup.Jsoup
import java.net.URI

/** Reads public player data; never evaluates page JavaScript or advertising code. */
object PlayerParser {
    private val mapper = ObjectMapper()
    private fun b64(value: String) = Base64Variants.getDefaultVariant().decode(value)
    fun wordpressEmbed(html: String, base: String): String? = Jsoup.parse(html,base).selectFirst("[data-player-url]")
        ?.attr("data-player-url")?.substringBefore('#')?.let { Channels.https(URI(base).resolve(it).toString()) }
    fun wordpressStream(html: String, player: String, session: String): String? = runCatching {
        val id=Channels.param(player,"id")?.takeIf { Regex("[0-9]{1,12}").matches(it) } ?: return null
        val array=Regex("""window\.streamradardomil\s*=\s*\[atob\(['"]([^'"]+)['"]\)""").find(html)?.groupValues?.get(1) ?: return null
        val domain=String(b64(array),Charsets.UTF_8).removePrefix(".")
        val path=Regex("""streamradardomi\.substring\(1\)\s*\+\s*['"]([^'"]+)['"]\s*\+\s*window\.mainSource""").find(html)?.groupValues?.get(1) ?: return null
        val query=mapper.readTree(session).get(5)?.asText().orEmpty()
        if (query.length>8192 || (query.isNotEmpty() && !query.startsWith('?')) || query.any { it=='\n'||it=='\r'||it=='#' }) return null
        Channels.https("https://$domain$path$id/playlist.m3u8$query")
    }.getOrNull()
    fun domainEndpoint(html: String): String? = Regex("""domainUrl\s*:\s*['"](https://[^'"]+)['"]""").find(html)?.groupValues?.get(1)?.let(Channels::https)
    fun royalStream(json: String, player: String): String? = runCatching {
        val id=Channels.param(player,"id")?.takeIf { Regex("[a-zA-Z0-9_-]{1,80}").matches(it) } ?: return null
        val base=Channels.https(mapper.readTree(json).path("baseurl").asText()) ?: return null
        "$base${if(base.endsWith('/')) "" else "/"}$id/mono.m3u8"
    }.getOrNull()
    fun apiStream(json: String): String? = runCatching { Channels.https(mapper.readTree(json).path("URL").asText()) }.getOrNull()
    fun nextStreams(html: String, base: String): List<String> {
        val streams=mutableListOf<String>()
        for(m in Regex("""self\.__next_f\.push\(""").findAll(html)) {
            val chunk=runCatching { mapper.readTree(html.substring(m.range.last+1)).get(1)?.takeIf { it.isTextual }?.asText() }.getOrNull() ?: continue
            val fields=Regex(""""streamUrl(?:2)?"\s*:\s*("(?:\\.|[^"\\])*")""").findAll(chunk)
            for(field in fields) {
                val value=runCatching { mapper.readTree(field.groupValues[1]).asText() }.getOrNull() ?: continue
                if(value.startsWith("/api/stream?") || value.startsWith("/api/embed?")) streams.add(URI(base).resolve(value).toString())
                else Channels.https(value)?.let(streams::add)
            }
        }
        return streams.distinct()
    }
    fun unpackNumeric(html: String): String? = runCatching {
        if(html.length>2_000_000) return null
        val args=Regex("""\}\("([^"]+)",(\d+),"([^"]+)",(\d+),(\d+),(\d+)\)\)""").find(html)?.groupValues ?: return null
        val packed=args[1];val alphabet=args[3];val offset=args[4].toInt();val radix=args[5].toInt()
        if(packed.length>1_500_000 || radix !in 2..9 || alphabet.length<=radix || alphabet.toSet().size!=alphabet.length || offset !in 0..255) return null
        val output=ArrayList<Byte>()
        for(word in packed.split(alphabet[radix])) {
            if(word.isEmpty()) continue
            if(word.length>12) return null
            val digits=word.map { c -> alphabet.indexOf(c).also { if(it !in 0 until radix) return null }.digitToChar() }.joinToString("")
            val code=digits.toInt(radix)-offset
            if(code !in 0..255) return null
            output.add(code.toByte())
        }
        String(output.toByteArray(),Charsets.UTF_8)
    }.getOrNull()
    fun embeddedHls(html: String, page: String): String? = runCatching {
        val script=if(html.contains("let EMBD_STREAMID")) html else unpackNumeric(html) ?: return null
        fun field(key: String) = Regex("""\b$key\s*=\s*"([^"]*)"""").find(script)?.groupValues?.get(1)
        if(field("EMBD_STREAMTYPE")!="hls" || field("EMBD_DRMTYPE").orEmpty().isNotEmpty()) return null
        val id=field("EMBD_STREAMID")?.takeIf { Regex("[a-zA-Z0-9_-]{1,100}").matches(it) } ?: return null
        val endpoint=field("EMBD_PLAYERURL") ?: return null
        val encoded=Regex("""EMBD_AUTHTOKEN\s*=\s*\w+\("decrypt",\s*"([^"]*)"\)""").find(script)?.groupValues?.get(1) ?: return null
        val keyParts=Regex("""let\s+s="([^"]+)";return\s+s\+=String\.fromCharCode\(([^)]+)\),s\+"([^"]+)"""").find(script)?.groupValues ?: return null
        val codes=keyParts[2].split(',').map { it.trim().toInt().also { n -> if(n !in 0..127) return null }.toChar() }.joinToString("")
        val key=keyParts[1]+codes+keyParts[3]
        if(key.length !in 1..2048 || encoded.length>16384) return null
        val token=b64(encoded).mapIndexed { index, byte -> ((byte.toInt() and 255) xor key[index%key.length].code).toChar() }.joinToString("")
        if(token.any { it=='\r'||it=='\n'||it=='#' }) return null
        val origin=Jsoup.parse(html,page).selectFirst("base[href]")?.absUrl("href") ?: page
        Channels.https(URI(origin).resolve(endpoint).toString()+"?id=$id&$token&format=.m3u8")
    }.getOrNull()
}

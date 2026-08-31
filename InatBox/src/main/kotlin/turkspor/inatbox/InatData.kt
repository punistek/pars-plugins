package turkspor.inatbox

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.core.Base64Variants
import turkspor.shared.*
import java.text.Normalizer
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Public catalogue envelope format from murattunc05/cloudstream-turkish, GPL-3.0. */
object InatData {
    const val KEY="ywevqtjrurkwtqgz"
    val mapper=ObjectMapper()
    private fun base64(value: String)=Base64Variants.MIME.decode(value)
    fun decode(value: String,defaultKey: String=KEY): JsonNode? = runCatching {
        if(value.length>8_000_000) return null
        var text=value.replace("-----BEGIN CERTIFICATE-----","").replace("-----END CERTIFICATE-----","").trim()
        repeat(4) {
            if(text.startsWith('[') || text.startsWith('{')) return mapper.readTree(text)
            val split=text.indexOf(':')
            val data=if(split>=0) text.substring(0,split).trim() else text
            val keyText=if(split>=0) text.substring(split+1).trim() else defaultKey
            val key=runCatching { base64(keyText) }.getOrNull()?.takeIf { it.size in listOf(16,24,32) } ?: keyText.toByteArray()
            if(key.size !in listOf(16,24,32)) return null
            val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE,SecretKeySpec(key,"AES"),IvParameterSpec(key.copyOf(16)))
            text=String(cipher.doFinal(base64(data)),Charsets.UTF_8).trim()
        }
        if(text.startsWith('[') || text.startsWith('{')) mapper.readTree(text) else null
    }.getOrNull()
    fun domain(document: String): String?=decode(document)?.path("DC10")?.asText()?.let(Channels::https)
    fun githubDocument(json: String): String?=runCatching { String(base64(mapper.readTree(json).path("content").asText()),Charsets.UTF_8) }.getOrNull()
    fun fold(text: String)=Normalizer.normalize(text.lowercase(Locale.ROOT).replace('ı','i'),Normalizer.Form.NFD).replace(Regex("[^a-z0-9]"),"")
    fun title(value: String): String {
        val text=value.substringBefore('|').trim().replace(Regex("(?i)^EXXENSPOR\\s+0*([1-9])$"),"Exxen Sports $1")
        return Branding.normalize(text)
    }
    fun channels(json: JsonNode): List<Channel> {
        if(!json.isArray) return emptyList()
        return json.mapNotNull { row ->
            val type=row.path("chType").asText()
            if(type !in listOf("live_url","live_url_mode") && !type.startsWith("tekli_regex_lb_sh_3")) return@mapNotNull null
            val raw=row.path("chName").asText().trim()
            if(raw.isBlank() || stream(row)==null) return@mapNotNull null
            val title=title(raw);val id=fold(title)
            Channel(id,title,listOf(row.toString()),row.path("chImg").asText())
        }.groupBy { it.id }.values.map { list -> list.first().copy(players=list.flatMap { it.players }.distinct()) }
    }
    fun headers(row: JsonNode): Map<String,String> {
        val result=mutableMapOf("Referer" to "https://google.com/","User-Agent" to DomainResolver.UA)
        runCatching {
            val raw=row.path("chHeaders")
            val items=if(raw.isTextual) mapper.readTree(raw.asText()) else raw
            val obj=if(items.isArray) items.firstOrNull() else items
            obj?.fields()?.forEach { (key,value) ->
                val name=when(key) { "UserAgent"->"User-Agent";"XRequestedWith"->"X-Requested-With";else->key }
                if(name.lowercase(Locale.ROOT) in listOf("user-agent","referer","origin","x-requested-with","cookie") && value.isTextual && !value.asText().contains(Regex("[\\r\\n]"))) result[name]=value.asText()
            }
        }
        runCatching { val value=reg(row)?.path("playSH2")?.asText().orEmpty();if(value.isNotBlank() && value!="null" && !value.contains(Regex("[\\r\\n]"))) result["Cookie"]=value }
        return result
    }
    private fun reg(row: JsonNode): JsonNode? = runCatching {
        val raw=row.path("chReg");val parsed=if(raw.isTextual) mapper.readTree(raw.asText()) else raw
        if(parsed.isArray) parsed.firstOrNull() else parsed
    }.getOrNull()
    fun key(row: JsonNode)=reg(row)?.path("Regex1")?.asText()?.takeIf { it.isNotBlank() && it!="null" } ?: KEY
    fun stream(json: JsonNode): String?=runCatching {
        val value=json.path("chUrl").asText();val uri=java.net.URI(value)
        if(uri.scheme in listOf("http","https") && uri.host!=null && uri.userInfo==null) value else null
    }.getOrNull()
}

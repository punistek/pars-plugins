package turkspor.inatbox
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class InatDataTest {
    private fun encrypt(text: String,key: String): String {
        val bytes=key.toByteArray();val cipher=Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE,SecretKeySpec(bytes,"AES"),IvParameterSpec(bytes))
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray()))+":"+Base64.getEncoder().encodeToString(bytes)
    }
    @Test fun domainDocumentSupportsTwoLayersAndMirrorApiEnvelope() {
        val text="""{"DC10":"https://catalog.example/index.js"}"""
        val document="-----BEGIN CERTIFICATE-----\n"+encrypt(encrypt(text,"1234567890abcdef"),"abcdefghijklmnop")+"\n-----END CERTIFICATE-----"
        assertEquals("https://catalog.example/index.js",InatData.domain(document))
        val api=InatData.mapper.writeValueAsString(mapOf("content" to Base64.getMimeEncoder().encodeToString(document.toByteArray())))
        assertEquals(document,InatData.githubDocument(api))
        assertNull(InatData.domain("""{"DC10":"https://user:pass@catalog.example/private"}"""))
        assertNull(InatData.domain("invalid"))
    }
    @Test fun decodedJsonIsNotMistakenForAnotherEncryptedLayer() {
        val value="""[{"chName":"beIN Sports 1","chUrl":"https://cdn.example/master.m3u8"}]"""
        assertEquals("beIN Sports 1",InatData.decode(encrypt(value,"1234567890abcdef"))!!.first().path("chName").asText())
        assertNull(InatData.decode("invalid:short-key"))
    }
    @Test fun fixedChannelAlternativesAreGroupedAndWebSchedulesAreExcluded() {
        val channels=InatData.channels(InatData.mapper.readTree(javaClass.getResource("/sports.json")!!.readText()))
        assertEquals(47,channels.size)
        assertTrue(channels.none { it.title.contains("Maç") || it.title.contains("Premium") || it.title.contains("@") || it.title.contains('|') })
        assertTrue(channels.first { it.title=="beIN Sports 1" }.players.size>=3)
        assertEquals("Exxen Sports 1",InatData.title("EXXENSPOR 01"))
        assertEquals("S Sport",InatData.title("S Sport 1 | A"))
    }
    @Test fun playerHeaderMappingPreservesOnlyPlaybackHeaders() {
        val row=InatData.mapper.readTree("""{"chHeaders":[{"UserAgent":"player","Referer":"https://player.example/","Host":"wrong.example","Origin":"bad\r\nheader"}],"chReg":[{"Regex1":"sample-key","playSH2":"public=sample"}]}""")
        val headers=InatData.headers(row)
        assertEquals("player",headers["User-Agent"])
        assertEquals("public=sample",headers["Cookie"])
        assertFalse(headers.containsKey("Host"));assertFalse(headers.containsKey("Origin"))
        assertEquals("sample-key",InatData.key(row))
    }
}

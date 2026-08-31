package turkspor.shared

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class SharedSourcesTest {
    private fun fixture(name: String) = javaClass.getResource("/$name.html")!!.readText()
    private fun spec(key: String) = SourceSpec.all.getValue(key)
    private fun channels(key: String, file: String, root: String = spec(key).roots.first()) = Channels.read(spec(key),fixture(file),root)
    @Test fun wordpressMirrorsHaveTheSame41FixedChannels() {
        val a=channels("mackeyfi","mackeyfi")
        val b=channels("mackeyfi","canlimaclar",spec("mackeyfi").roots[1])
        assertEquals(41,a.size)
        assertEquals(a.map { it.id },b.map { it.id })
        assertEquals("beIN Sports 1",a.first { it.id=="bein-sports-1" }.title)
    }
    @Test fun royalCatalogsExcludeScheduledMatches() {
        val a=channels("zbahistv","zbahis")
        val b=channels("zbahistv","baywin",spec("zbahistv").roots[1])
        assertEquals(31,a.size)
        assertEquals(31,b.size)
        assertTrue("Zbahis=${a.map { it.id }} Baywin=${b.map { it.id }}",b.map { it.id }.containsAll(a.map { it.id }))
        val scheduled="""<a class="channel-item" href="/channel.html?id=game"><b class="channel-name">Team A - Team B</b><i class="channel-status">21:00</i></a>"""
        assertTrue(Channels.read(spec("zbahistv"),scheduled,spec("zbahistv").roots.first()).isEmpty())
    }
    @Test fun interHasSevenStaticChannelsAndNoMatchCards() {
        val rows=channels("intersportv","inter")
        assertEquals(7,rows.size)
        assertTrue(rows.all { it.players.single().endsWith(".m3u8") })
        val match="""<div class="single-channel" data-channel="false" data-name="Team A" data-stream="123" data-streamx="https://cdn.example/game.m3u8"></div>"""
        assertTrue(Channels.read(spec("intersportv"),match,spec("intersportv").roots.first()).isEmpty())
    }
    @Test fun beyazQualityVariantsShareOneCanonicalCard() {
        val rows=channels("beyazelma","beyaz")
        assertEquals("${rows.map { it.title }}",27,rows.size)
        assertEquals(3,rows.first { it.title=="beIN Sports 1" }.players.size)
        assertTrue(rows.none { it.title.contains("HD") || it.title.contains("4K") || it.title.contains("İzle") })
    }
    @Test fun everyCapturedChannelHasItsOwnBrandLogo() {
        val all=channels("mackeyfi","mackeyfi")+channels("zbahistv","zbahis")+channels("zbahistv","baywin",spec("zbahistv").roots[1])+channels("intersportv","inter")+channels("beyazelma","beyaz")
        val missing=all.filter { Branding.forChannel(it).logo.isEmpty() }
        assertTrue("Missing logos: ${missing.map { it.title }}",missing.isEmpty())
        assertEquals("S Sport Plus",Branding.normalize("SSPORT+"))
        assertEquals("Tivibu Spor 1",Branding.normalize("TİVİBU 1"))
    }
    @Test fun domainCandidatesRejectAdsLookalikesCredentialsAndWrongPorts() {
        val s=spec("mackeyfi")
        assertEquals("https://www.canlimaclar560.sbs/",Channels.site(s,"https://www.canlimaclar560.sbs/tv/a/"))
        for(url in listOf("https://mackeyfi560.sbs.evil.example/","https://user@mackeyfi560.sbs/","http://mackeyfi560.sbs/","https://mackeyfi560.sbs:8080/")) assertNull(Channels.site(s,url))
        assertEquals(listOf("https://www.mackeyfi560.sbs/","https://www.mackeyfi561.sbs/","https://www.mackeyfi562.sbs/"),Channels.nextDomains(s,s.roots.first()))
        assertEquals(listOf("https://100baywintv.live/","https://101baywintv.live/","https://102baywintv.live/"),Channels.nextDomains(spec("zbahistv"),"https://99baywintv.live/"))
    }
    @Test fun wordpressUsesRequestedIdAndPublicSessionNotAds() {
        val domain=Base64.getEncoder().encodeToString(".cdn.example".toByteArray())
        val html="""window.streamradardomil=[atob("$domain")];window.mainSource=5063;window.config={source:"https://"+window.streamradardomi.substring(1)+"/live/-/"+window.mainSource+"/playlist.m3u8",ads:"https://ads.example/ad.m3u8"};"""
        assertEquals("https://cdn.example/live/-/5062/playlist.m3u8?verify=sample",PlayerParser.wordpressStream(html,"https://www.mackeyfi559.sbs/player?id=5062","""[0,0,0,0,0,"?verify=sample"]"""))
        assertNull(PlayerParser.wordpressStream(html,"https://www.mackeyfi559.sbs/player?id=../a","[]"))
        assertEquals("https://www.mackeyfi559.sbs/player?id=5062",PlayerParser.wordpressEmbed("""<iframe src="https://ads.example/"></iframe><div data-player-url="/player?id=5062#ads=true"></div>""","https://www.mackeyfi559.sbs/"))
    }
    @Test fun royalReadsDynamicEndpointAndChecksChannelId() {
        assertEquals("https://data.example/domain.php",PlayerParser.domainEndpoint("""const CONFIG={domainUrl:'https://data.example/domain.php',adUrl:'https://ads.example'}"""))
        assertEquals("https://cdn.example/zirve/mono.m3u8",PlayerParser.royalStream("""{"baseurl":"https://cdn.example/"}""","https://zbahistv65.com/channel.html?id=zirve"))
        assertNull(PlayerParser.royalStream("""{"baseurl":"https://cdn.example/"}""","https://zbahistv65.com/channel.html?id=..%2Fsecret"))
    }
    @Test fun nextServerDataIsDecodedWithoutExecutingScripts() {
        val payload="""7:["component",{"streamUrl":"/api/embed?u=opaque%2Bvalue","streamUrl2":"https://cdn.example/second.m3u8","ad":"https://ads.example/"}]"""
        val html="<script>self.__next_f.push("+ObjectMapper().writeValueAsString(listOf(1,payload))+")</script><script>throw Error('do not execute')</script>"
        assertEquals(listOf("https://beyazelma78.com/api/embed?u=opaque%2Bvalue","https://cdn.example/second.m3u8"),PlayerParser.nextStreams(html,"https://beyazelma78.com/kanal/example"))
    }
    private fun embeddedScript(): String {
        val key="abcTVxyz"
        val token="token=sample&expires=1"
        val encrypted=Base64.getEncoder().encodeToString(token.mapIndexed { i,c -> (c.code xor key[i%key.length].code).toByte() }.toByteArray())
        return """let EMBD_STREAMID="test-channel";let EMBD_PLAYERURL="live.php";let EMBD_STREAMTYPE="hls";let EMBD_DRMTYPE="";let EMBD_AUTHTOKEN=decode("decrypt","$encrypted");function decode(){const c=function(){let s="abc";return s+=String.fromCharCode(84,86),s+"xyz"}();} """
    }
    private fun pack(script: String): String {
        val alphabet="fCXnuWOli"
        val body=script.toByteArray().joinToString("") { b -> ((b.toInt() and 255)+2).toString(3).map { alphabet[it.digitToInt()] }.joinToString("")+alphabet[3] }
        return "eval(function(h,u,n,t,e,r){}(\"$body\",93,\"$alphabet\",2,3,53))"
    }
    @Test fun numericPublicPlayerDataDecodesAndRespectsBaseTag() {
        val script=embeddedScript()
        assertEquals(script,PlayerParser.unpackNumeric(pack(script)))
        val html="<base href=\"https://player.example/\">"+pack(script)
        assertEquals("https://player.example/live.php?id=test-channel&token=sample&expires=1&format=.m3u8",PlayerParser.embeddedHls(html,"https://beyazelma78.com/api/embed?u=sample"))
    }
    @Test fun malformedAndUnsupportedPlayersAreRejected() {
        assertNull(PlayerParser.unpackNumeric("eval(function(){}(\"invalid\",93,\"fCXnuWOli\",2,3,53))"))
        assertNull(PlayerParser.embeddedHls(embeddedScript().replace("EMBD_DRMTYPE=\"\"","EMBD_DRMTYPE=\"widevine\""),"https://player.example/"))
        assertNull(PlayerParser.embeddedHls(embeddedScript().replace("EMBD_STREAMTYPE=\"hls\"","EMBD_STREAMTYPE=\"dash\""),"https://player.example/"))
        assertNull(PlayerParser.embeddedHls("<iframe src='https://ads.example'></iframe>","https://player.example/"))
    }
}

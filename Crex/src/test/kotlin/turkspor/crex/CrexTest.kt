package turkspor.crex
import org.junit.Assert.*
import org.junit.Test

class CrexTest {
    @Test fun capturedChannelListGroupsAlternatesAndExcludesMatches() {
        val html = javaClass.getResource("/crex-page.html")!!.readText() + "<script>const dailyMatchesByDate = [{name:'A vs B',stream:'https://ads.example/ad.m3u8'}];</script>"
        val items = SportsParser.channels(html, "https://crex1.vercel.app/")
        assertEquals(31, items.size)
        assertEquals(3, items.first().players.size)
        assertEquals("beIN Sports 1", items.first().title)
        assertEquals(2, items.first { it.title == "beIN Sports 2" }.players.size)
        assertTrue(items.all { ChannelBranding.forChannel(it).logo.startsWith("https://") })
        assertFalse(items.any { it.title.contains("vs") })
    }
    @Test fun onlySelectedPlayerMappingIsUsed() {
        val html = javaClass.getResource("/player-map.html")!!.readText()
        assertEquals("https://corestream.ardastream.live//hls/bein1.m3u8", SportsParser.mappedStream(html, "https://playersystem1.vercel.app/?kanal=beinsports1b"))
        assertNull(SportsParser.mappedStream(html, "https://playersystem1.vercel.app/?kanal=unknown"))
    }
    @Test fun royalDomainAndIdsAreValidated() {
        val html = """const CONFIG={prerollVideo:'https://ads.example/ad.m3u8',domainUrl:'https://data.example/domain.php'};"""
        assertEquals("https://data.example/domain.php", SportsParser.domainEndpoint(html))
        assertEquals("https://cdn.example/zirve/mono.m3u8", SportsParser.royalStream("https://cdn.example/", "https://royaltv35.com/channel?id=zirve"))
        assertNull(SportsParser.royalStream("https://cdn.example/", "https://royaltv35.com/channel?id=..%2fx"))
        assertNull(SportsParser.siteUrl("https://crex1.vercel.app.evil.com/"))
        assertEquals("https://crex2.vercel.app/", SportsParser.siteUrl("https://crex2.vercel.app/"))
    }
}

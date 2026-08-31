package turkspor.taraftarium

import org.junit.Assert.*
import org.junit.Test

class TaraftariumTest {
    @Test fun inatXyzAliasHasTheSame25Channels() {
        val html=javaClass.getResource("/inat-xyz.html")!!.readText()
        val alias="https://inattv1322.xyz/"
        val channels=SportsParser.channels(html,alias)
        assertEquals(alias,SportsParser.siteUrl(alias))
        assertEquals(SportsParser.channels(javaClass.getResource("/taraftarium-page.html")!!.readText(),site).map { it.id }.toSet(),channels.map { it.id }.toSet())
        assertEquals(25,channels.size)
        assertNull(SportsParser.siteUrl("https://inattv1322.xyz.evil.example/"))
    }
    private val site = "https://taraftarium1081.xyz/"
    @Test fun capturedChannelsExcludeMatchSchedule() {
        val channels = SportsParser.channels(javaClass.getResource("/taraftarium-page.html")!!.readText(), site)
        assertEquals(25, channels.size)
        assertEquals("patron", channels.first().id)
        assertTrue(channels.all { it.time.isEmpty() })
        assertTrue(channels.all { ChannelBranding.forChannel(it).logo.startsWith("https://") })
    }
    @Test fun scheduledEventsAndCommentedChannelsAreIgnored() {
        val html = """<title>Taraftarium24</title><a class="channel-item" href="/channel.html?id=patron"><div class="channel-name">Team A - Team B</div><div class="channel-status">21:00</div></a><!-- <a class="channel-item" href="/channel.html?id=f1"><div class="channel-name">Inactive F1</div><div class="channel-status">7/24</div></a> -->"""
        assertTrue(SportsParser.channels(html, site).isEmpty())
    }
    @Test fun resolvesMainConfigNotPreroll() {
        val html = """const CONFIG = {baseUrl:'https://cdn.example/',prerollVideo:'https://ads.example/ad.m3u8'}; return CONFIG.baseUrl+b+'/mono.m3u8'"""
        assertEquals("https://cdn.example/patron/mono.m3u8", SportsParser.streamUrl(html, "${site}channel.html?id=patron"))
        assertNull(SportsParser.streamUrl(html, "${site}channel.html?id=..%2fprivate"))
    }
    @Test fun domainValidationRejectsUnrelatedLinks() {
        val html = """<a href="https://taraftarium1082.xyz/">Canlı Maç Girişi</a><a href="https://ads.example">Bonus</a>"""
        assertEquals(listOf("https://taraftarium1082.xyz/"),SportsParser.gatewayTargets(html,"https://taraftarium24.ch/"))
        assertNull(SportsParser.siteUrl("https://taraftarium1082.xyz.evil.com"))
        assertNull(SportsParser.siteUrl("https://127.0.0.1/"))
    }
}

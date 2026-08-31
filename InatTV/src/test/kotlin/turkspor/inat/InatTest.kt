package turkspor.inat

import org.junit.Assert.*
import org.junit.Test

class InatTest {
    private val site = "https://www.inattvizle487.top/"
    private fun fixture() = javaClass.getResource("/inat-page.html")!!.readText()
    @Test fun capturedFixedChannelsHaveProperNamesAndLogos() {
        val channels = SportsParser.channels(fixture(), site)
        assertEquals(24, channels.size)
        assertEquals(20, channels.count { it.target == "viptv" })
        assertEquals(4, channels.count { it.target == "m3u8" })
        assertEquals("100001", channels.first().player)
        assertEquals("beIN Sports 1", ChannelBranding.forChannel(channels.first()).title)
        assertTrue(channels.all { ChannelBranding.forChannel(it).logo.startsWith("https://") })
    }
    @Test fun eventsAndAdvertisingFramesAreExcluded() {
        val html = """<title>inatTV</title><div class="channel-item" data-name="A vs B" data-url="#match" data-target="viptv" data-source="100001"></div><div id="channel-slider"><div class="channel-item" data-name="Reklam" data-url="#ad" data-target="frame" data-source="https://ads.example/"></div></div>"""
        assertTrue(SportsParser.channels(html, site).isEmpty())
    }
    @Test fun publicPlayerConfigIsReadWithoutExecutingAds() {
        assertEquals(PlayerConfig("${site}auth.php", "inatTV"), SportsParser.config(fixture(), site))
        assertNull(SportsParser.config(fixture().replace("\"stream_server_cdn_url\":\"/\"", "\"stream_server_cdn_url\":\"https://ads.example/\""), site))
    }
    @Test fun signedJsEndpointIsAcceptedAsHlsSessionNotJavascript() {
        val result = SportsParser.streamSession("""{"URL":"https:\/\/cdn.example\/cdn\/test\/100001.js","TOKEN":"test","SERVER":1}""")
        assertEquals(StreamSession("https://cdn.example/cdn/test/100001.js", "test"), result)
        assertNull(SportsParser.streamSession("""{"ERROR":"Unavailable"}"""))
        assertNull(SportsParser.streamSession("""{"URL":"javascript:alert(1)"}"""))
        assertNull(SportsParser.streamSession("""{"URL":"https://cdn.example/test.js","TOKEN":"bad\r\nHeader: x"}"""))
    }
    @Test fun numericFallbackIsBoundedAndUnrelatedDomainsAreRejected() {
        assertEquals(listOf("https://www.inattvizle488.top/", "https://www.inattvizle489.top/", "https://www.inattvizle490.top/"), SportsParser.nextDomains(site))
        assertNull(SportsParser.siteUrl("https://inattvizle488.top.ads.example/"))
        assertNull(SportsParser.siteUrl("https://user@inattvizle488.top/"))
        assertNull(SportsParser.siteUrl("https://inattvizle488.top:8443/"))
        assertTrue(SportsParser.channels("<title>inatTV</title>Parked domain", site).isEmpty())
    }
}

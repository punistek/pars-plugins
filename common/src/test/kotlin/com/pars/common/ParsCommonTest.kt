package com.pars.common

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ParsCommonTest {
    @Test fun channelKeysMergeQualitySuffixes() {
        assertEquals(ChannelNormalizer.key("TRT 1 HD"), ChannelNormalizer.key("TRT1 FHD"))
    }

    @Test fun hlsMasterSortsHighestFirst() {
        val text = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=2000000,RESOLUTION=1280x720
720/index.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
1080/index.m3u8
"""
        val result = HlsQuality.parse("https://example.com/live/master.m3u8", text)
        assertEquals(1080, result.first().quality)
        assertTrue(result.first().url.endsWith("1080/index.m3u8"))
    }

    @Test fun rejectsNonHttpUrls() {
        assertEquals(null, ParsUrl.http("file:///etc/passwd"))
    }
}

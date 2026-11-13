package com.satelliteworld.tv.recording

import org.junit.Assert.*
import org.junit.Test

class HlsPlaylistTest {
    @Test fun choosesClosestAvailableNotSyntheticResolution() {
        val master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=450000,RESOLUTION=426x240
240/live.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=1280x720
720/live.m3u8
"""
        val list = HlsPlaylist.variants("https://example.com/index.m3u8", master)
        assertEquals(2,list.size)
        assertEquals(240,HlsPlaylist.choose(list,360)?.height)
        assertEquals(720,HlsPlaylist.choose(list,0)?.height)
    }
    @Test fun mediaSegmentsAreResolvedAndTagged() {
        val manifest = """#EXTM3U
#EXT-X-TARGETDURATION:4
#EXTINF:4,
seg1.ts
#EXTINF:4,
seg2.ts
"""
        val media=HlsPlaylist.media("https://site.test/live/index.m3u8", manifest)
        assertEquals(2,media.segments.size)
        assertEquals("https://site.test/live/seg1.ts",media.segments.first())
        assertFalse(media.encrypted)
    }
    @Test fun encryptedAndFragmentedMediaDetected() {
        val manifest="""#EXTM3U
#EXT-X-KEY:METHOD=AES-128,URI="key.bin"
#EXT-X-MAP:URI="init.mp4"
#EXTINF:4,
seg.m4s
"""
        val media=HlsPlaylist.media("https://example.com/test.m3u8", manifest)
        assertTrue(media.encrypted)
        assertTrue(media.fragmentedMp4)
    }
}

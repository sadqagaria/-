package com.satelliteworld.tv.data

import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class PlaylistParserTest {
    @Test fun parsesTvAndRadioM3u() {
        val playlist = """#EXTM3U
#EXTINF:-1 tvg-id="TestEG.eg" tvg-logo="https://example.com/logo.png" group-title="Egypt",Test Egypt
https://example.com/live.m3u8
#EXTINF:-1 radio="true" group-title="Egypt",Cairo FM
https://example.com/fm.mp3
"""
        val rows = PlaylistParser.parse(StringReader(playlist), "github:test")
        assertEquals(2, rows.size)
        assertEquals("Test Egypt", rows[0].name)
        assertEquals("TestEG.eg", rows[0].epgId)
        assertEquals("Egypt", rows[0].group)
        assertTrue(rows[1].radio)
    }

    @Test fun preservesCsvNamesAndChannelNumber() {
        val text = "name,url,satellite,number\n\"Film, Drama\",https://example.com/tv.m3u8,Nilesat 7W,99"
        val row = PlaylistParser.parseCsv(StringReader(text), "import:csv").single()
        assertEquals("Film, Drama", row.name)
        assertEquals(99, row.position)
        assertEquals("7°W · Nilesat", row.satellite)
    }

    @Test fun convertsSatellitePositionAndDoesNotInventDvbStreams() {
        assertEquals("7°W · Nilesat", PlaylistParser.fromEnigmaNamespace("0DCA0000"))
        val content = "#SERVICE 1:0:1:abcd:0000:0000:0DCA0000:0:0:0:\n#DESCRIPTION Satellite Channel\n"
        val channel = PlaylistParser.parseEnigmaBouquet(StringReader(content), "import:bouquet.tv").single()
        assertTrue(channel.streamUrl.isBlank())
        assertEquals("Satellite Channel", channel.name)
        assertEquals("7°W · Nilesat", channel.satellite)
    }

    @Test fun dropsNonHttpScheme() {
        val text = "#EXTM3U\n#EXTINF:-1,Local file\nfile:///tmp/a.m3u8\n"
        assertTrue(PlaylistParser.parse(StringReader(text), "sample").isEmpty())
    }

    @Test fun stableIdsMatchAcrossRefreshes() {
        val text = "#EXTM3U\n#EXTINF:-1,One\nhttps://example.com/one.m3u8\n"
        val x = PlaylistParser.parse(StringReader(text), "source").single().id
        val y = PlaylistParser.parse(StringReader(text), "source").single().id
        assertEquals(x, y)
    }
}

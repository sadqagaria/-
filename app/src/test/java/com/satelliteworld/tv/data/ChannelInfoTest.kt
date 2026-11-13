package com.satelliteworld.tv.data

import org.junit.Assert.*
import org.junit.Test

class ChannelInfoTest {
    private fun ch(name:String, epg:String = "", country:String = "EG", stream:String = "") =
        ChannelEntry(id="i",name=name,streamUrl=stream,group="Egypt",source="test",country=country,epgId=epg)

    @Test fun arabicAndLatinSearch() {
        val c=ch("Nile Drama", "NileDrama.eg")
        assertTrue(ChannelInfo.matches(c,"نايل دراما"))
        assertTrue(ChannelInfo.matches(c,"NILEDRAMA"))
        assertTrue(ChannelInfo.bilingualName(c).contains("نايل دراما"))
        assertEquals("مسلسلات",ChannelInfo.category(c))
    }
    @Test fun arabicDiacriticsNormalization() {
        assertEquals(ChannelInfo.normalize("الْقُرْآن"),ChannelInfo.normalize("القران"))
    }
    @Test fun neverInventSatelliteFromCountry() {
        val c=ch("Nile Drama")
        assertFalse(ChannelInfo.nilesatVerified(c))
        assertTrue(ChannelInfo.inEgyptDirectory(c))
    }
    @Test fun mergesOnlyMatchingEpgReferencesWithFallbacks() {
        val one=ch("Nile Drama","NileDrama.eg@SD",stream="https://one.test/live.m3u8")
        val two=ch("Nile Drama","NileDrama.eg",stream="https://two.test/live.m3u8")
        val result=CatalogMerger.merge(listOf(one,two))
        assertEquals(1,result.size)
        assertEquals(1,result.single().alternateUrls.size)
        assertTrue(result.single().streamUrl.isNotBlank())
    }
    @Test fun guideOnlyNotMisrepresentedAsPlayable() {
        val extra=EgyptianGuides.supplement(listOf(ch("Other Egyptian TV")))
        val nile=extra.first{it.name == "Nile Drama"}
        assertEquals("",nile.streamUrl)
        assertFalse(ChannelInfo.nilesatVerified(nile))
    }
}

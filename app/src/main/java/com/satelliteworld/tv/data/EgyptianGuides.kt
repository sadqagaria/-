package com.satelliteworld.tv.data

/**
 * A SMALL offline recovery list for search: it is NOT a current satellite transponder
 * scan, nor evidence of internet streaming. Live directory metadata is preferred.
 */
object EgyptianGuides {
    private val known = listOf(
        "Nile Drama" to "نايل دراما", "Nile Cinema" to "نايل سينما",
        "Nile Sport" to "نايل سبورت", "Nile Comedy" to "نايل كوميدي",
        "Nile Family" to "نايل العائلة", "Nile Life" to "نايل لايف",
        "Nile Cultural" to "نايل الثقافية", "Nile News" to "النيل للأخبار",
        "Nile International" to "النيل الدولية", "DMC Drama" to "دي إم سي دراما",
        "ON Time Sports" to "أون تايم سبورتس", "Al Qahera News" to "القاهرة الإخبارية",
        "Al Nahar Drama" to "النهار دراما", "CBC Drama" to "سي بي سي دراما",
        "Rotana Cinema" to "روتانا سينما", "MBC Masr" to "إم بي سي مصر",
        "MBC Masr 2" to "إم بي سي مصر 2", "Koogi TV" to "كوكي"
    )

    fun supplement(channels: List<ChannelEntry>): List<ChannelEntry> {
        val names = channels.flatMap { listOf(it.name, it.aliases) }.flatMap { it.split(';') }
            .map { ChannelInfo.normalize(it) }.toHashSet()
        val extra = known.filter { (en, ar) ->
            ChannelInfo.normalize(en) !in names && ChannelInfo.normalize(ar) !in names
        }.mapIndexed { i,(en, ar) ->
            ChannelEntry(id="guide:egypt:${ChannelInfo.normalize(en)}", name=en, streamUrl="",
                group="دليل مصر (بيانات القمر غير مؤكدة)", source="offline:egypt-guide",
                country="EG", aliases=ar, position=80_000+i)
        }
        return channels + extra
    }
}

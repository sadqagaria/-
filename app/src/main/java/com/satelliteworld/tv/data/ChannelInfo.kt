package com.satelliteworld.tv.data

import java.text.Normalizer
import java.util.Locale

/**
 * Search aliases and genre grouping are descriptive only. They are NOT evidence
 * that a channel is carried on a satellite, has a playable feed, or is licensed.
 */
object ChannelInfo {
    private val egyptianNames = mapOf(
        "niledrama" to "نايل دراما", "nilecomedy" to "نايل كوميدي",
        "nilecinema" to "نايل سينما", "nilesport" to "نايل سبورت",
        "nilefamily" to "نايل العائلة", "nileculture" to "نايل الثقافية",
        "nilelife" to "نايل لايف", "nilenews" to "النيل للأخبار",
        "nileinternational" to "النيل الدولية", "nileeducational" to "النيل التعليمية",
        "alqaheranews" to "القاهرة الإخبارية", "alqahera" to "القاهرة",
        "almasriyah" to "المصرية", "aloula" to "الأولى المصرية",
        "egyptianchannel1" to "القناة الأولى", "egyptianchannel2" to "القناة الثانية",
        "dmc" to "دي إم سي", "dmcdrama" to "دي إم سي دراما",
        "on" to "أون", "ontv" to "أون تي في", "ondrama" to "أون دراما", "ontime sports" to "أون تايم سبورتس",
        "ontimesports" to "أون تايم سبورتس", "mbcmasr" to "إم بي سي مصر",
        "mbcmasr1" to "إم بي سي مصر", "mbcmasr2" to "إم بي سي مصر 2",
        "rotanacinema" to "روتانا سينما", "rotanaclassic" to "روتانا كلاسيك",
        "rotanadrama" to "روتانا دراما", "rotanakhalejia" to "روتانا خليجية",
        "alhayat" to "الحياة", "alhayattv" to "الحياة",
        "alquranalkarim" to "القرآن الكريم", "qurantv" to "قناة القرآن الكريم",
        "alquran" to "القرآن الكريم", "iqraa" to "اقرأ",
        "alnas" to "الناس", "elnas" to "الناس", "alrahma" to "الرحمة",
        "alkahera wal nas" to "القاهرة والناس", "alqaherawalnas" to "القاهرة والناس",
        "alnahar" to "النهار", "alnahardrama" to "النهار دراما",
        "sadaelbalad" to "صدى البلد", "sadaelbaladdrama" to "صدى البلد دراما",
        "cbc" to "سي بي سي", "cbcsofra" to "سي بي سي سفرة", "cbcdrama" to "سي بي سي دراما",
        "alghadtv" to "الغد", "alghadplus" to "الغد بلس",
        "koogitv" to "كوكي", "majid" to "ماجد", "spacetoon" to "سبيستون",
        "sat7" to "سات 7", "aghapytv" to "أغابي",
        "huda" to "هدى", "hudatv" to "هدى", "watan" to "وطن", "watantv" to "وطن",
        "mecatv" to "مكة", "meccatv" to "مكة", "saudiqurantv" to "السعودية قرآن"
    )

    fun normalize(value: String): String {
        val deaccented = Normalizer.normalize(value.trim().lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[ًٌٍَُِّْٰـ]"), "")
            .replace(Regex("[أإآٱ]"), "ا")
            .replace('ى', 'ي').replace('ة', 'ه').replace('ؤ', 'و').replace('ئ', 'ي')
        return deaccented.replace(Regex("[^\\p{L}\\p{N}]"), "")
    }

    fun arabicAlias(name: String, epgId: String = ""): String {
        val ids = listOf(epgId.substringBefore('@').substringBeforeLast('.'), name)
        for (id in ids) {
            val result = egyptianNames[normalize(id)]
            if (result != null) return result
        }
        return ""
    }

    fun bilingualName(c: ChannelEntry): String {
        val ar = c.aliases.split(';').firstOrNull { it.any { ch -> ch in '\u0600'..'\u06ff' } }
            ?: arabicAlias(c.name, c.epgId)
        return if (ar.isNullOrBlank() || normalize(ar) == normalize(c.name)) c.name else "$ar  ·  ${c.name}"
    }

    fun matches(c: ChannelEntry, query: String): Boolean {
        val needle = normalize(query)
        if (needle.isEmpty()) return true
        return sequenceOf(c.name, c.aliases, arabicAlias(c.name, c.epgId), c.group,
            c.epgId, c.country, c.position.toString()).any { normalize(it).contains(needle) }
    }

    fun category(c: ChannelEntry): String {
        val existing = c.category.lowercase(Locale.ROOT)
        val joined = "${c.name} ${c.group} ${c.aliases} $existing".lowercase(Locale.ROOT)
        fun has(vararg terms: String) = terms.any { joined.contains(it, true) }
        if (has("sport", "رياض", "كرة", "football", "soccer", "bein", "espn")) return "رياضة"
        if (has("film", "movie", "cinema", "افلام", "أفلام", "سينما", "action")) return "أفلام"
        if (has("drama", "series", "serial", "مسلسل", "دراما")) return "مسلسلات"
        if (has("cartoon", "kids", "animation", "child", "كرتون", "اطفال", "أطفال", "كوكي", "spacetoon")) return "كرتون"
        if (has("quran", "قران", "قرآن", "islam", "relig", "دين", "مكه", "مكة", "الناس", "iqraa", "اقرأ")) return "دين وقرآن"
        if (has("news", "اخبار", "أخبار", "politics", "الاخبار", "الإخبار")) return "أخبار"
        if (has("documentary", "وثائقي", "nature", "science", "history", "علوم")) return "وثائقي"
        return "أخرى"
    }

    fun inEgyptDirectory(c: ChannelEntry): Boolean =
        c.country.equals("EG", true) || c.epgId.contains(".eg", true) ||
            c.source == "github:iptv-eg-directory" || c.satellite.contains("7°W", true) ||
            c.group.equals("Egypt", true) || c.group.contains("مصر")

    fun nilesatVerified(c: ChannelEntry) =
        c.satellite.contains("7°W", true) || c.satellite.contains("nilesat", true)

    fun isEuropean(c: ChannelEntry): Boolean {
        val eu = setOf("GB","UK","FR","DE","IT","ES","PT","NL","BE","SE","NO","FI","DK","PL","IE","CH","AT","GR","RO","CZ","HU","TR","UA")
        return c.country.uppercase(Locale.ROOT) in eu ||
            listOf("europe", "united kingdom", "france", "germany", "italy", "spain", "portugal", "netherlands").any { c.group.contains(it,true) }
    }

    fun stableMergeKey(c: ChannelEntry): String {
        val epg = c.epgId.substringBefore('@').trim()
        return if (epg.isNotEmpty()) "epg:${epg.lowercase(Locale.ROOT)}"
        else "name:${normalize(c.name)}:${c.country.uppercase(Locale.ROOT)}"
    }
}

/** Merges exact EPG IDs across independent GitHub playlists; never guesses links for a different channel. */
object CatalogMerger {
    fun sortManual(channels: List<ChannelEntry>, saved: String): List<ChannelEntry> {
        val rank = saved.split('|').withIndex().associate { it.value to it.index }
        return channels.sortedWith(compareBy<ChannelEntry> { rank[it.id] ?: Int.MAX_VALUE }
            .thenBy { if (it.source.startsWith("import:")) 0 else 1 }
            .thenBy { it.position })
    }
    fun merge(entries: List<ChannelEntry>): List<ChannelEntry> {
        val nameToEpg = HashMap<String, String>()
        for (c in entries) if (c.epgId.isNotBlank()) {
            val key = ChannelInfo.stableMergeKey(c)
            val norm = ChannelInfo.normalize(c.name)
            if (norm.isNotBlank() && c.country.isNotBlank()) nameToEpg["$norm:${c.country.uppercase(Locale.ROOT)}"] = key
        }
        val grouped = LinkedHashMap<String, MutableList<ChannelEntry>>()
        for (c in entries) {
            val key = if (c.epgId.isNotBlank()) ChannelInfo.stableMergeKey(c) else
                nameToEpg["${ChannelInfo.normalize(c.name)}:${c.country.uppercase(Locale.ROOT)}"] ?: ChannelInfo.stableMergeKey(c)
            grouped.getOrPut(key) { mutableListOf() }.add(c)
        }
        return grouped.map { (key, group) ->
            val guide = group.firstOrNull { it.source == "github:iptv-eg-directory" }
            val ordered = group.sortedWith(compareBy<ChannelEntry> {
                when {
                    it.source.startsWith("import:") -> 0
                    it.source == "github:free-tv" -> 1
                    it.source == "github:iptv-org-country" -> 2
                    else -> 3
                }
            }.thenBy { it.position })
            val main = ordered.firstOrNull { it.streamUrl.isNotBlank() } ?: guide ?: ordered.first()
            val urls = ordered.map { it.streamUrl }.filter { it.startsWith("https://") || it.startsWith("http://") }.distinct()
            val aliases = (group.flatMap { it.aliases.split(';') } + group.map { it.name } +
                listOf(ChannelInfo.arabicAlias(main.name, main.epgId))).map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(";")
            main.copy(
                id = "merged:$key", streamUrl = urls.firstOrNull().orEmpty(),
                alternateUrls = urls.drop(1),
                aliases = aliases,
                category = guide?.category?.ifBlank { main.category } ?: main.category,
                country = main.country.ifBlank { guide?.country.orEmpty() },
                logo = main.logo.ifBlank { guide?.logo.orEmpty() },
                satellite = ordered.firstOrNull { ChannelInfo.nilesatVerified(it) }?.satellite.orEmpty(),
                position = ordered.firstOrNull { it.source.startsWith("import:") }?.position ?: main.position,
                source = if (ordered.any { it.source.startsWith("import:") }) "import:merged" else main.source
            )
        }.sortedWith(compareBy<ChannelEntry> { if (it.source.startsWith("import:")) 0 else 1 }.thenBy { it.position }.thenBy { it.name })
    }
}

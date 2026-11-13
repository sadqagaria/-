package com.satelliteworld.tv.data

import java.io.BufferedReader
import java.io.Reader
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

/** Metadata describes catalog entries. It does not imply permission to rebroadcast or playback availability. */
data class ChannelEntry(
    val id: String,
    val name: String,
    val streamUrl: String,
    val group: String,
    val satellite: String = "",
    val logo: String = "",
    val epgId: String = "",
    val source: String,
    val radio: Boolean = false,
    val position: Int = 0,
    val country: String = "",
    val aliases: String = "",
    val category: String = "",
    val alternateUrls: List<String> = emptyList(),
)

object PlaylistParser {
    private val attributes = Regex("""([A-Za-z][A-Za-z0-9_-]*)\s*=\s*"([^"]*)"""")
    private val safeRemote = Regex("""^https?://""", RegexOption.IGNORE_CASE)

    fun parse(reader: Reader, source: String, radio: Boolean = false): List<ChannelEntry> {
        val lines = BufferedReader(reader)
        var info = ""
        var groupOverride = ""
        var position = 0
        val result = ArrayList<ChannelEntry>()
        lines.forEachLine { raw ->
            if (position >= 60000) return@forEachLine
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.startsWith("#EXTINF:", true) -> { info = line; groupOverride = "" }
                line.startsWith("#EXTGRP:", true) -> groupOverride = line.substringAfter(':').trim()
                line.isBlank() || line.startsWith('#') -> Unit
                safeRemote.containsMatchIn(line) && safeRemote.find(line)?.range?.first == 0 -> {
                    val a = attributes.findAll(info).associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
                    val comma = info.indexOf(',')
                    val title = if (comma >= 0) info.substring(comma + 1).trim() else ""
                    val name = title.ifBlank { a["tvg-name"].orEmpty() }.ifBlank { "Channel ${position + 1}" }
                    val tagGroup = groupOverride.ifBlank { a["group-title"].orEmpty() }
                    val group = tagGroup.ifBlank { if (radio) "Radio" else "World" }
                    val explicitSatellite = a["satellite"].orEmpty().trim()
                    // Country/category group titles are never interpreted as a satellite.
                    val satellite = if (explicitSatellite.isNotEmpty()) cleanSatellite(explicitSatellite)
                        else if (looksLikeSatellite(group)) cleanSatellite(group) else ""
                    val epgId = a["tvg-id"].orEmpty()
                    position++
                    val isRadio = radio || a["radio"].equals("true", true) || a["radio"].equals("1")
                    result += ChannelEntry(
                        id = stableId(source, epgId.ifBlank { name }, line),
                        name = name,
                        streamUrl = line,
                        group = group,
                        satellite = satellite,
                        logo = a["tvg-logo"].orEmpty(),
                        epgId = epgId,
                        source = source,
                        radio = isRadio,
                        position = a["tvg-chno"]?.toIntOrNull() ?: a["channel-number"]?.toIntOrNull() ?: position,
                        country = a["tvg-country"].orEmpty().ifBlank { epgId.substringBefore('@').substringAfterLast('.', "").takeIf { it.length == 2 }?.uppercase(Locale.ROOT).orEmpty() },
                        aliases = a["tvg-name"].orEmpty()
                    )
                    info = ""
                    groupOverride = ""
                }
                else -> { info = ""; groupOverride = "" }
            }
        }
        return result.distinctBy { it.id }
    }

    fun parseCsv(reader: Reader, source: String): List<ChannelEntry> {
        val lines = BufferedReader(reader)
        val header = lines.readLine()?.removePrefix("\uFEFF") ?: return emptyList()
        val cols = csvFields(header).map { it.trim().lowercase(Locale.ROOT) }
        val nameIndex = cols.indexOfFirst { it in setOf("name", "channel", "channel_name", "اسم القناة") }
        if (nameIndex < 0) return emptyList()
        fun field(v: List<String>, vararg names: String): String {
            val i = cols.indexOfFirst { it in names }
            return if (i >= 0 && i < v.size) v[i].trim() else ""
        }
        val result = ArrayList<ChannelEntry>()
        lines.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val cells = csvFields(line)
            val name = cells.getOrNull(nameIndex)?.trim().orEmpty()
            if (name.isBlank()) return@forEachLine
            val stream = field(cells, "url", "stream", "stream_url", "link")
            if (stream.isNotEmpty() && safeRemote.find(stream)?.range?.first != 0) return@forEachLine
            val position = field(cells, "number", "position", "lcn").toIntOrNull() ?: result.size + 1
            val satellite = cleanSatellite(field(cells, "satellite", "sat", "القمر"))
            result += ChannelEntry(
                id = stableId(source, name, stream.ifBlank { "$position:$satellite" }),
                name = name, streamUrl = stream,
                group = field(cells, "group", "category", "country").ifBlank { "My Receiver" },
                satellite = satellite,
                logo = field(cells, "logo", "tvg-logo"),
                epgId = field(cells, "tvg-id", "epg_id", "id"),
                source = source,
                radio = field(cells, "radio", "is_radio").lowercase(Locale.ROOT) in setOf("true", "1", "yes"),
                position = position,
                country = field(cells, "country"),
                aliases = field(cells, "aliases", "alt_names", "arabic_name"),
                category = field(cells, "category", "genre")
            )
        }
        return result.distinctBy { it.id }
    }

    /** Enigma2 bouquet: satellite DVB services are catalog-only; IPTV service URLs can play. */
    fun parseEnigmaBouquet(reader: Reader, source: String): List<ChannelEntry> {
        val result = ArrayList<ChannelEntry>()
        var service = ""
        var name = ""
        fun flush() {
            if (service.isBlank()) return
            val parts = service.split(':')
            val namespace = parts.getOrNull(6).orEmpty()
            val satellite = fromEnigmaNamespace(namespace)
            val serviceType = parts.getOrNull(1)?.toIntOrNull() ?: 0
            // Enigma2 marker and alternative-reference records are not channels.
            if (serviceType in setOf(64, 320, 832) || service.contains("FROM BOUQUET")) {
                service = ""; name = ""; return
            }
            val serviceUrl = service.split(':', limit = 11).getOrNull(10).orEmpty()
            val encoded = serviceUrl.substringBefore(':')
            val url = try { URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()) }
                catch (_: Exception) { "" }
            val validUrl = url.takeIf { safeRemote.find(it)?.range?.first == 0 }.orEmpty()
            val actualName = name.ifBlank { "Service ${result.size + 1}" }
            result += ChannelEntry(
                id = stableId(source, actualName, namespace + validUrl),
                name = actualName, streamUrl = validUrl,
                group = "My Receiver", satellite = satellite, source = source,
                radio = source.contains("radio", true), position = result.size + 1
            )
            service = ""; name = ""
        }
        BufferedReader(reader).forEachLine { line ->
            when {
                line.startsWith("#SERVICE ") -> { flush(); service = line.removePrefix("#SERVICE ").trim() }
                line.startsWith("#DESCRIPTION ") -> name = line.removePrefix("#DESCRIPTION ").trim()
                line.startsWith("#NAME ") -> Unit
            }
        }
        flush()
        return result
    }

    fun fromEnigmaNamespace(namespace: String): String {
        val n = namespace.toLongOrNull(16) ?: return ""
        val orbital = ((n ushr 16) and 0xFFFF).toInt()
        if (orbital == 0 || orbital > 3600) return ""
        val degrees = if (orbital > 1800) (3600 - orbital) / 10.0 else orbital / 10.0
        val west = orbital > 1800
        if (west && degrees in 6.9..7.1) return "7°W · Nilesat"
        if (!west && degrees in 25.9..26.1) return "26°E · Badr/Arabsat"
        if (!west && degrees in 12.9..13.1) return "13°E · Hot Bird"
        return "%.1f°%s".format(Locale.US, degrees, if (west) "W" else "E")
    }

    private fun looksLikeSatellite(value: String): Boolean {
        val s = value.lowercase(Locale.ROOT)
        return listOf("nilesat", "نايل سات", "7°w", "7w", "arabsat", "بدر", "26e", "26°e", "hotbird", "hot bird", "هوت بيرد", "13°e", "13e").any { it in s }
    }

    fun cleanSatellite(value: String): String {
        val s = value.lowercase(Locale.ROOT)
        return when {
            "nilesat" in s || "نايل" in s || "7w" in s || "7°w" in s -> "7°W · Nilesat"
            "badr" in s || "arabsat" in s || "عرب" in s || "26e" in s || "26°e" in s -> "26°E · Badr/Arabsat"
            "hotbird" in s || "hot bird" in s || "هوت" in s || "13e" in s || "13°e" in s -> "13°E · Hot Bird"
            else -> value.trim()
        }
    }

    fun stableId(source: String, name: String, link: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$source\u0000$name\u0000$link".toByteArray(StandardCharsets.UTF_8))
        return bytes.take(16).joinToString("") { "%02x".format(it) }
    }

    fun csvFields(line: String): List<String> {
        val fields = mutableListOf<String>()
        val cur = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            when (line[i]) {
                '"' -> if (quoted && i + 1 < line.length && line[i + 1] == '"') { cur.append('"'); i++ } else quoted = !quoted
                ',' -> if (quoted) cur.append(',') else { fields += cur.toString(); cur.setLength(0) }
                else -> cur.append(line[i])
            }
            i++
        }
        fields += cur.toString()
        return fields
    }
}

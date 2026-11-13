package com.satelliteworld.tv.recording

import java.net.URI

/** Strict subset of clear, MPEG-TS HLS. No DRM/encryption bypass or fMP4 concatenation. */
data class HlsVariant(val url: String, val height: Int, val bandwidth: Long, val audioGroup: String = "")
data class HlsMedia(val segments: List<String>, val targetDuration: Int, val ended: Boolean,
                    val encrypted: Boolean, val fragmentedMp4: Boolean)

object HlsPlaylist {
    private fun attr(line: String, key: String): String {
        val found = Regex("(?:^|,)$key=\\\"([^\\\"]*)\\\"|(?:^|,)$key=([^,]+)")
            .find(line.substringAfter(':', line)) ?: return ""
        return found.groupValues[1].ifBlank { found.groupValues[2] }
    }

    fun abs(base: String, url: String): String = URI(base).resolve(url.trim().replace(" ", "%20")).toASCIIString()

    fun variants(base: String, text: String): List<HlsVariant> {
        val lines = text.lineSequence().map { it.trim() }.toList()
        val list = ArrayList<HlsVariant>()
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF:", true)) continue
            val path = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith('#') } ?: continue
            val resolution = attr(line, "RESOLUTION")
            val height = resolution.substringAfter('x', "0").toIntOrNull() ?: 0
            val rate = attr(line, "BANDWIDTH").toLongOrNull() ?: 0L
            list += HlsVariant(abs(base, path), height, rate, attr(line, "AUDIO"))
        }
        return list
    }

    fun choose(variants: List<HlsVariant>, targetHeight: Int): HlsVariant? {
        if (variants.isEmpty()) return null
        if (targetHeight <= 0) return variants.maxWithOrNull(compareBy<HlsVariant> { it.height }.thenBy { it.bandwidth })
        val suitable = variants.filter { it.height in 1..targetHeight }
        if (suitable.isNotEmpty()) {
            // Prefer requested resolution; same resolution uses the smallest available bitrate.
            return suitable.sortedWith(compareByDescending<HlsVariant> { it.height }.thenBy { it.bandwidth }).first()
        }
        return variants.filter { it.height > 0 }.minWithOrNull(compareBy<HlsVariant> { it.height }.thenBy { it.bandwidth }) ?: variants.first()
    }

    fun media(base: String, text: String): HlsMedia {
        val list = ArrayList<String>()
        var expecting = false
        var duration = 4
        var encrypted = false
        var fmp4 = false
        var ended = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXT-X-KEY:", true) && !line.contains("METHOD=NONE", true) -> encrypted = true
                line.startsWith("#EXT-X-MAP:", true) -> fmp4 = true
                line.startsWith("#EXT-X-BYTERANGE", true) -> fmp4 = true // byte-range needs special handling
                line.startsWith("#EXT-X-TARGETDURATION:", true) -> duration = line.substringAfter(':').toIntOrNull()?.coerceIn(1,30) ?: 4
                line.startsWith("#EXT-X-ENDLIST", true) -> ended = true
                line.startsWith("#EXTINF:", true) -> expecting = true
                expecting && line.isNotEmpty() && !line.startsWith('#') -> {
                    val path = abs(base,line)
                    if (path.substringBefore('?').substringAfterLast('.').lowercase() in listOf("m4s","mp4","cmfv","cmfa")) fmp4 = true
                    list += path
                    expecting = false
                }
            }
        }
        return HlsMedia(list,duration,ended,encrypted,fmp4)
    }
}

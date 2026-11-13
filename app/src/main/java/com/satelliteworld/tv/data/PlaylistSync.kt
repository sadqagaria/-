package com.satelliteworld.tv.data

import android.content.Context
import org.json.JSONArray
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Sources are public Github-hosted directories; entries are not automatically rights-cleared. */
object PlaylistSync {
    const val TV_SOURCE = "github:iptv-org-country"
    const val FREE_SOURCE = "github:free-tv"
    const val RADIO_SOURCE = "github:verified-radio"
    const val EGYPT_DIRECTORY = "github:iptv-eg-directory"
    const val TV_URL = "https://iptv-org.github.io/iptv/index.country.m3u"
    const val FREE_URL = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
    const val RADIO_URL = "https://raw.githubusercontent.com/eyalev/verified-radio-streams/main/m3u/all.m3u"
    const val DIRECTORY_URL = "https://iptv-org.github.io/api/channels.json"

    data class SyncResult(val television: Int, val radio: Int, val egyptDirectory: Int, val messages: List<String>)

    /** Optional baked-in GitHub CSV snapshot; keeps an Egyptian directory usable before network sync. */
    fun seedCachedEgypt(context: Context): Int {
        return try {
            val db = ReceiverDatabase(context)
            try {
                if (db.all().any { it.source == EGYPT_DIRECTORY }) return 0
                val entries = context.assets.open("egypt-directory.csv").bufferedReader(Charsets.UTF_8).use {
                    PlaylistParser.parseCsv(it, EGYPT_DIRECTORY)
                }
                if (entries.size >= 30) db.replaceSource(EGYPT_DIRECTORY, entries) else 0
            } finally { db.close() }
        } catch (_: Exception) { 0 }
    }

    fun sync(context: Context, onUpdate: (String) -> Unit = {}): SyncResult {
        val db = ReceiverDatabase(context)
        var tv = 0
        var radio = 0
        var egypt = 0
        val messages = mutableListOf<String>()
        val tasks = listOf(
            Triple(TV_SOURCE, TV_URL, false),
            Triple(FREE_SOURCE, FREE_URL, false),
            Triple(RADIO_SOURCE, RADIO_URL, true)
        )
        try {
            for ((source, url, isRadio) in tasks) {
                val label = when(source){ TV_SOURCE->"IPTV-org"; FREE_SOURCE->"Free-TV"; else->"Radio" }
                try {
                    onUpdate("جاري تحديث $label من GitHub...")
                    val entries = fetchPlaylist(url, source, isRadio)
                    val min = when(source){ TV_SOURCE->100; FREE_SOURCE->10; else->50 }
                    if (entries.size < min) throw IllegalStateException("قائمة ناقصة: ${entries.size}")
                    db.replaceSource(source, entries)
                    if (isRadio) radio += entries.size else tv += entries.size
                    messages += "$label: ${entries.size}"
                } catch (e: Exception) {
                    messages += "$label: تعذر التحديث؛ النسخة السابقة محفوظة (${e.message?.take(90)})"
                }
            }
            try {
                onUpdate("جاري استيراد دليل القنوات المصرية بأسمائها وتصنيفاتها...")
                val items = fetchEgyptDirectory()
                if (items.size < 30) throw IllegalStateException("دليل مصر ناقص: ${items.size}")
                db.replaceSource(EGYPT_DIRECTORY, items)
                egypt = items.size
                messages += "دليل مصر: $egypt"
            } catch (e: Exception) {
                messages += "دليل مصر: ${e.message?.take(90)}، القائمة المحفوظة لم تُحذف"
            }
            db.setPreference("last_sync", System.currentTimeMillis().toString())
        } finally { db.close() }
        return SyncResult(tv, radio, egypt, messages)
    }

    private fun fetchPlaylist(url: String, source: String, radio: Boolean): List<ChannelEntry> {
        val connection = connect(url)
        try {
            BufferedReader(InputStreamReader(limit(connection.inputStream, 30_000_000L), StandardCharsets.UTF_8)).use {
                return PlaylistParser.parse(it, source, radio)
            }
        } finally { connection.disconnect() }
    }

    /** Does not infer DVB satellite carriage from an Egyptian country code. */
    private fun fetchEgyptDirectory(): List<ChannelEntry> {
        val connection = connect(DIRECTORY_URL)
        try {
            val data = limit(connection.inputStream, 15_000_000L).bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            val json = JSONArray(data)
            val entries = ArrayList<ChannelEntry>()
            for (index in 0 until json.length()) {
                val obj = json.optJSONObject(index) ?: continue
                if (!obj.optString("country").equals("EG", true)) continue
                if (obj.optBoolean("is_nsfw", false)) continue
                if (!obj.isNull("closed") && obj.optString("closed").isNotBlank()) continue
                val channelId = obj.optString("id")
                val name = obj.optString("name")
                if (channelId.isBlank() || name.isBlank()) continue
                val alts = obj.optJSONArray("alt_names")
                val aliases = (0 until (alts?.length() ?: 0)).mapNotNull { alts?.optString(it) }.joinToString(";")
                val categories = obj.optJSONArray("categories")
                val genres = (0 until (categories?.length() ?: 0)).mapNotNull { categories?.optString(it) }.joinToString(";")
                entries += ChannelEntry(
                    id = "meta:$channelId", name = name, streamUrl = "", group = "دليل القنوات المصرية",
                    satellite = "", logo = "", epgId = channelId, source = EGYPT_DIRECTORY,
                    position = entries.size + 1, country = "EG", aliases = aliases, category = genres
                )
            }
            return entries
        } finally { connection.disconnect() }
    }

    private fun connect(address: String): HttpURLConnection {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 18_000
        connection.readTimeout = 50_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "SatelliteWorldTV/0.3 Android")
        if (connection.responseCode !in 200..299) {
            val code = connection.responseCode
            connection.disconnect()
            throw IOException("HTTP $code")
        }
        return connection
    }

    private fun limit(stream: java.io.InputStream, limit: Long) = object: FilterInputStream(stream) {
        var used = 0L
        override fun read(): Int {
            val n = super.read()
            if (n >= 0 && ++used > limit) throw IOException("الملف أكبر من الحد المسموح")
            return n
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b,off,len)
            if (n > 0) { used += n; if (used > limit) throw IOException("الملف أكبر من الحد المسموح") }
            return n
        }
    }
}

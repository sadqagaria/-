package com.satelliteworld.tv.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Reader
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Optional XMLTV import. No EPG is synthesized for channels without a matching XMLTV ID. */
class EpgImporter(context: Context) : SQLiteOpenHelper(context, "receiver-epg.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE programmes (
            channel TEXT NOT NULL,
            start_ms INTEGER NOT NULL,
            stop_ms INTEGER NOT NULL,
            title TEXT NOT NULL,
            PRIMARY KEY(channel,start_ms)
        )""".trimIndent())
        db.execSQL("CREATE INDEX epg_lookup ON programmes(channel,start_ms,stop_ms)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun importXml(reader: Reader): Int {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(reader)
        val incoming = ArrayList<ProgramInfo>()
        var ch = ""
        var title = ""
        var start = 0L
        var stop = 0L
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT && incoming.size < 75000) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "programme" -> {
                        ch = parser.getAttributeValue(null, "channel").orEmpty()
                        start = xmlTime(parser.getAttributeValue(null, "start"))
                        stop = xmlTime(parser.getAttributeValue(null, "stop"))
                        title = ""
                    }
                    "title" -> if (ch.isNotBlank() && title.isBlank()) title = parser.nextText()
                }
            } else if (event == XmlPullParser.END_TAG && parser.name == "programme") {
                if (ch.isNotBlank() && title.isNotBlank() && start > 0 && stop > start)
                    incoming += ProgramInfo(ch, start, stop, title.trim().take(300))
                ch = ""; title = ""
            }
            event = parser.next()
        }
        if (incoming.isEmpty()) return 0
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL("DELETE FROM programmes")
            val statement = db.compileStatement(
                "INSERT OR REPLACE INTO programmes(channel,start_ms,stop_ms,title) VALUES(?,?,?,?)"
            )
            incoming.forEach {
                statement.clearBindings()
                statement.bindString(1, it.channel)
                statement.bindLong(2, it.start)
                statement.bindLong(3, it.stop)
                statement.bindString(4, it.title)
                statement.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return incoming.size
    }

    fun nowAndNext(channelId: String, now: Long = System.currentTimeMillis()): Pair<String, String> {
        if (channelId.isBlank()) return "" to ""
        val current = readableDatabase.rawQuery(
            "SELECT title FROM programmes WHERE channel=? AND start_ms<=? AND stop_ms>? ORDER BY start_ms DESC LIMIT 1",
            arrayOf(channelId, now.toString(), now.toString())
        ).use { if (it.moveToFirst()) it.getString(0) else "" }
        val next = readableDatabase.rawQuery(
            "SELECT title FROM programmes WHERE channel=? AND start_ms>? ORDER BY start_ms LIMIT 1",
            arrayOf(channelId, now.toString())
        ).use { if (it.moveToFirst()) it.getString(0) else "" }
        return current to next
    }

    private fun xmlTime(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0
        val value = raw.trim()
        return try {
            val basic = LocalDateTime.parse(value.take(14), DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.US))
            val offset = Regex("""([+-])(\d\d)(\d\d)$""").find(value)?.let {
                val sign = if (it.groupValues[1] == "-") -1 else 1
                ZoneOffset.ofHoursMinutes(sign * it.groupValues[2].toInt(), sign * it.groupValues[3].toInt())
            } ?: ZoneOffset.UTC
            basic.toInstant(offset).toEpochMilli()
        } catch (_: Exception) { 0L }
    }
}

data class ProgramInfo(val channel: String, val start: Long, val stop: Long, val title: String)

package com.satelliteworld.tv.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues

class ReceiverDatabase(context: Context) : SQLiteOpenHelper(context, "receiver-catalog.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE channels (
            id TEXT PRIMARY KEY NOT NULL,
            name TEXT NOT NULL,
            stream_url TEXT NOT NULL,
            group_name TEXT NOT NULL,
            satellite TEXT NOT NULL,
            logo TEXT NOT NULL,
            epg_id TEXT NOT NULL,
            source TEXT NOT NULL,
            radio INTEGER NOT NULL,
            position INTEGER NOT NULL,
            country TEXT NOT NULL,
            aliases TEXT NOT NULL DEFAULT '',
            genre TEXT NOT NULL DEFAULT ''
        )""".trimIndent())
        db.execSQL("CREATE INDEX channels_source_idx ON channels(source)")
        db.execSQL("CREATE INDEX channels_sat_idx ON channels(satellite)")
        db.execSQL("CREATE TABLE favorites (channel_id TEXT PRIMARY KEY NOT NULL)")
        db.execSQL("CREATE TABLE preferences (pref_key TEXT PRIMARY KEY NOT NULL, pref_value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE manual_order (channel_id TEXT PRIMARY KEY NOT NULL, rank INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE channels ADD COLUMN aliases TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE channels ADD COLUMN genre TEXT NOT NULL DEFAULT ''")
        }
    }

    @Synchronized
    fun replaceSource(source: String, items: List<ChannelEntry>): Int {
        if (items.isEmpty()) return 0 // Network failure must not erase the old catalog.
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("channels", "source = ?", arrayOf(source))
            for (item in items) upsert(db, item)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return items.size
    }

    private fun upsert(db: SQLiteDatabase, item: ChannelEntry) {
        val v = ContentValues().apply {
            put("id", item.id)
            put("name", item.name)
            put("stream_url", item.streamUrl)
            put("group_name", item.group)
            put("satellite", item.satellite)
            put("logo", item.logo)
            put("epg_id", item.epgId)
            put("source", item.source)
            put("radio", if (item.radio) 1 else 0)
            put("position", item.position)
            put("country", item.country)
            put("aliases", item.aliases)
            put("genre", item.category)
        }
        db.insertWithOnConflict("channels", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun all(): List<ChannelEntry> {
        val result = ArrayList<ChannelEntry>()
        val query = """SELECT c.id,c.name,c.stream_url,c.group_name,c.satellite,c.logo,c.epg_id,
            c.source,c.radio,c.position,c.country,c.aliases,c.genre FROM channels c
            LEFT JOIN manual_order o ON c.id=o.channel_id
            ORDER BY CASE WHEN o.rank IS NULL THEN 1 ELSE 0 END, o.rank,
                CASE WHEN c.source LIKE 'import:%' THEN 0 ELSE 1 END,
                c.source, c.position""".trimIndent()
        readableDatabase.rawQuery(query, null).use { cursor ->
            while (cursor.moveToNext()) {
                result += ChannelEntry(
                    id = cursor.getString(0), name = cursor.getString(1),
                    streamUrl = cursor.getString(2), group = cursor.getString(3),
                    satellite = cursor.getString(4), logo = cursor.getString(5),
                    epgId = cursor.getString(6), source = cursor.getString(7),
                    radio = cursor.getInt(8) != 0, position = cursor.getInt(9),
                    country = cursor.getString(10), aliases = cursor.getString(11),
                    category = cursor.getString(12)
                )
            }
        }
        return result
    }

    @Synchronized
    fun favorites(): Set<String> {
        val ids = HashSet<String>()
        readableDatabase.rawQuery("SELECT channel_id FROM favorites", null).use { c ->
            while (c.moveToNext()) ids.add(c.getString(0))
        }
        return ids
    }

    @Synchronized
    fun toggleFavorite(id: String): Boolean {
        val db = writableDatabase
        val exists = db.rawQuery("SELECT 1 FROM favorites WHERE channel_id = ?", arrayOf(id))
            .use { it.moveToFirst() }
        if (exists) db.delete("favorites", "channel_id=?", arrayOf(id))
        else db.execSQL("INSERT OR IGNORE INTO favorites(channel_id) VALUES(?)", arrayOf(id))
        return !exists
    }

    @Synchronized
    fun setPreference(key: String, value: String) {
        val v = ContentValues().apply { put("pref_key", key); put("pref_value", value) }
        writableDatabase.insertWithOnConflict("preferences", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    @Synchronized
    fun getPreference(key: String): String {
        readableDatabase.rawQuery("SELECT pref_value FROM preferences WHERE pref_key=?", arrayOf(key))
            .use { return if (it.moveToFirst()) it.getString(0) else "" }
    }

    @Synchronized
    fun saveManualOrder(ids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("manual_order", null, null)
            ids.forEachIndexed { i, id ->
                db.execSQL("INSERT INTO manual_order(channel_id,rank) VALUES(?,?)", arrayOf(id, i))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}

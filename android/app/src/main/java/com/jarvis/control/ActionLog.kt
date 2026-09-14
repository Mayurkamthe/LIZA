package com.jarvis.control

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** Lightweight persisted log of every request the bridge has served, shown
 * in ActivityLogActivity so the user can see exactly what JARVIS has done. */
object ActionLog {
    private var helper: DbHelper? = null

    private fun db(context: Context): SQLiteDatabase {
        if (helper == null) helper = DbHelper(context.applicationContext)
        return helper!!.writableDatabase
    }

    @Synchronized
    fun log(context: Context, entry: String) {
        val values = ContentValues()
        values.put("entry", entry)
        values.put("ts", System.currentTimeMillis())
        db(context).insert("logs", null, values)
    }

    fun recentAsJson(context: Context, limit: Int = 50): JSONArray {
        val arr = JSONArray()
        val c = db(context).query(
            "logs", arrayOf("entry", "ts"), null, null, null, null, "ts DESC", limit.toString()
        )
        c.use {
            while (it.moveToNext()) {
                val o = JSONObject()
                o.put("entry", it.getString(0))
                o.put("ts", it.getLong(1))
                arr.put(o)
            }
        }
        return arr
    }

    private class DbHelper(context: Context) :
        SQLiteOpenHelper(context, "jarvis_log.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE logs (id INTEGER PRIMARY KEY, entry TEXT, ts INTEGER)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }
}

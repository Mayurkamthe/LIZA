package com.jarvis.control

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.ListView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Simple read-only screen listing recent bridge activity (every action
 * request the Termux agent has sent), newest first. */
class ActivityLogActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val listView = ListView(this)
        setContentView(listView)

        val entries = ActionLog.recentAsJson(this)
        val fmt = SimpleDateFormat("MM/dd HH:mm:ss", Locale.getDefault())
        val rows = (0 until entries.length()).map {
            val o = entries.getJSONObject(it)
            val time = fmt.format(Date(o.getLong("ts")))
            "$time  ${o.getString("entry")}"
        }
        listView.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, rows)
    }
}

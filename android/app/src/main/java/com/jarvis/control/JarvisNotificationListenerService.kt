package com.jarvis.control

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

/** Mirrors the device's recent notifications into an in-memory buffer so the
 * local bridge can hand them to the Termux agent on request (e.g. "read my
 * notifications"). Requires the user to grant Notification Access in
 * Settings -> Notification access. */
class JarvisNotificationListenerService : NotificationListenerService() {
    companion object {
        private val recent = mutableListOf<JSONObject>()
        private const val MAX_KEEP = 100
        private const val MAX_RETURN = 20

        fun latestAsJson(): JSONArray {
            val arr = JSONArray()
            synchronized(recent) {
                recent.takeLast(MAX_RETURN).forEach { arr.put(it) }
            }
            return arr
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence("android.title")?.toString() ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        if (title.isBlank() && text.isBlank()) return
        val entry = JSONObject()
            .put("app", sbn.packageName)
            .put("title", title)
            .put("text", text)
            .put("time", sbn.postTime)
        synchronized(recent) {
            recent.add(entry)
            if (recent.size > MAX_KEEP) recent.removeAt(0)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}

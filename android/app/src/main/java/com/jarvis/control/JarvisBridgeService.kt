package com.jarvis.control

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.URLEncoder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * Local-only HTTP bridge (127.0.0.1:PORT) that lets the Termux Python agent
 * drive the Accessibility Service, launch apps, and read notifications.
 * Runs as a foreground service so it survives Termux/agent restarts.
 *
 * Every request must include header `X-Jarvis-Token: <token>` matching the
 * token shown in MainActivity (stored in SharedPreferences, generated once).
 *
 * Endpoints:
 *   POST /action         {"action":"tap"|"swipe"|"type"|"back"|"home"|"recents"
 *                          |"tap_node"|"type_node", ...}
 *   POST /open_app       {"name":"..."} or {"package":"..."}
 *   GET  /screen          -> {"text": "..."}
 *   GET  /nodes           -> {"nodes": [{"id","text","desc","clickable","editable","bounds"}, ...]}
 *   GET  /notifications   -> {"notifications": [...]}
 *   GET  /log             -> {"log": [...]}
 *   POST /whatsapp_send   {"phone":"+91...","message":"...","auto_send":true}
 *        Opens WhatsApp on the given chat via the wa.me deep link with the
 *        message pre-filled, then (if auto_send) waits briefly and taps the
 *        Send button by matching its content-description. Requires the
 *        Accessibility Service to be enabled; fragile if WhatsApp's UI
 *        changes, and repeated automated sends risk WhatsApp's anti-spam
 *        detection -- use for occasional personal messages, not bulk sends.
 */
class JarvisBridgeService : Service() {

    private var serverThread: Thread? = null
    @Volatile private var running = false
    private val port = 8734
    private val channelId = "jarvis_bridge_channel"

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceNotification()
        startServer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        serverThread?.interrupt()
        super.onDestroy()
    }

    private fun startForegroundServiceNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "JARVIS Bridge", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("JARVIS is listening")
            .setContentText("Local control bridge active on port $port")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    private fun token(): String {
        val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        var t = prefs.getString("bridge_token", null)
        if (t == null) {
            t = UUID.randomUUID().toString().replace("-", "").take(24)
            prefs.edit().putString("bridge_token", t).apply()
        }
        return t
    }

    private fun startServer() {
        running = true
        serverThread = Thread {
            try {
                ServerSocket(port).use { server ->
                    while (running) {
                        val client = try { server.accept() } catch (e: Exception) { break }
                        Thread { handleClient(client) }.start()
                    }
                }
            } catch (e: Exception) {
                // server socket failed to bind (e.g. port already in use) -- give up quietly
            }
        }
        serverThread?.start()
    }

    private fun handleClient(client: Socket) {
        try {
            client.use {
                it.soTimeout = 10000
                val reader = BufferedReader(InputStreamReader(it.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0]
                val path = parts[1]

                var contentLength = 0
                var headerToken: String? = null
                var line: String?
                while (true) {
                    line = reader.readLine()
                    if (line.isNullOrEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("content-length:")) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                    if (lower.startsWith("x-jarvis-token:")) {
                        headerToken = line.substringAfter(":").trim()
                    }
                }

                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    reader.read(buf, 0, contentLength)
                    String(buf)
                } else ""

                val out = it.getOutputStream()

                if (headerToken != token()) {
                    respond(out, false, JSONObject().put("error", "unauthorized").toString())
                    return
                }

                ActionLog.log(this, "$method $path ${body.take(200)}")

                val responseJson = try {
                    route(method, path, body)
                } catch (e: Exception) {
                    JSONObject().put("error", e.message ?: "internal error")
                }
                respond(out, true, responseJson.toString())
            }
        } catch (e: Exception) {
            // malformed / dropped connection -- ignore
        }
    }

    private fun respond(out: OutputStream, ok: Boolean, body: String) {
        val status = if (ok) "200 OK" else "401 Unauthorized"
        val bytes = body.toByteArray()
        val header = "HTTP/1.1 $status\r\nContent-Type: application/json\r\n" +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun route(method: String, path: String, body: String): JSONObject {
        val svc = JarvisAccessibilityService.instance
        return when {
            path.startsWith("/action") && method == "POST" -> {
                if (svc == null) {
                    JSONObject().put("error", "accessibility service not enabled")
                } else {
                    val data = JSONObject(body)
                    when (data.optString("action")) {
                        "tap" -> {
                            svc.tap(data.getDouble("x").toFloat(), data.getDouble("y").toFloat())
                            JSONObject().put("ok", true)
                        }
                        "swipe" -> {
                            svc.swipe(
                                data.getDouble("x1").toFloat(), data.getDouble("y1").toFloat(),
                                data.getDouble("x2").toFloat(), data.getDouble("y2").toFloat()
                            )
                            JSONObject().put("ok", true)
                        }
                        "type" -> JSONObject().put("ok", svc.typeText(data.optString("text")))
                        "back" -> { svc.back(); JSONObject().put("ok", true) }
                        "home" -> { svc.home(); JSONObject().put("ok", true) }
                        "recents" -> { svc.recents(); JSONObject().put("ok", true) }
                        "tap_node" -> JSONObject().put("ok", svc.tapNode(data.getInt("id")))
                        "type_node" -> JSONObject().put(
                            "ok", svc.typeIntoNode(data.getInt("id"), data.optString("text"))
                        )
                        else -> JSONObject().put("error", "unknown action")
                    }
                }
            }
            path.startsWith("/nodes") && method == "GET" -> {
                if (svc == null) JSONObject().put("error", "accessibility service not enabled")
                else JSONObject().put("nodes", svc.getInteractiveNodesJson())
            }
            path.startsWith("/whatsapp_send") && method == "POST" -> {
                val data = JSONObject(body)
                val phone = data.optString("phone", "").filter { it.isDigit() || it == '+' }
                val message = data.optString("message", "")
                val autoSend = data.optBoolean("auto_send", true)
                if (phone.isEmpty() || message.isEmpty()) {
                    JSONObject().put("error", "phone and message are required")
                } else {
                    val encoded = URLEncoder.encode(message, "UTF-8")
                    val uri = Uri.parse("https://wa.me/${phone.removePrefix("+")}?text=$encoded")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    var autoSent = false
                    if (autoSend && svc != null) {
                        Thread.sleep(3500) // let WhatsApp load the chat + pre-filled text
                        autoSent = svc.tapNodeMatching("Send")
                    }
                    JSONObject().put("ok", true).put("auto_sent", autoSent)
                }
            }
            path.startsWith("/open_app") && method == "POST" -> {
                val data = JSONObject(body)
                val pkg = data.optString("package", "")
                val name = data.optString("name", "")
                val resolvedPkg = pkg.ifEmpty { resolvePackageByName(name) ?: "" }
                val launchIntent = if (resolvedPkg.isNotEmpty())
                    packageManager.getLaunchIntentForPackage(resolvedPkg) else null
                if (launchIntent == null) {
                    JSONObject().put("error", "app not found")
                } else {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    JSONObject().put("ok", true).put("package", resolvedPkg)
                }
            }
            path.startsWith("/screen") && method == "GET" -> {
                if (svc == null) JSONObject().put("error", "accessibility service not enabled")
                else JSONObject().put("text", svc.getScreenText())
            }
            path.startsWith("/notifications") && method == "GET" -> {
                JSONObject().put("notifications", JarvisNotificationListenerService.latestAsJson())
            }
            path.startsWith("/log") && method == "GET" -> {
                JSONObject().put("log", ActionLog.recentAsJson(this))
            }
            else -> JSONObject().put("error", "not found")
        }
    }

    private fun resolvePackageByName(name: String): String? {
        if (name.isEmpty()) return null
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in apps) {
            val label = pm.getApplicationLabel(app).toString()
            if (label.equals(name, ignoreCase = true) || label.contains(name, ignoreCase = true)) {
                return app.packageName
            }
        }
        return null
    }
}

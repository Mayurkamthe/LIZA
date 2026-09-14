package com.jarvis.control

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.UUID

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("jarvis_prefs", Context.MODE_PRIVATE)
        var token = prefs.getString("bridge_token", null)
        if (token == null) {
            token = UUID.randomUUID().toString().replace("-", "").take(24)
            prefs.edit().putString("bridge_token", token).apply()
        }

        val status = TextView(this).apply {
            text = "JARVIS Control\n\n" +
                "Setup:\n" +
                "1. Enable Accessibility Service (tap / swipe / type / screen-read)\n" +
                "2. Enable Notification Access (notification summaries)\n" +
                "3. Start the local bridge service\n" +
                "4. Paste this token into ~/.jarvis/config.json on the Termux side:\n\n" +
                token
            textSize = 16f
            setPadding(32, 48, 32, 32)
        }

        val accessibilityBtn = Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        val notificationBtn = Button(this).apply {
            text = "Open Notification Access Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }

        val startBridgeBtn = Button(this).apply {
            text = "Start Bridge Service"
            setOnClickListener {
                startForegroundService(Intent(this@MainActivity, JarvisBridgeService::class.java))
                Toast.makeText(this@MainActivity, "Bridge started on port 8734", Toast.LENGTH_SHORT).show()
            }
        }

        val logBtn = Button(this).apply {
            text = "View Activity Log"
            setOnClickListener { startActivity(Intent(this@MainActivity, ActivityLogActivity::class.java)) }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(accessibilityBtn)
            addView(notificationBtn)
            addView(startBridgeBtn)
            addView(logBtn)
        })
    }
}

package com.wakealarm

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val prefsName = "wake_alarm_prefs"
    private val keyServerUrl = "server_url"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val urlInput = findViewById<EditText>(R.id.serverUrlInput)
        val startButton = findViewById<Button>(R.id.startButton)
        val stopButton = findViewById<Button>(R.id.stopButton)
        val batteryButton = findViewById<Button>(R.id.batteryButton)
        val statusText = findViewById<TextView>(R.id.statusText)

        val prefs = getSharedPreferences(prefsName, Context.MODE_PRIVATE)
        urlInput.setText(prefs.getString(keyServerUrl, "ws://"))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001
                )
            }
        }

        startButton.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isBlank() || !(url.startsWith("ws://") || url.startsWith("wss://"))) {
                Toast.makeText(this, "Enter a valid ws:// or wss:// URL", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(keyServerUrl, url).apply()

            val serviceIntent = Intent(this, AlarmForegroundService::class.java).apply {
                putExtra(AlarmForegroundService.EXTRA_SERVER_URL, url)
            }
            ContextCompat.startForegroundService(this, serviceIntent)
            statusText.text = "Status: starting..."
        }

        stopButton.setOnClickListener {
            stopService(Intent(this, AlarmForegroundService::class.java))
            statusText.text = "Status: stopped"
        }

        batteryButton.setOnClickListener {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Already ignoring battery optimizations", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

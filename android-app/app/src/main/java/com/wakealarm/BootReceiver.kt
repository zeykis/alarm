package com.wakealarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

// Currently a no-op placeholder. If you want the service to auto-restart the
// WebSocket connection after the phone reboots, read the saved server URL
// from SharedPreferences ("wake_alarm_prefs" / "server_url") here and start
// AlarmForegroundService with it, the same way MainActivity does.
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Intentionally left blank for now — start the app manually after reboot.
    }
}

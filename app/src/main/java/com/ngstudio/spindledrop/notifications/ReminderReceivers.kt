package com.ngstudio.spindledrop.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.os.bundleOf
import com.ngstudio.spindledrop.SpindleApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Fires at 9:00 / 19:00: posts the reminder and re-arms the same slot for tomorrow. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DailyReminders.ACTION_FIRE) return
        val slot = intent.getStringExtra(DailyReminders.EXTRA_SLOT)
            ?.let { runCatching { DailyReminders.Slot.valueOf(it) }.getOrNull() } ?: return
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Always re-arm for tomorrow (reminders have no in-app off switch).
                DailyReminders.schedule(app, slot)
                // Don't interrupt someone who is already playing.
                if (Notifications.appInForeground || !Notifications.canPost(app)) return@launch
                DailyReminders.post(app, slot)
                (app as? SpindleApp)?.services?.telemetry
                    ?.logEvent("reminder_shown", bundleOf("slot" to slot.name.lowercase()))
            } finally {
                pending.finish()
            }
        }
    }
}

/** Alarms are wiped by a reboot and go stale after an update or a clock change, so re-arm them. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> Unit
            else -> return
        }
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DailyReminders.scheduleAll(app)
            } finally {
                pending.finish()
            }
        }
    }
}

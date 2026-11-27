package com.ngstudio.spindledrop.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Two local reminders a day, at 9:00 and 19:00 device time.
 *
 * Timing: below Android 12 we use exact alarms, which need no permission there. On Android 12+
 * exact alarms need the user-granted SCHEDULE_EXACT_ALARM permission, which this app doesn't
 * declare, so there we ask for a 10-minute window instead (on time unless the phone is in deep
 * Doze, which postpones it to the next maintenance window). Each alarm is one-shot and re-arms
 * itself for the next day when it fires; [ReminderBootReceiver] re-arms them after a reboot, an
 * app update or a clock/time-zone change, and the app re-arms them on every launch, so they
 * recover from a force-stop too.
 */
object DailyReminders {

    enum class Slot(val hour: Int, val requestCode: Int, val notificationId: Int) {
        MORNING(hour = 9, requestCode = 9001, notificationId = 9001),
        EVENING(hour = 19, requestCode = 9019, notificationId = 9019),
    }

    const val ACTION_FIRE = "com.ngstudio.spindledrop.action.DAILY_REMINDER"
    const val EXTRA_SLOT = "slot"
    private const val WINDOW_MS = 10 * 60 * 1000L

    private data class Message(val title: String, val body: String)

    private val morning = listOf(
        Message("Good morning! ☀️", "The towers are stacked and ready. Drop in and grab three stars before breakfast!"),
        Message("Rise and spin 🌀", "A new day, a new high score. Can you beat yesterday's run?"),
        Message("Morning warm-up 🎯", "One quick tower to wake up those reflexes. Ready, set, drop!"),
        Message("Your ball missed you! 🔴", "It's been bouncing around the menu all night. Take it for a spin."),
        Message("Start the day with a win 🏆", "Clear a level this morning and stack up coins for your next unlock."),
        Message("Fresh towers await ⚡", "New rings, new colours, same perfect smash. Let's go!"),
        Message("Coffee + combos ☕", "Line up a big smash chain before your coffee gets cold."),
    )

    private val evening = listOf(
        Message("Evening drop time 🌙", "Kick back, unwind and chase a few stars before the day ends."),
        Message("Beat your best before bed ⏱️", "Your record is begging to be broken tonight."),
        Message("One more tower? 🔻", "Just one. Okay, maybe five. Your ball is ready when you are."),
        Message("Coins are waiting 🪙", "Play a few levels tonight and treat your ball to a shiny new skin."),
        Message("Smash into the evening 💥", "Perfect drops, satisfying smashes, bonus coins. Let's go!"),
        Message("Golden hour on the tower 🌅", "The spiral is glowing. Time for one more run?"),
        Message("Unwind with a quick drop", "Five minutes, one tower, all the thrills. Your streak needs you!"),
    )

    /** Arm both reminders (replacing any already set). */
    fun scheduleAll(context: Context) = Slot.entries.forEach { schedule(context, it) }

    fun cancelAll(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        Slot.entries.forEach { am.cancel(pendingIntent(context, it)) }
    }

    /** Arm [slot] for its next occurrence: later today if still ahead, otherwise tomorrow. */
    fun schedule(context: Context, slot: Slot) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val at = nextTrigger(slot)
        val pi = pendingIntent(context, slot)
        val exactAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exactAllowed) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // A bounded window; a plain inexact alarm may be deferred by up to 75% of the time until it is due.
            am.setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, pi)
        }
    }

    fun post(context: Context, slot: Slot) {
        val msg = messageFor(slot, LocalDate.now())
        Notifications.show(
            context,
            id = slot.notificationId,
            channel = Notifications.CHANNEL_REMINDERS,
            title = msg.title,
            body = msg.body,
            source = "reminder_${slot.name.lowercase()}",
        )
    }

    internal fun nextTrigger(slot: Slot, now: ZonedDateTime = ZonedDateTime.now()): Long {
        var at = now.with(LocalTime.of(slot.hour, 0))
        // A minute of slack so re-arming right after the alarm fires lands on tomorrow, not now.
        if (!at.isAfter(now.plusMinutes(1))) at = at.plusDays(1)
        return at.toInstant().toEpochMilli()
    }

    /** Rotates by day of year, so the text changes daily and morning/evening never repeat each other. */
    private fun messageFor(slot: Slot, date: LocalDate): Message {
        val pool = if (slot == Slot.MORNING) morning else evening
        return pool[date.dayOfYear % pool.size]
    }

    private fun pendingIntent(context: Context, slot: Slot): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_FIRE)
            .putExtra(EXTRA_SLOT, slot.name)
        return PendingIntent.getBroadcast(
            context, slot.requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

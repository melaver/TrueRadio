package com.trueradio.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.trueradio.app.SecureSettings
import com.trueradio.app.service.RadioForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Starts the radio at a scheduled time so the listener wakes to a Morning mix and a news read.
 *
 * Uses AlarmManager rather than the service's own ticker because the service isn't running when
 * the alarm needs to fire - that's the entire point. Exact alarms are required: an inexact alarm
 * may be deferred by many minutes under Doze, which is unacceptable for something acting as an
 * alarm clock.
 */
class WakeAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Wake alarm fired - starting radio")

        val appContext = context.applicationContext
        // goAsync() would be cleaner for the DataStore read, but the client id is only needed to
        // pass through to the service, which re-reads settings itself anyway - so a fire-and-forget
        // launch is sufficient and avoids holding the broadcast open.
        CoroutineScope(Dispatchers.IO).launch {
            val settings = SecureSettings(appContext)
            val clientId = settings.snapshotSpotifyClientId()
            if (clientId.isBlank()) {
                Log.e(TAG, "Wake alarm: no Spotify client id configured")
                return@launch
            }
            val serviceIntent = Intent(appContext, RadioForegroundService::class.java).apply {
                action = RadioForegroundService.ACTION_START
                putExtra(RadioForegroundService.EXTRA_SPOTIFY_CLIENT_ID, clientId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(serviceIntent)
            } else {
                appContext.startService(serviceIntent)
            }

            // Alarms are one-shot; reschedule for the same time tomorrow so it behaves like a
            // daily alarm clock rather than firing once and silently never again.
            val minutes = settings.snapshotWakeAlarmMinutes()
            if (minutes != null) schedule(appContext, minutes)
        }
    }

    companion object {
        private const val TAG = "WakeAlarm"
        private const val REQUEST_CODE = 7701

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WakeAlarmReceiver::class.java)
            return PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Schedules the next firing at [minutesOfDay] past midnight, tomorrow if that time has
         * already passed today.
         *
         * Returns false when the OS won't permit exact alarms (Android 12+ requires the user to
         * grant "Alarms & reminders"), so the caller can tell the user rather than silently
         * scheduling nothing.
         */
        fun schedule(context: Context, minutesOfDay: Int): Boolean {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
                Log.w(TAG, "Exact alarms not permitted - user must grant Alarms & reminders")
                return false
            }

            val target = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, minutesOfDay / 60)
                set(Calendar.MINUTE, minutesOfDay % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
            }

            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                target.timeInMillis,
                pendingIntent(context)
            )
            Log.d(TAG, "Wake alarm scheduled for ${target.time}")
            return true
        }

        fun cancel(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarmManager.cancel(pendingIntent(context))
            Log.d(TAG, "Wake alarm cancelled")
        }
    }
}

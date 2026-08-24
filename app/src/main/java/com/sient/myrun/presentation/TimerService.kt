package com.sient.myrun.presentation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.sient.myrun.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that drives [TimerEngine] while a workout is active.
 * Keeps the timer ticking and buzzing even when the watch drops back to the
 * watch face, where the system would freeze a background app.
 */
class TimerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var tickJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyRun:interval-timer")
                .apply { setReferenceCounted(false) }
        }
        @Suppress("WakelockTimeout")
        wakeLock?.takeIf { !it.isHeld }?.acquire(MAX_WORKOUT_MS)

        if (tickJob?.isActive != true) {
            tickJob = scope.launch {
                while (isActive) {
                    if (TimerEngine.tick()) vibrateForPhase(TimerEngine.currentPhase)
                    delay(200)
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Interval timer", NotificationManager.IMPORTANCE_LOW)
        )

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Interval timer running")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)

        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_launcher_foreground)
            .setTouchIntent(openApp)
            .setStatus(Status.Builder().addTemplate("Interval timer").build())
            .build()
            .apply(this)

        return builder.build()
    }

    private fun vibrateForPhase(phase: Phase) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val effect = when (phase) {
            // Two short buzzes: time to run.
            Phase.RUN -> VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1)
            // One long buzz: time to walk.
            Phase.WALK -> VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    }

    companion object {
        private const val CHANNEL_ID = "interval_timer"
        private const val NOTIFICATION_ID = 1
        // Wake lock safety timeout: no morning run needs more than 4 hours.
        private const val MAX_WORKOUT_MS = 4 * 60 * 60 * 1000L
    }
}

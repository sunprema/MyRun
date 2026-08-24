package com.sient.myrun.presentation

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Phase { RUN, WALK }

class TimerViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("myrun_prefs", Context.MODE_PRIVATE)

    var runSeconds by mutableIntStateOf(prefs.getInt(KEY_RUN, 60))
        private set
    var walkSeconds by mutableIntStateOf(prefs.getInt(KEY_WALK, 180))
        private set

    var isRunning by mutableStateOf(false)
        private set
    var currentPhase by mutableStateOf(Phase.RUN)
        private set
    var timeLeft by mutableIntStateOf(runSeconds)
        private set
    var totalSeconds by mutableIntStateOf(0)
        private set

    private var job: Job? = null
    // Absolute deadline on the monotonic clock, so ticking never drifts.
    private var phaseEndElapsed = 0L
    // Workout time accumulated across pauses, plus the anchor of the current stretch.
    private var accumulatedMs = 0L
    private var sessionStartElapsed = 0L

    // Keeps the CPU ticking while the screen is in ambient mode; without it the
    // system freezes the app and the countdown stalls, then jumps on wake.
    private val wakeLock by lazy {
        val pm = application.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MyRun:interval-timer")
    }

    fun adjustRunSeconds(delta: Int) {
        runSeconds = (runSeconds + delta).coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        prefs.edit().putInt(KEY_RUN, runSeconds).apply()
        if (!isRunning && currentPhase == Phase.RUN) timeLeft = runSeconds
    }

    fun adjustWalkSeconds(delta: Int) {
        walkSeconds = (walkSeconds + delta).coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        prefs.edit().putInt(KEY_WALK, walkSeconds).apply()
        if (!isRunning && currentPhase == Phase.WALK) timeLeft = walkSeconds
    }

    fun toggle() = if (isRunning) pause() else start()

    fun start() {
        if (isRunning) return
        isRunning = true
        phaseEndElapsed = SystemClock.elapsedRealtime() + timeLeft * 1000L
        sessionStartElapsed = SystemClock.elapsedRealtime()
        @Suppress("WakelockTimeout")
        if (!wakeLock.isHeld) wakeLock.acquire(MAX_WORKOUT_MS)
        job = viewModelScope.launch {
            while (isActive) {
                // Advance as many phases as the clock says have elapsed (normally
                // one), but buzz only once so a stall can't fire a burst.
                var switched = false
                while (phaseEndElapsed <= SystemClock.elapsedRealtime()) {
                    currentPhase = if (currentPhase == Phase.RUN) Phase.WALK else Phase.RUN
                    val nextSeconds = if (currentPhase == Phase.RUN) runSeconds else walkSeconds
                    phaseEndElapsed += nextSeconds * 1000L
                    switched = true
                }
                if (switched) vibrateForPhase(currentPhase)
                timeLeft = (((phaseEndElapsed - SystemClock.elapsedRealtime()) + 999) / 1000)
                    .toInt().coerceAtLeast(0)
                totalSeconds = ((accumulatedMs +
                    (SystemClock.elapsedRealtime() - sessionStartElapsed)) / 1000).toInt()
                delay(200)
            }
        }
    }

    fun pause() {
        if (isRunning) {
            accumulatedMs += SystemClock.elapsedRealtime() - sessionStartElapsed
        }
        isRunning = false
        job?.cancel()
        job = null
        if (wakeLock.isHeld) wakeLock.release()
    }

    fun reset() {
        pause()
        currentPhase = Phase.RUN
        timeLeft = runSeconds
        accumulatedMs = 0L
        totalSeconds = 0
    }

    private fun vibrateForPhase(phase: Phase) {
        val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getApplication<Application>()
                .getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getApplication<Application>().getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val effect = when (phase) {
            // Two short buzzes: time to run.
            Phase.RUN -> VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1)
            // One long buzz: time to walk.
            Phase.WALK -> VibrationEffect.createOneShot(700, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    }

    override fun onCleared() {
        job?.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onCleared()
    }

    companion object {
        private const val KEY_RUN = "run_seconds"
        private const val KEY_WALK = "walk_seconds"
        private const val MIN_INTERVAL = 30
        private const val MAX_INTERVAL = 30 * 60
        // Wake lock safety timeout: no morning run needs more than 4 hours.
        private const val MAX_WORKOUT_MS = 4 * 60 * 60 * 1000L
    }
}

package com.sient.myrun.presentation

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Phase { RUN, WALK }

/**
 * Process-wide timer state, observed by the UI and driven by [TimerService].
 * Living outside any Activity/ViewModel lets the timer keep going when the
 * watch returns to the watch face.
 */
object TimerEngine {

    var runSeconds by mutableIntStateOf(60)
        private set
    var walkSeconds by mutableIntStateOf(180)
        private set
    var isRunning by mutableStateOf(false)
        private set
    var currentPhase by mutableStateOf(Phase.RUN)
        private set
    var timeLeft by mutableIntStateOf(60)
        private set
    var totalSeconds by mutableIntStateOf(0)
        private set

    // Absolute deadline on the monotonic clock, so ticking never drifts.
    private var phaseEndElapsed = 0L
    // Workout time accumulated across pauses, plus the anchor of the current stretch.
    private var accumulatedMs = 0L
    private var sessionStartElapsed = 0L
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = prefs(context)
        runSeconds = prefs.getInt(KEY_RUN, 60)
        walkSeconds = prefs.getInt(KEY_WALK, 180)
        if (!isRunning) timeLeft = if (currentPhase == Phase.RUN) runSeconds else walkSeconds
    }

    fun adjustRunSeconds(context: Context, delta: Int) {
        runSeconds = (runSeconds + delta).coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        prefs(context).edit().putInt(KEY_RUN, runSeconds).apply()
        if (!isRunning && currentPhase == Phase.RUN) timeLeft = runSeconds
    }

    fun adjustWalkSeconds(context: Context, delta: Int) {
        walkSeconds = (walkSeconds + delta).coerceIn(MIN_INTERVAL, MAX_INTERVAL)
        prefs(context).edit().putInt(KEY_WALK, walkSeconds).apply()
        if (!isRunning && currentPhase == Phase.WALK) timeLeft = walkSeconds
    }

    fun toggle(context: Context) = if (isRunning) pause(context) else start(context)

    fun start(context: Context) {
        if (isRunning) return
        isRunning = true
        val now = SystemClock.elapsedRealtime()
        phaseEndElapsed = now + timeLeft * 1000L
        sessionStartElapsed = now
        context.startForegroundService(Intent(context, TimerService::class.java))
    }

    fun pause(context: Context) {
        if (isRunning) {
            accumulatedMs += SystemClock.elapsedRealtime() - sessionStartElapsed
        }
        isRunning = false
        context.stopService(Intent(context, TimerService::class.java))
    }

    fun reset(context: Context) {
        pause(context)
        currentPhase = Phase.RUN
        timeLeft = runSeconds
        accumulatedMs = 0L
        totalSeconds = 0
    }

    /**
     * Advances state to the current clock time. Any number of missed phases is
     * caught up in one call; returns true if the phase switched so the caller
     * can buzz exactly once.
     */
    fun tick(): Boolean {
        if (!isRunning) return false
        var switched = false
        val now = SystemClock.elapsedRealtime()
        while (phaseEndElapsed <= now) {
            currentPhase = if (currentPhase == Phase.RUN) Phase.WALK else Phase.RUN
            val nextSeconds = if (currentPhase == Phase.RUN) runSeconds else walkSeconds
            phaseEndElapsed += nextSeconds * 1000L
            switched = true
        }
        timeLeft = (((phaseEndElapsed - now) + 999) / 1000).toInt().coerceAtLeast(0)
        totalSeconds = ((accumulatedMs + (now - sessionStartElapsed)) / 1000).toInt()
        return switched
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private const val PREFS_NAME = "myrun_prefs"
    private const val KEY_RUN = "run_seconds"
    private const val KEY_WALK = "walk_seconds"
    private const val MIN_INTERVAL = 30
    private const val MAX_INTERVAL = 30 * 60
}

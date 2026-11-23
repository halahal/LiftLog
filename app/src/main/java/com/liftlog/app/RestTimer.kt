package com.liftlog.app

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.annotation.MainThread
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.os.HandlerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** An elapsed-realtime deadline keeps ticking while the display is off.
 * Persists across process recreation. Wall time is used only after a device reboot.
 */
@MainThread
class RestTimer private constructor(context: Context, deliveredDeadline: Long) {
    companion object {
        @Volatile private var instance: RestTimer? = null

        fun get(context: Context, deliveredDeadline: Long = 0L): RestTimer = instance ?: synchronized(this) {
            instance ?: RestTimer(context.applicationContext, deliveredDeadline).also { instance = it }
        }

        fun canScheduleExactAlert(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("rest_timer", Context.MODE_PRIVATE)
    private val bootCount = runCatching {
        Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)
    private var deadline = prefs.getLong("elapsed_deadline", 0L)
    private var startElapsed = prefs.getLong("elapsed_start", 0L)
    private var duration = prefs.getLong("duration", 0L)
    private var label: String? = prefs.getString("label", null)
    private val mutableState = MutableStateFlow<RestState?>(null)
    val state = mutableState.asStateFlow()
    private val mutableRinging = MutableStateFlow<String?>(null)
    val ringing = mutableRinging.asStateFlow()
    private val serviceActive = MutableStateFlow(false)
    internal val hasService = serviceActive.asStateFlow()
    private val handler = HandlerCompat.createAsync(Looper.getMainLooper())
    private val alarmManager = app.getSystemService(AlarmManager::class.java)
    private val deadlineCallback = Runnable { expireIfDue() }
    private var armedDeadline = 0L
    private var deadlineAlarm: PendingIntent? = null

    internal fun setServiceActive(active: Boolean) {
        serviceActive.value = active
        if (active) armBackgroundDeadline() else cancelBackgroundDeadline()
    }

    /** Call from the visible activity to recover an unfinished countdown. */
    fun ensureAlertService(): Boolean {
        if (serviceActive.value) {
            armBackgroundDeadline()
            return true
        }
        if (deadline == 0L && mutableRinging.value == null) return false
        return RestAlertService.ensureRunning(app).also { started ->
            if (started) armBackgroundDeadline()
        }
    }

    init {
        val elapsed = SystemClock.elapsedRealtime()
        val savedBoot = prefs.getInt("boot_count", -1)
        val reboot = (savedBoot >= 0 && bootCount >= 0 && savedBoot != bootCount) ||
            elapsed < startElapsed
        if (deadline > 0 && reboot) {
            val wallRemaining = (prefs.getLong("wall_deadline", 0L) - System.currentTimeMillis())
                .coerceIn(0L, duration.coerceAtLeast(0L))
            if (wallRemaining > 0) {
                startElapsed = elapsed
                deadline = elapsed + wallRemaining
                persist()
            } else {
                skip()
            }
        }
        if (deadline > 0 && deadline <= elapsed && (reboot || deliveredDeadline != deadline)) skip()
        refresh()
    }

    fun start(seconds: Int, exerciseName: String) {
        if (mutableRinging.value != null || expireIfDue()) return
        if (seconds <= 0) {
            skip()
            return
        }
        startElapsed = SystemClock.elapsedRealtime()
        duration = seconds.coerceAtMost(3600) * 1_000L
        deadline = startElapsed + duration
        label = exerciseName
        persist()
        refresh()
        // Request a fresh start ID even if the last service is still completing shutdown.
        if (RestAlertService.ensureRunning(app) || serviceActive.value) armBackgroundDeadline()
    }

    fun adjust(seconds: Int) {
        val now = SystemClock.elapsedRealtime()
        if (deadline <= now) {
            expireIfDue()
            return
        }
        val newRemaining = (deadline - now + seconds * 1_000L).coerceIn(0L, 3_600_000L)
        deadline = now + newRemaining
        duration = (deadline - startElapsed).coerceAtLeast(1L)
        persist()
        refresh()
        if (armedDeadline != 0L || serviceActive.value) armBackgroundDeadline()
    }

    fun skip() {
        cancelBackgroundDeadline()
        deadline = 0L
        duration = 0L
        startElapsed = 0L
        label = null
        prefs.edit().remove("elapsed_deadline").remove("elapsed_start").remove("duration")
            .remove("label").remove("boot_count").remove("wall_deadline").apply()
        mutableState.value = null
    }

    private fun armBackgroundDeadline() {
        if (deadline == 0L) return
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0L) {
            expireIfDue()
            return
        }
        if (armedDeadline != deadline) {
            cancelBackgroundDeadline()
            armedDeadline = deadline
            // The callback belongs to the active timer and does not depend on the Activity lifecycle.
            handler.postDelayed(deadlineCallback, remaining)
        }
        val manager = alarmManager ?: return
        if (deadlineAlarm != null || !canScheduleExactAlert(app)) return
        val pending = RestAlertService.deadlineIntent(app, deadline)
        try {
            // A PendingIntent can reach the service even if the app's own callbacks were frozen.
            manager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, deadline, pending)
            deadlineAlarm = pending
        } catch (_: SecurityException) {
            pending.cancel() // Access may have been revoked after the check.
        }
    }

    private fun cancelBackgroundDeadline() {
        if (armedDeadline == 0L) return
        armedDeadline = 0L
        handler.removeCallbacks(deadlineCallback)
        deadlineAlarm?.let { pending ->
            deadlineAlarm = null
            alarmManager?.cancel(pending)
            pending.cancel()
        }
    }

    private fun persist() {
        prefs.edit().putLong("elapsed_deadline", deadline)
            .putLong("elapsed_start", startElapsed).putLong("duration", duration)
            .putString("label", label).putInt("boot_count", bootCount)
            .putLong("wall_deadline", System.currentTimeMillis() + deadline - SystemClock.elapsedRealtime())
            .apply()
    }

    internal fun refresh() {
        if (deadline == 0L) return
        val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        mutableState.value = RestState(
            seconds = ((remaining + 999L) / 1000L).toInt(),
            fraction = (remaining.toFloat() / duration.coerceAtLeast(1L)).coerceIn(0f, 1f),
            label = label.orEmpty(),
            deadlineElapsed = deadline,
        )
    }

    /** Emits at most one alert per countdown. Ringing is deliberately process-local. */
    internal fun expireIfDue(): Boolean {
        if (deadline == 0L || SystemClock.elapsedRealtime() < deadline) return false
        if (mutableRinging.value == null) mutableRinging.value = label ?: "组间休息"
        skip() // Commit away the elapsed countdown; preserve the currently ringing alert.
        return true
    }

    fun stopAlert() {
        mutableRinging.value = null
    }
}

data class RestState(val seconds: Int, val fraction: Float, val label: String, val deadlineElapsed: Long)

private enum class FallbackMode { Idle, Counting, Ringing }

/** Refreshes the visible countdown and plays the local fallback if the service is unavailable. */
@Composable
fun RestTimerEffect(timer: RestTimer, soundController: RestSoundController) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val power = remember(context) { context.getSystemService(PowerManager::class.java) }
    val keyguard = remember(context) { context.getSystemService(KeyguardManager::class.java) }
    LaunchedEffect(timer, soundController, owner, power, keyguard) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            combine(
                timer.state.map { it != null }.distinctUntilChanged(),
                timer.ringing,
                timer.hasService,
            ) { active, ringing, serviced ->
                when {
                    ringing != null -> if (serviced) FallbackMode.Idle else FallbackMode.Ringing
                    active -> FallbackMode.Counting
                    else -> FallbackMode.Idle
                }
            }.distinctUntilChanged().collectLatest { mode ->
                when (mode) {
                    FallbackMode.Idle -> Unit
                    FallbackMode.Ringing -> {
                        if (!timer.hasService.value && power?.isInteractive == true && keyguard?.isKeyguardLocked == false) {
                            soundController.playLoop()
                            try { awaitCancellation() } finally { soundController.stopLoop() }
                        }
                    }
                    FallbackMode.Counting -> {
                        while (timer.state.value != null) {
                            if (timer.expireIfDue()) break
                            timer.refresh()
                            delay(200L)
                        }
                    }
                }
            }
        }
    }
}

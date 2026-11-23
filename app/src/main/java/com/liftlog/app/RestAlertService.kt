package com.liftlog.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Runs only for a rest interval that the user started in the visible app.
 * Finishing a pending interval makes the separately stoppable notification and saved sound
 * available while the user is in another app. Force-stop and reboot cancel the system alarm.
 */
class RestAlertService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val timer by lazy { RestTimer.get(applicationContext) }
    private val manager by lazy { getSystemService(NotificationManager::class.java) }
    private var promoted = false
    private var mostRecentStartId = 0
    private var countdownLabel: String? = null
    private var countdownDeadline = 0L
    private var alertToken = 0L
    private var alertNoticePosted = false
    private val overlay = lazy { RestAlertOverlay(this, ::syncAlertSurfaces) }
    private var sound: RestSoundController? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        mostRecentStartId = startId
        if (intent?.action == ACTION_STOP) {
            if (alertToken != 0L && intent.getLongExtra(EXTRA_TOKEN, 0L) == alertToken) {
                stopCurrentAlert(alertToken)
            } else if (!promoted) {
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        val fromAlarm = intent?.action == ACTION_DEADLINE
        if (fromAlarm) {
            val deliveredDeadline = intent.getLongExtra(EXTRA_DEADLINE, 0L)
            // Preserve an expired deadline on process recreation only for its matching system alarm.
            val currentTimer = RestTimer.get(applicationContext, deliveredDeadline)
            if (deliveredDeadline == 0L || currentTimer.state.value?.deadlineElapsed != deliveredDeadline) {
                if (!promoted) stopSelfResult(startId)
                return START_NOT_STICKY
            }
        }
        if (!promoted) {
            try {
                createChannels(this)
                ServiceCompat.startForeground(
                    this,
                    TIMER_NOTIFICATION_ID,
                    countdownNotification(timer.state.value),
                    if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
                )
            } catch (e: RuntimeException) {
                // The in-app timer still owns the deadline and presents the local fallback.
                Log.w(TAG, "Could not promote rest timer foreground service", e)
                timer.setServiceActive(false)
                stopSelfResult(startId)
                return START_NOT_STICKY
            }
            promoted = true
            timer.setServiceActive(true)
            overlay.value.observe()
            startObserving()
        }
        if (fromAlarm) timer.expireIfDue()
        return START_NOT_STICKY
    }

    private fun startObserving() {
        scope.launch {
            // The timer publishes active/ringing separately, but mutates both on main.
            // Re-read both sources here rather than interpreting a transient combine pair.
            combine(timer.state, timer.ringing, RestAlertOverlay.activityInForeground) { _, _, _ -> Unit }.collect {
                val ringing = timer.ringing.value
                val pending = timer.state.value
                when {
                    ringing != null -> syncAlertSurfaces()
                    pending != null -> {
                        dismissAlert()
                        updateCountdown(pending)
                    }
                    else -> {
                        dismissAlert()
                        stopSelfResult(mostRecentStartId)
                    }
                }
            }
        }
    }

    private fun updateCountdown(pending: RestState) {
        if (countdownLabel == pending.label && countdownDeadline == pending.deadlineElapsed) return
        countdownLabel = pending.label
        countdownDeadline = pending.deadlineElapsed
        // Failure to update notification styling must not kill the pending interval.
        notifySafely(TIMER_NOTIFICATION_ID, countdownNotification(pending))
    }

    private fun syncAlertSurfaces() {
        if (!promoted) return
        val label = timer.ringing.value ?: return
        if (alertToken == 0L) {
            alertToken = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
            countdownLabel = null
            countdownDeadline = 0L
        }
        val token = alertToken
        val canOverlay = overlay.value.show(label) { stopCurrentAlert(token) }
        if (canPostAlert(this)) {
            if (!alertNoticePosted) alertNoticePosted = postFinishedNotification(label, token)
        } else if (alertNoticePosted) {
            manager.cancel(ALERT_NOTIFICATION_ID)
            alertNoticePosted = false
        }
        val canSeeDialog = RestAlertOverlay.activityInForeground.value && RestAlertOverlay.deviceCanDisplay(this)
        if (canOverlay || alertNoticePosted || canSeeDialog) {
            // Read committed settings at expiry; notification is not the source of the sound.
            if (sound == null) sound = RestSoundController(applicationContext).also { player ->
                if (!player.playLoop()) Log.w(TAG, "Could not play saved rest sound")
            }
        } else {
            // Overlay-only mode can temporarily lose its Stop control (lock screen, revocation,
            // or device rejection). Suspend sound and restore the control when again eligible.
            sound?.close()
            sound = null
            if (!RestAlertOverlay.hasPermission(this) && !canPostAlert(this)) {
                stopSelfResult(mostRecentStartId)
            }
        }
    }

    private fun stopCurrentAlert(token: Long) {
        if (alertToken != token || timer.ringing.value == null) return
        timer.stopAlert()
        dismissAlert()
        if (timer.state.value == null) stopSelfResult(mostRecentStartId)
    }

    private fun postFinishedNotification(label: String, token: Long): Boolean {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, RestAlertService::class.java).apply {
                action = ACTION_STOP
                data = Uri.parse("liftlog://rest-alert/stop/$token")
                putExtra(EXTRA_TOKEN, token)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = if (label.isBlank()) "点击停止，或打开练迹。" else "$label · 点击停止，或打开练迹。"
        val finished = baseNotification(ALERT_CHANNEL_ID)
            .setContentTitle("休息结束")
            .setContentText(body)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setDeleteIntent(stop)
            .addAction(R.drawable.ic_rest_notification, "停止提示音", stop)
            .build()
        if (!notifySafely(ALERT_NOTIFICATION_ID, finished)) return false
        // Keep a Stop button in the notification shade even if the heads-up card is dismissed.
        notifySafely(
            TIMER_NOTIFICATION_ID,
            baseNotification(COUNTDOWN_CHANNEL_ID)
                .setContentTitle("休息结束")
                .setContentText(body)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setDeleteIntent(stop)
                .addAction(R.drawable.ic_rest_notification, "停止提示音", stop)
                .build(),
        )
        return true
    }

    private fun dismissAlert() {
        if (overlay.isInitialized()) overlay.value.hide()
        if (alertToken == 0L && sound == null) return
        alertToken = 0L
        sound?.close()
        sound = null
        if (alertNoticePosted) {
            manager.cancel(ALERT_NOTIFICATION_ID)
            alertNoticePosted = false
        }
    }

    private fun countdownNotification(pending: RestState?): Notification = baseNotification(COUNTDOWN_CHANNEL_ID)
        .setContentTitle("组间休息中")
        .setContentText(pending?.label?.takeUnless(String::isBlank) ?: "练迹正在计时")
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .apply {
            if (pending != null) {
                val remaining = (pending.deadlineElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                setWhen(System.currentTimeMillis() + remaining)
                setShowWhen(true)
                setUsesChronometer(true)
                setChronometerCountDown(true)
            }
        }
        .build()

    private fun baseNotification(channel: String): NotificationCompat.Builder =
        NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_rest_notification)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    },
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )

    private fun notifySafely(id: Int, notification: Notification): Boolean {
        if (!canPostNotifications(this)) return false
        return try {
            manager.notify(id, notification)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not publish rest timer notification", e)
            false
        }
    }

    override fun onDestroy() {
        scope.cancel()
        dismissAlert()
        if (overlay.isInitialized()) overlay.value.close()
        if (promoted) {
            timer.setServiceActive(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        super.onDestroy()
    }

    companion object {
        const val ALERT_CHANNEL_ID = "rest_finished_popup"
        const val COUNTDOWN_CHANNEL_ID = "rest_countdown"
        private const val TAG = "RestAlertService"
        private const val TIMER_NOTIFICATION_ID = 401
        private const val ALERT_NOTIFICATION_ID = 402
        private const val ACTION_START = "com.liftlog.app.rest.START"
        private const val ACTION_STOP = "com.liftlog.app.rest.STOP"
        private const val ACTION_DEADLINE = "com.liftlog.app.rest.DEADLINE"
        private const val EXTRA_TOKEN = "alert_token"
        private const val EXTRA_DEADLINE = "elapsed_deadline"

        internal fun deadlineIntent(context: Context, deadline: Long): PendingIntent =
            PendingIntent.getForegroundService(
                context, 0,
                Intent(context, RestAlertService::class.java).apply {
                    action = ACTION_DEADLINE
                    data = Uri.parse("liftlog://rest-alert/deadline/$deadline")
                    putExtra(EXTRA_DEADLINE, deadline)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        @MainThread
        fun ensureRunning(context: Context): Boolean {
            val app = context.applicationContext
            return try {
                if (!canPostAlert(app) && !RestAlertOverlay.hasPermission(app)) return false
                createChannels(app)
                ContextCompat.startForegroundService(
                    app,
                    Intent(app, RestAlertService::class.java).apply { action = ACTION_START },
                )
                true
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not start background rest timer", e)
                false
            }
        }

        /** Whether a heads-up alert is allowed by these notification settings, not guaranteed. */
        fun canShowHeadsUp(context: Context): Boolean {
            val importance = context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(ALERT_CHANNEL_ID)?.importance
            return canPostNotifications(context) &&
                (importance == null || importance >= NotificationManager.IMPORTANCE_HIGH)
        }

        fun hasAlertChannel(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(ALERT_CHANNEL_ID) != null

        internal fun createChannels(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannels(
                listOf(
                    NotificationChannel(
                        COUNTDOWN_CHANNEL_ID, "组间休息倒计时", NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "显示进行中的组间休息"
                        setSound(null, null)
                        setShowBadge(false)
                    },
                    NotificationChannel(
                        ALERT_CHANNEL_ID, "休息结束弹窗", NotificationManager.IMPORTANCE_HIGH,
                    ).apply {
                        description = "休息结束时弹出提醒，可直接停止应用内铃声"
                        setSound(null, null) // The timed sound comes from the user's saved in-app settings.
                        enableVibration(true)
                        vibrationPattern = longArrayOf(0, 220, 90, 220)
                        lockscreenVisibility = Notification.VISIBILITY_PRIVATE
                        setShowBadge(false)
                    },
                ),
            )
        }

        private fun canPostAlert(context: Context): Boolean =
            canPostNotifications(context) && context.getSystemService(NotificationManager::class.java)
                .getNotificationChannel(ALERT_CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE

        private fun canPostNotifications(context: Context): Boolean =
            (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) &&
                context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
    }
}

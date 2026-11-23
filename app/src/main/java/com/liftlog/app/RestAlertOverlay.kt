package com.liftlog.app

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** An opt-in, compact stop control; never opens an activity, grabs focus or covers the screen. */
@MainThread
internal class RestAlertOverlay(
    private val serviceContext: Context,
    private val onEligibilityChanged: () -> Unit,
) {
    private val app = serviceContext.applicationContext
    private val ops = app.getSystemService(AppOpsManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = Runnable { if (observing) onEligibilityChanged() }
    private val retry = Runnable { if (observing) onEligibilityChanged() }
    private var attachedCard: AttachedCard? = null
    private var retriesRemaining = ATTACH_RETRIES
    private var watching = false
    private var registered = false
    private var observing = false
    private val listener = AppOpsManager.OnOpChangedListener { _, pkg ->
        if (pkg == app.packageName) handler.post(refresh)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (observing) onEligibilityChanged()
        }
    }

    fun observe() {
        if (observing) return
        observing = true
        watching = runCatching {
            ops?.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, app.packageName, listener)
        }.isSuccess && ops != null
        registered = runCatching {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
                    addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
                }
            }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.isSuccess
    }

    fun show(label: String, onStop: () -> Unit): Boolean {
        if (activityInForeground.value || !hasPermission(app) || !deviceCanDisplay(app)) {
            hide()
            return false
        }
        if (attachedCard?.view?.parent != null) return true
        attachedCard = null
        handler.removeCallbacks(retry)
        return try {
            val context = createOverlayContext()
            val windowManager = checkNotNull(context.getSystemService(WindowManager::class.java))
            val card = card(context, label, onStop)
            val width = (context.resources.displayMetrics.widthPixels - context.dp(24))
                .coerceIn(1, context.dp(400))
            val params = WindowManager.LayoutParams(
                width,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = context.dp(12)
                title = "练迹 · 休息结束"
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
                }
            }
            windowManager.addView(card, params)
            attachedCard = AttachedCard(windowManager, card)
            retriesRemaining = ATTACH_RETRIES
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not show rest stop control", e)
            if (retriesRemaining > 0) {
                retriesRemaining--
                handler.postDelayed(retry, RETRY_DELAY_MS)
            }
            false
        }
    }

    private fun createOverlayContext(): Context {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return serviceContext
        val display = checkNotNull(app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY))
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            serviceContext.createWindowContext(display, type, null)
        } else {
            serviceContext.createDisplayContext(display).createWindowContext(type, null)
        }
    }

    fun hide() {
        handler.removeCallbacks(retry)
        retriesRemaining = ATTACH_RETRIES
        val shown = attachedCard ?: return
        attachedCard = null
        runCatching { shown.windowManager.removeViewImmediate(shown.view) }
    }

    fun close() {
        observing = false
        hide()
        if (registered) runCatching { app.unregisterReceiver(receiver) }
        if (watching) runCatching { ops?.stopWatchingMode(listener) }
        registered = false
        watching = false
        handler.removeCallbacks(refresh)
    }

    private fun card(context: Context, label: String, onStop: () -> Unit): View = with(context) {
        val darkGreen = Color.rgb(24, 54, 40)
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = rounded(Color.rgb(245, 250, 242), 18).apply {
                setStroke(dp(1), Color.rgb(173, 195, 181))
            }
            elevation = dp(7).toFloat()
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_rest_notification)
                setColorFilter(darkGreen)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                background = rounded(Color.rgb(216, 233, 222), 12)
            }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(9) })
            val message = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = "休息结束"
                    textSize = 18f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(darkGreen)
                })
                addView(TextView(context).apply {
                    text = context.getString(
                        R.string.rest_overlay_summary,
                        label.ifBlank { context.getString(R.string.rest_overlay_next_set) },
                    )
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    textSize = 12f
                    setTextColor(Color.rgb(52, 83, 67))
                })
            }
            val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
            addView(message, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginEnd = dp(8) })
            addView(Button(context).apply {
                text = "停止提示音"
                isAllCaps = false
                textSize = 14f
                setTextColor(Color.WHITE)
                backgroundTintList = null
                background = rounded(Color.rgb(31, 89, 63), 12)
                setPadding(dp(12), dp(4), dp(12), dp(4))
                minHeight = dp(48)
                minimumHeight = dp(48)
                setOnClickListener { onStop() }
            }, LinearLayout.LayoutParams(wrap, wrap))
        }
    }

    private fun Context.rounded(fill: Int, radius: Int) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
    }

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()

    private class AttachedCard(val windowManager: WindowManager, val view: View)

    companion object {
        private const val TAG = "RestAlertOverlay"
        private const val ATTACH_RETRIES = 3
        private const val RETRY_DELAY_MS = 450L
        private val mutableActivityInForeground = MutableStateFlow(false)
        internal val activityInForeground = mutableActivityInForeground.asStateFlow()

        fun hasPermission(context: Context): Boolean =
            runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

        @MainThread
        fun setActivityInForeground(inForeground: Boolean) {
            mutableActivityInForeground.value = inForeground
        }

        internal fun deviceCanDisplay(context: Context): Boolean =
            context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
                context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false
    }
}

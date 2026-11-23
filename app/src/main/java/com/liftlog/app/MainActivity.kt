package com.liftlog.app

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.lifecycle.Lifecycle
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private val repository by lazy { WorkoutRepository(applicationContext) }
    private val timer by lazy { RestTimer.get(applicationContext) }
    private val restSound = lazy { RestSoundController(applicationContext) }

    private var canShowRestNotice by mutableStateOf(false)
    private var canOverlayRestNotice by mutableStateOf(false)
    private var canScheduleExactRest by mutableStateOf(false)
    private var alarmIsMuted by mutableStateOf(false)
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refreshRestNotice()
        if (granted && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) timer.ensureAlertService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LiftLogTheme {
                LiftLogApp(
                    repository, timer, restSound.value,
                    canShowRestNotice = canShowRestNotice,
                    canOverlayRestNotice = canOverlayRestNotice,
                    canScheduleExactRest = canScheduleExactRest,
                    alarmIsMuted = alarmIsMuted,
                    onEnableRestNotice = ::enableRestNotice,
                    onManageOverlay = ::manageRestOverlay,
                    onManageExactAlarm = ::manageExactRestAlarm,
                    onOpenSoundSettings = ::openSoundSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            RestAlertOverlay.setActivityInForeground(true)
        }
        refreshRestNotice()
        timer.ensureAlertService()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        RestAlertOverlay.setActivityInForeground(isTopResumedActivity)
    }

    private fun refreshRestNotice() {
        canShowRestNotice = RestAlertService.canShowHeadsUp(this)
        canOverlayRestNotice = RestAlertOverlay.hasPermission(this)
        canScheduleExactRest = RestTimer.canScheduleExactAlert(this)
        alarmIsMuted = getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_ALARM) == 0
    }

    private fun manageRestOverlay() {
        openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", packageName, null)))
    }

    private fun manageExactRestAlarm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun openSoundSettings() = openSettings(Intent(Settings.ACTION_SOUND_SETTINGS))

    private fun openSettings(destination: Intent) {
        try {
            startActivity(destination)
        } catch (_: android.content.ActivityNotFoundException) {
            val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            runCatching { startActivity(details) }
        }
    }

    private fun enableRestNotice() {
        val prefs = getSharedPreferences("rest_notice_access", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (!prefs.getBoolean("requested", false) ||
                shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
        ) {
            prefs.edit().putBoolean("requested", true).apply()
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val manager = getSystemService(NotificationManager::class.java)
        val hasChannel = manager?.areNotificationsEnabled() == true &&
            manager.getNotificationChannel(RestAlertService.ALERT_CHANNEL_ID) != null
        val destination = if (hasChannel) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_CHANNEL_ID, RestAlertService.ALERT_CHANNEL_ID)
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }
        destination.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        openSettings(destination)
    }

    override fun onPause() {
        RestAlertOverlay.setActivityInForeground(false)
        if (restSound.isInitialized()) restSound.value.stopPreview()
        super.onPause()
    }

    override fun onDestroy() {
        if (restSound.isInitialized()) restSound.value.close()
        super.onDestroy()
    }
}

private enum class LiftTab(val label: String) {
    Training("训练"), Plan("周计划"), Calendar("日历"), Stats("统计")
}

private val daylight = lightColorScheme(
    primary = Color(0xFF225C40),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD8F1DD),
    onPrimaryContainer = Color(0xFF123B29),
    secondary = Color(0xFF466B4A),
    secondaryContainer = Color(0xFFE3EEDF),
    onSecondaryContainer = Color(0xFF213E25),
    tertiary = Color(0xFF795B24),
    tertiaryContainer = Color(0xFFFCEBCD),
    onTertiaryContainer = Color(0xFF5A4011),
    background = Color(0xFFF6F8F3),
    onBackground = Color(0xFF19271E),
    surface = Color(0xFFFCFDF9),
    onSurface = Color(0xFF19271E),
    surfaceVariant = Color(0xFFE6ECE3),
    onSurfaceVariant = Color(0xFF4B5B4F),
    surfaceContainer = Color(0xFFECF1E9),
    surfaceContainerLow = Color(0xFFF0F4ED),
    surfaceContainerHigh = Color(0xFFE5ECE1),
    outline = Color(0xFF718073),
    outlineVariant = Color(0xFFCBD6CB),
)

private val night = darkColorScheme(
    primary = Color(0xFFA6DDB6),
    onPrimary = Color(0xFF133725),
    primaryContainer = Color(0xFF224E36),
    onPrimaryContainer = Color(0xFFC5EBD0),
    secondary = Color(0xFFB5D1AF),
    secondaryContainer = Color(0xFF2C4730),
    onSecondaryContainer = Color(0xFFD0E9C9),
    tertiary = Color(0xFFF0CF88),
    tertiaryContainer = Color(0xFF514019),
    onTertiaryContainer = Color(0xFFF7E3B2),
    background = Color(0xFF121A14),
    onBackground = Color(0xFFE3EDE4),
    surface = Color(0xFF17211A),
    onSurface = Color(0xFFE5EEE5),
    surfaceVariant = Color(0xFF2F4033),
    onSurfaceVariant = Color(0xFFBECEC0),
    surfaceContainer = Color(0xFF202C23),
    surfaceContainerLow = Color(0xFF1A251D),
    surfaceContainerHigh = Color(0xFF2A392E),
    outline = Color(0xFF879B8C),
    outlineVariant = Color(0xFF3F5043),
)

@Composable
private fun LiftLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) night else daylight, content = content)
}

@Composable
private fun LiftLogApp(
    repo: WorkoutRepository,
    timer: RestTimer,
    restSound: RestSoundController,
    canShowRestNotice: Boolean,
    canOverlayRestNotice: Boolean,
    canScheduleExactRest: Boolean,
    alarmIsMuted: Boolean,
    onEnableRestNotice: () -> Unit,
    onManageOverlay: () -> Unit,
    onManageExactAlarm: () -> Unit,
    onOpenSoundSettings: () -> Unit,
) {
    var tabName by rememberSaveable { mutableStateOf(LiftTab.Training.name) }
    var pickedDate by rememberSaveable { mutableStateOf<String?>(null) }
    val tab = LiftTab.valueOf(tabName)
    var today by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    LaunchedEffect(Unit) {
        while (true) {
            today = LocalDate.now().toString()
            delay(15_000)
        }
    }
    val stateHolder = rememberSaveableStateHolder()
    var showRestSound by rememberSaveable { mutableStateOf(false) }
    var showRestSetup by rememberSaveable { mutableStateOf(false) }
    RestTimerEffect(timer, restSound)
    val restFinished by timer.ringing.collectAsState()
    LaunchedEffect(restFinished) {
        if (restFinished != null) restSound.stopPreview()
    }
    BackHandler(enabled = tab != LiftTab.Training || pickedDate != null) {
        if (tab == LiftTab.Training && pickedDate != null) {
            pickedDate = null
            tabName = LiftTab.Calendar.name
        } else {
            pickedDate = null
            tabName = LiftTab.Training.name
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                LiftTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = {
                            pickedDate = null
                            tabName = item.name
                        },
                        icon = { TabGlyph(item, tab == item) },
                        label = { Text(item.label, maxLines = 1) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            selectedTextColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            }
        }
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets)) {
            BrandHeader(repo, timer)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                stateHolder.SaveableStateProvider(tab.name) {
                    when (tab) {
                        LiftTab.Training -> WorkoutScreen(
                            date = LocalDate.parse(pickedDate ?: today),
                            repository = repo,
                            timer = timer,
                            modifier = Modifier.fillMaxSize(),
                            onOpenPlan = { tabName = LiftTab.Plan.name },
                            onOpenRestSound = { showRestSound = true },
                            onOpenRestSetup = { showRestSetup = true },
                        )
                        LiftTab.Plan -> PlanScreen(repo)
                        LiftTab.Calendar -> CalendarScreen(repo) {
                            pickedDate = it.toString()
                            tabName = LiftTab.Training.name
                        }
                        LiftTab.Stats -> StatsScreen(repo)
                    }
                }
            }
        }
    }
    if (showRestSound) {
        RestSoundDialog(restSound, onDismiss = { showRestSound = false })
    }
    if (showRestSetup) {
        val countdown by timer.state.collectAsState()
        RestSetupDialog(
            notifications = canShowRestNotice,
            overlay = canOverlayRestNotice,
            exactAlarm = canScheduleExactRest,
            alarmIsMuted = alarmIsMuted,
            busy = countdown != null || restFinished != null,
            onNotifications = onEnableRestNotice,
            onOverlay = onManageOverlay,
            onExactAlarm = onManageExactAlarm,
            onSystemVolume = onOpenSoundSettings,
            onTest = {
                if (timer.state.value == null && timer.ringing.value == null) {
                    showRestSetup = false
                    timer.start(10, "提醒测试 · 不计入训练")
                }
            },
            onDismiss = { showRestSetup = false },
        )
    }
    restFinished?.let { exercise ->
        AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text("休息结束") },
            text = { Text(if (exercise.isBlank()) "可以开始下一组了。" else "「$exercise」可以开始下一组了。") },
            confirmButton = { Button(onClick = timer::stopAlert) { Text("停止提示音") } },
        )
    }
}

@Composable
private fun RestSetupDialog(
    notifications: Boolean,
    overlay: Boolean,
    exactAlarm: Boolean,
    alarmIsMuted: Boolean,
    busy: Boolean,
    onNotifications: () -> Unit,
    onOverlay: () -> Unit,
    onExactAlarm: () -> Unit,
    onSystemVolume: () -> Unit,
    onTest: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("后台提醒") },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                Text("允许悬浮窗后，切到其他应用也能在提醒卡片上停止提示音。")
                Text(
                    if (overlay) "悬浮窗：已允许" else "悬浮窗：未允许",
                    style = MaterialTheme.typography.labelLarge
                )
                OutlinedButton(onClick = onOverlay, modifier = Modifier.fillMaxWidth()) {
                    Text("设置「在其他应用上显示」")
                }
                if (!overlay) {
                    Text(
                        "如果系统显示应用列表，请选择「练迹」，开启「允许在其他应用上显示」。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    if (notifications) "通知：已允许重要提醒" else "通知：横幅可能未开启",
                    style = MaterialTheme.typography.labelLarge
                )
                OutlinedButton(onClick = onNotifications, modifier = Modifier.fillMaxWidth()) {
                    Text("开启或检查通知")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Text(
                        if (exactAlarm) "准时后台提醒：已允许"
                        else "开启「闹钟和提醒」，让系统在练迹被暂停时也能投递到期提醒。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (!exactAlarm) {
                        OutlinedButton(onClick = onExactAlarm, modifier = Modifier.fillMaxWidth()) {
                            Text("开启「闹钟和提醒」")
                        }
                    }
                }
                Text(
                    if (alarmIsMuted) "手机闹钟音量当前为 0。" else "声音使用手机的闹钟音量，而不是视频的媒体音量。",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (alarmIsMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onSystemVolume) { Text("检查手机闹钟音量") }
                Text(
                    if (busy) "当前正在休息或提醒；结束后再测试。" else "点测试后切到视频，10 秒后查看提醒。测试不写入训练记录。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        confirmButton = { Button(onClick = onTest, enabled = !busy) { Text("测试 10 秒") } },
    )
}

@Composable
private fun BrandHeader(repo: WorkoutRepository, timer: RestTimer) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            Modifier.size(32.dp).background(Color(0xFF153528), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.width(15.dp).height(18.dp)) {
                val w = size.width
                val h = size.height
                drawPath(Path().apply {
                    moveTo(w * .47f, 0f)
                    lineTo(0f, h * .55f)
                    lineTo(w * .38f, h * .55f)
                    lineTo(w * .30f, h)
                    lineTo(w, h * .36f)
                    lineTo(w * .59f, h * .36f)
                    lineTo(w * .71f, 0f)
                    close()
                }, Color(0xFFCAFE75))
            }
        }
        Text("练迹", Modifier.semantics { heading() }, fontWeight = FontWeight.Bold, fontSize = 19.sp)
        Box(Modifier.weight(1f))
        BackupMenu(repo, afterRestore = {
            timer.skip()
            timer.stopAlert()
        })
    }
}

@Composable
private fun TabGlyph(tab: LiftTab, selected: Boolean) {
    val tint by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "navigation tint"
    )
    Canvas(Modifier.size(23.dp)) {
        val d = size.width / 24f
        fun line(x: Float, y: Float, a: Float, b: Float) =
            drawLine(tint, Offset(x * d, y * d), Offset(a * d, b * d), 2f * d, StrokeCap.Round)
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 2f) =
            drawRoundRect(tint, Offset(x * d, y * d), Size(w * d, h * d), CornerRadius(r * d), style = Stroke(1.7f * d))
        when (tab) {
            LiftTab.Training -> {
                line(2f, 12f, 22f, 12f)
                rect(4f, 7f, 4f, 10f, 1f)
                rect(16f, 7f, 4f, 10f, 1f)
            }
            LiftTab.Plan -> {
                rect(5f, 4f, 14f, 16f)
                line(8f, 8f, 16f, 8f)
                line(8f, 12f, 16f, 12f)
                line(8f, 16f, 13f, 16f)
            }
            LiftTab.Calendar -> {
                rect(3f, 5f, 18f, 16f)
                line(7f, 3f, 7f, 7f)
                line(17f, 3f, 17f, 7f)
                line(4f, 10f, 20f, 10f)
                drawCircle(tint, d, Offset(10f * d, 15f * d))
                drawCircle(tint, d, Offset(15f * d, 15f * d))
            }
            LiftTab.Stats -> {
                line(4f, 3f, 4f, 20f)
                line(4f, 20f, 21f, 20f)
                line(8f, 16f, 8f, 12f)
                line(13f, 16f, 13f, 9f)
                line(18f, 16f, 18f, 5f)
            }
        }
    }
}

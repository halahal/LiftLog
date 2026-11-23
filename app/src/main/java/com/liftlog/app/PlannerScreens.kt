package com.liftlog.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val WeekdayNames = listOf("一", "二", "三", "四", "五", "六", "日")
private val PlannerGap = 14.dp

@Composable
private fun PlannerLoading() {
    Box(Modifier.fillMaxSize().semantics { contentDescription = "正在载入训练数据" }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun PlannerColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(PlannerGap),
        content = content
    )
}

@Composable
private fun PlannerIntro(title: String, summary: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlannerPanel(content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
private fun PlannerEmpty(title: String, summary: String) {
    PlannerPanel {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PlannerHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
}

private fun displayDate(date: LocalDate): String = "${date.monthValue} 月 ${date.dayOfMonth} 日 · 周${WeekdayNames[date.dayOfWeek.value - 1]}"
private fun displayWeight(value: Double): String = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

@Composable
private fun ExerciseForm(
    initialName: String = "",
    initialWeight: Double = 0.0,
    initialSets: Int = 3,
    initialReps: Int = 10,
    initialRest: Int = 90,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (String, Double, Int, Int, Int) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var weight by remember { mutableStateOf(displayWeight(initialWeight)) }
    var sets by remember { mutableStateOf(initialSets.toString()) }
    var reps by remember { mutableStateOf(initialReps.toString()) }
    var rest by remember { mutableStateOf(initialRest.toString()) }
    val parsedWeight = weight.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..5000.0 }
    val parsedSets = sets.toIntOrNull()?.takeIf { it in 1..100 }
    val parsedReps = reps.toIntOrNull()?.takeIf { it in 1..999 }
    val parsedRest = rest.toIntOrNull()?.takeIf { it in 0..3600 }
    val valid = name.trim().isNotEmpty() && parsedWeight != null && parsedSets != null && parsedReps != null && parsedRest != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (onDelete == null) "添加动作" else "编辑动作") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(80) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("动作名称") }
                )
                OutlinedTextField(
                    value = weight, onValueChange = { weight = it.take(9) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    isError = weight.isNotBlank() && parsedWeight == null,
                    label = { Text("目标重量（kg）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sets, onValueChange = { sets = it.take(3) },
                        modifier = Modifier.weight(1f), singleLine = true,
                        isError = sets.isNotBlank() && parsedSets == null,
                        label = { Text("组数") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    OutlinedTextField(
                        value = reps, onValueChange = { reps = it.take(3) },
                        modifier = Modifier.weight(1f), singleLine = true,
                        isError = reps.isNotBlank() && parsedReps == null,
                        label = { Text("每组次数") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                OutlinedTextField(
                    value = rest, onValueChange = { rest = it.take(4) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    isError = rest.isNotBlank() && parsedRest == null,
                    label = { Text("组间休息（秒）") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Text("0 kg 可记自重训练；0 秒表示不启动倒计时。", style = MaterialTheme.typography.bodySmall)
                if (onDelete != null) TextButton(onClick = onDelete) {
                    Text("从当天计划移除", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = valid,
                onClick = {
                    if (parsedWeight != null && parsedSets != null && parsedReps != null && parsedRest != null) {
                        onSave(name.trim(), parsedWeight, parsedSets, parsedReps, parsedRest)
                    }
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun PlanScreen(repo: WorkoutRepository) {
    val loaded: AppData? by repo.data.collectAsState(initial = null)
    val data = loaded ?: return PlannerLoading()
    val scope = rememberCoroutineScope()
    var weekday by rememberSaveable { mutableIntStateOf(LocalDate.now().dayOfWeek.value) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val plan = data.weekly[weekday].orEmpty()
    PlannerColumn {
        PlannerIntro("周计划", "为每周重复安排。已经开始的训练不会随计划改变。")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            WeekdayNames.forEachIndexed { index, day ->
                val number = index + 1
                val selected = weekday == number
                val hasPlan = data.weekly[number].orEmpty().isNotEmpty()
                val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                val foreground = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                Column(
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                        .background(background)
                        .clickable(enabled = !saving) { weekday = number }
                        .semantics(mergeDescendants = true) {
                            contentDescription = "周${day}，${if (hasPlan) "有计划" else "休息"}${if (selected) "，已选" else ""}"
                        }
                        .heightIn(min = 48.dp).padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(day, color = foreground, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    Spacer(Modifier.height(3.dp))
                    Box(Modifier.size(4.dp).clip(RoundedCornerShape(4.dp)).background(if (hasPlan) foreground else Color.Transparent))
                }
            }
        }
        PlannerHeading("周${WeekdayNames[weekday - 1]} · ${if (plan.isEmpty()) "休息日" else "${plan.size} 个动作"}")
        EditablePlan(
            key = "week-$weekday",
            plan = plan,
            enabled = !saving,
            onChange = { updated ->
                val editingDay = weekday
                saving = true
                error = null
                scope.launch {
                    try {
                        repo.saveWeekly(editingDay, updated)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        error = "保存失败，原计划未更改，请再试一次。"
                    } finally {
                        saving = false
                    }
                }
            }
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text("想只调整某个具体日期？在「日历」里选择该日期即可。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EditablePlan(key: String, plan: List<ExercisePlan>, enabled: Boolean, onChange: (List<ExercisePlan>) -> Unit) {
    // Null closes the form; -1 starts a new row; otherwise edit by snapshot order.
    var editing by remember(key) { mutableStateOf<Int?>(null) }
    if (plan.isEmpty()) PlannerEmpty("休息日", "没有安排训练动作。添加动作后即为训练日。")
    plan.forEachIndexed { index, exercise ->
        PlannerPanel {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(exercise.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "${displayWeight(exercise.weight)} kg · ${exercise.sets} 组 × ${exercise.reps} 次 · 休息 ${exercise.restSeconds} 秒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { editing = index }, enabled = enabled) { Text("编辑") }
            }
        }
    }
    OutlinedButton(
        onClick = { editing = -1 },
        enabled = enabled && plan.size < 100,
        modifier = Modifier.fillMaxWidth()
    ) { Text("＋ 添加动作") }
    if (plan.size == 100) Text("一天最多安排 100 个动作。", style = MaterialTheme.typography.bodySmall)
    editing?.let { index ->
        val existing = plan.getOrNull(index)
        ExerciseForm(
            initialName = existing?.name.orEmpty(),
            initialWeight = existing?.weight ?: 0.0,
            initialSets = existing?.sets ?: 3,
            initialReps = existing?.reps ?: 10,
            initialRest = existing?.restSeconds ?: 90,
            onDismiss = { editing = null },
            onDelete = existing?.let {
                {
                    editing = null
                    onChange(plan.filterIndexed { itemIndex, _ -> itemIndex != index })
                }
            },
            onSave = { name, weight, sets, reps, rest ->
                val updated = ExercisePlan(name, weight, sets, reps, rest)
                val next = if (existing == null) {
                    plan + updated
                } else {
                    plan.mapIndexed { i, old -> if (i == index) updated else old }
                }
                editing = null
                onChange(next)
            }
        )
    }
}

private fun statusLabel(status: DayStatus): String = when (status) {
    DayStatus.REST -> "休息日"
    DayStatus.PLANNED -> "待训练"
    DayStatus.PARTIAL -> "部分完成"
    DayStatus.COMPLETE -> "已完成"
}

/** Calendar status colors deliberately differ from the app's shared green theme. */
@Composable
private fun CalendarStatusMark(status: DayStatus, modifier: Modifier = Modifier) {
    val dark = isSystemInDarkTheme()
    val tint = when (status) {
        DayStatus.PLANNED -> if (dark) Color(0xFF9BC5FF) else Color(0xFF175BB0)
        DayStatus.PARTIAL -> if (dark) Color(0xFFFFBF63) else Color(0xFF8B4900)
        DayStatus.COMPLETE -> if (dark) Color(0xFF85E2AD) else Color(0xFF08663C)
        DayStatus.REST -> if (dark) Color(0xFFB8C5C1) else Color(0xFF5A6468)
    }
    Canvas(modifier.size(14.dp)) {
        val u = size.minDimension / 14f
        fun point(x: Float, y: Float) = Offset(x * u, y * u)
        val outline = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (status) {
            DayStatus.PLANNED -> drawCircle(tint, radius = 4.1f * u, center = point(7f, 7f), style = outline)
            DayStatus.PARTIAL -> drawPath(Path().apply {
                moveTo(7f * u, 1.5f * u)
                lineTo(12.5f * u, 7f * u)
                lineTo(7f * u, 12.5f * u)
                lineTo(1.5f * u, 7f * u)
                close()
            }, tint)
            DayStatus.COMPLETE -> drawPath(Path().apply {
                moveTo(2.1f * u, 7.2f * u)
                lineTo(5.5f * u, 10.6f * u)
                lineTo(11.9f * u, 4.1f * u)
            }, tint, style = outline)
            DayStatus.REST -> drawLine(tint, point(3f, 7f), point(11f, 7f), 2f * u, StrokeCap.Round)
        }
    }
}

@Composable
fun CalendarScreen(repo: WorkoutRepository, onOpenWorkout: (LocalDate) -> Unit) {
    val loaded: AppData? by repo.data.collectAsState(initial = null)
    val data = loaded ?: return PlannerLoading()
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var dateText by rememberSaveable { mutableStateOf(today.toString()) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    val date = LocalDate.parse(dateText)
    val month = YearMonth.parse(monthText)
    val day = data.days[dateText]
    val plan = day?.plan ?: data.plan(date)
    val status = data.status(date)
    val overridden = dateText in data.overrides
    var editingDate by remember(dateText) { mutableStateOf(false) }
    var reset by remember(dateText) { mutableStateOf(false) }
    var confirmRest by remember(dateText) { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember(dateText) { mutableStateOf<String?>(null) }

    fun saveOverride(newPlan: List<ExercisePlan>?) {
        saving = true
        error = null
        scope.launch {
            try {
                repo.saveOverride(date, newPlan)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = "无法保存这天的计划，请重试。"
            } finally {
                saving = false
            }
        }
    }

    fun selectMonth(target: YearMonth) {
        monthText = target.toString()
        dateText = (if (target == YearMonth.from(today)) today else target.atDay(1)).toString()
    }

    PlannerColumn {
        PlannerIntro("训练日历", "查看每天的计划和实际完成情况。")
        PlannerPanel {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { selectMonth(month.minusMonths(1)) }, enabled = !saving) {
                    Text("‹", Modifier.semantics { contentDescription = "上个月" }, style = MaterialTheme.typography.headlineSmall)
                }
                Text("${month.year} 年 ${month.monthValue} 月", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                IconButton(onClick = { selectMonth(month.plusMonths(1)) }, enabled = !saving) {
                    Text("›", Modifier.semantics { contentDescription = "下个月" }, style = MaterialTheme.typography.headlineSmall)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                WeekdayNames.forEach { name ->
                    Text(name, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val offset = month.atDay(1).dayOfWeek.value - 1
            val weekCount = (offset + month.lengthOfMonth() + 6) / 7
            repeat(weekCount) { row ->
                Row(Modifier.fillMaxWidth()) {
                    repeat(7) { column ->
                        val number = row * 7 + column - offset + 1
                        if (number in 1..month.lengthOfMonth()) {
                            val cell = month.atDay(number)
                            val cellStatus = data.status(cell)
                            val selected = cell == date
                            val isToday = cell == today
                            Column(
                                Modifier.weight(1f)
                                    .padding(horizontal = 1.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable(enabled = !saving) { dateText = cell.toString() }
                                    .semantics(mergeDescendants = true) {
                                        contentDescription = "${displayDate(cell)}，${statusLabel(cellStatus)}${if (selected) "，已选" else ""}"
                                    }
                                    .heightIn(min = 43.dp)
                                    .padding(vertical = 3.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    "$number",
                                    fontWeight = if (selected || isToday) FontWeight.Bold else FontWeight.Normal,
                                    color = when {
                                        selected -> MaterialTheme.colorScheme.onPrimaryContainer
                                        isToday -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                CalendarStatusMark(cellStatus)
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            listOf(listOf(DayStatus.PLANNED, DayStatus.PARTIAL), listOf(DayStatus.COMPLETE, DayStatus.REST)).forEach { legend ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    legend.forEach { item ->
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                            CalendarStatusMark(item)
                            Text(statusLabel(item), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (date != today) {
                TextButton(onClick = { monthText = YearMonth.from(today).toString(); dateText = today.toString() }, enabled = !saving) {
                    Text("回到今天")
                }
            }
        }
        PlannerHeading(displayDate(date))
        PlannerPanel {
            Row(
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CalendarStatusMark(status)
                Text(statusLabel(status), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                when {
                    day != null -> "历史训练 · 保留开始时的计划"
                    overridden -> "该日期已单独调整"
                    else -> "沿用每周计划"
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
            )
        }
        if (day != null || !editingDate) {
            plan.forEachIndexed { index, item ->
                val records = day?.sets.orEmpty().filter { it.exerciseIndex == index }.sortedBy { it.setIndex }
                PlannerPanel {
                    Text(item.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "目标：${displayWeight(item.weight)} kg · ${item.sets} 组 × ${item.reps} 次",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    records.forEach { log ->
                        Text("第 ${log.setIndex + 1} 组：${displayWeight(log.weight)} kg × ${log.reps} 次", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (date <= today && (day != null || plan.isNotEmpty())) {
                Button(onClick = { onOpenWorkout(date) }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (day != null) "查看或更正训练记录" else if (date == today) "打开今日训练" else "补录训练")
                }
            }
        }
        if (day == null && editingDate) {
            EditablePlan("override-$dateText", plan, !saving, ::saveOverride)
            TextButton(
                enabled = !saving && plan.isNotEmpty(),
                onClick = { confirmRest = true }
            ) { Text("设为休息日") }
            if (overridden) {
                TextButton(enabled = !saving, onClick = { reset = true }) { Text("恢复每周计划") }
            }
            OutlinedButton(enabled = !saving, onClick = { editingDate = false }, modifier = Modifier.fillMaxWidth()) { Text("结束调整") }
        } else if (day == null) {
            OutlinedButton(enabled = !saving, onClick = { editingDate = true }, modifier = Modifier.fillMaxWidth()) { Text("只调整这一天") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (confirmRest) {
        AlertDialog(
            onDismissRequest = { confirmRest = false },
            title = { Text("设为休息日？") },
            text = { Text("这一天的动作将被清空，每周计划不会改动。") },
            confirmButton = { TextButton(onClick = { saveOverride(emptyList()); confirmRest = false }) { Text("设为休息日") } },
            dismissButton = { TextButton(onClick = { confirmRest = false }) { Text("取消") } }
        )
    }
    if (reset) {
        AlertDialog(
            onDismissRequest = { reset = false },
            title = { Text("恢复每周计划？") },
            text = { Text("这一天的单独安排将被移除，改用周${WeekdayNames[date.dayOfWeek.value - 1]}的每周计划。") },
            confirmButton = { TextButton(onClick = { saveOverride(null); reset = false }) { Text("恢复") } },
            dismissButton = { TextButton(onClick = { reset = false }) { Text("取消") } }
        )
    }
}

@Composable
fun StatsScreen(repo: WorkoutRepository) {
    val loaded: AppData? by repo.data.collectAsState(initial = null)
    val data = loaded ?: return PlannerLoading()
    var monthly by rememberSaveable { mutableStateOf(true) }
    val month = YearMonth.now()
    val records = remember(data, monthly, month) {
        data.stats(if (monthly) month else null).sortedWith(compareByDescending<ExerciseStats> { it.days }.thenBy { it.name })
    }
    PlannerColumn {
        PlannerIntro("训练统计", "按动作汇总实际记录，看见每一次进步。")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = monthly, onClick = { monthly = true }, label = { Text("本月") })
            FilterChip(selected = !monthly, onClick = { monthly = false }, label = { Text("累计") })
        }
        Text(
            if (monthly) "${month.year} 年 ${month.monthValue} 月" else "全部训练记录",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (records.isEmpty()) {
            PlannerEmpty("还没有训练记录", "保存至少一组实际重量和次数，即可在这里看到统计。")
        }
        records.forEach { item ->
            PlannerPanel {
                Text(item.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1.35f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${displayWeight(item.weight)} kg", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text("最高实练重量", style = MaterialTheme.typography.labelMedium)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${item.days} 天", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text("有效训练日", style = MaterialTheme.typography.labelMedium)
                    }
                }
                Text(
                    "最高重量记录：${item.reps} 次 · ${item.date}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            "同一动作在同一天记录多组只计 1 天。最高实练重量不等于 1RM。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

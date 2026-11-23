package com.liftlog.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WorkoutScreen(
    date: LocalDate,
    repository: WorkoutRepository,
    timer: RestTimer,
    modifier: Modifier = Modifier,
    onOpenPlan: () -> Unit = {},
    onOpenRestSound: () -> Unit = {},
    onOpenRestSetup: () -> Unit = {},
) {
    val snapshot: AppData? by repository.data.collectAsState(initial = null)
    val activeRest by timer.state.collectAsState()
    val scope = rememberCoroutineScope()
    var saving by remember(date) { mutableStateOf(false) }
    var error by remember(date) { mutableStateOf<String?>(null) }
    var editing by remember(date) { mutableStateOf<SetEditor?>(null) }
    var removing by remember(date) { mutableStateOf<SetEditor?>(null) }

    fun submit(operation: suspend () -> Unit) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            try {
                operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = if (failure is IllegalArgumentException || failure is IllegalStateException)
                    failure.message ?: "请检查后重试。" else "暂时未能保存，请重试。"
            } finally {
                saving = false
            }
        }
    }

    val today = LocalDate.now()
    val future = date > today
    val data = snapshot
    val log = data?.days?.get(date.toString())
    val plan = data?.plan(date).orEmpty()
    val records = log?.sets.orEmpty().groupBy { it.exerciseIndex }
    val goal = plan.sumOf { it.sets }
    val checked = log?.sets.orEmpty().count { it.setIndex in 0 until (plan.getOrNull(it.exerciseIndex)?.sets ?: 0) }
    val status = data?.status(date)

    fun saveSet(target: SetEditor, weight: Double, reps: Int) = submit {
        val newSet = repository.set(date, target.exerciseIndex, target.setIndex, weight, reps, expectNew = target.record == null)
        if (newSet && date == LocalDate.now()) timer.start(target.plan.restSeconds, target.plan.name)
        // Keep the tap target disabled until the committed row is visible to readers.
        withTimeoutOrNull(1500L) {
            repository.data.first { fresh ->
                fresh.days[date.toString()]?.sets?.any {
                    it.exerciseIndex == target.exerciseIndex && it.setIndex == target.setIndex &&
                        it.weight == weight && it.reps == reps
                } == true
            }
        }
        if (editing == target) editing = null
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (date == today) "今日训练" else if (future) "训练预览" else "训练记录",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenRestSound) { Text("提示音") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${date.year}年${date.monthValue}月${date.dayOfMonth}日 · 星期${"一二三四五六日"[date.dayOfWeek.value - 1]}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenRestSetup) { Text("后台提醒") }
            }
        }
        activeRest?.let { rest ->
            stickyHeader(key = "rest") { RestCard(rest, timer) }
        }
        if (data == null) {
            item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
        } else {
            item(key = "summary") {
                WorkoutSummary(
                    status = status ?: DayStatus.REST,
                    goal = goal,
                    checked = checked,
                    sessionStarted = log != null,
                    ended = log?.ended == true,
                    future = future,
                    busy = saving,
                    onOpenPlan = onOpenPlan,
                    onStart = { submit { repository.start(date) } },
                    onResume = { submit { repository.reopen(date) } },
                )
            }
            if (error != null) item(key = "error") {
                Text(
                    text = error.orEmpty(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            itemsIndexed(plan, key = { index, _ -> "exercise-$index" }) { index, exercise ->
                val sets = records[index].orEmpty()
                val taken = sets.map { it.setIndex }.toHashSet()
                val next = (0 until 200).firstOrNull { it !in taken }
                val nextSet = next?.let { SetEditor(index, it, exercise) }
                WorkoutExerciseCard(
                    ordinal = index + 1,
                    plan = exercise,
                    records = sets,
                    enabled = !saving,
                    next = nextSet.takeIf { log != null && !log.ended && !future },
                    onDefault = { target -> saveSet(target, exercise.weight, exercise.reps) },
                    onEdit = { target -> editing = target },
                    onRemove = { target -> removing = target },
                    toEditor = { row -> SetEditor(index, row.setIndex, exercise, row) },
                )
            }
            if (log != null && !log.ended) item(key = "end") {
                OutlinedButton(
                    onClick = { submit { repository.finish(date) } },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (checked == goal) "结束训练" else "结束训练并保留已记组") }
            }
        }
    }

    editing?.let { target ->
        SetEditorDialog(
            target = target,
            enabled = !saving,
            error = error,
            onClose = { if (!saving) { editing = null; error = null } },
            onSave = { weight, reps -> saveSet(target, weight, reps) },
        )
    }

    removing?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!saving) removing = null },
            title = { Text("撤销这组？") },
            text = {
                Text("${target.plan.name} · 第 ${target.setIndex + 1} 组将从训练记录和统计中移除。")
            },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        submit {
                            repository.undo(date, target.exerciseIndex, target.setIndex)
                            removing = null
                        }
                    },
                ) { Text("撤销记录") }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }, enabled = !saving) { Text("取消") }
            },
        )
    }
}

private data class SetEditor(
    val exerciseIndex: Int,
    val setIndex: Int,
    val plan: ExercisePlan,
    val record: SetLog? = null,
)

@Composable
private fun RestCard(state: RestState, timer: RestTimer) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("组间休息", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(state.label, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    String.format(Locale.ROOT, "%02d:%02d", state.seconds / 60, state.seconds % 60),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { timer.adjust(-15) }) { Text("−15 秒") }
                TextButton(onClick = { timer.adjust(15) }) { Text("+15 秒") }
                TextButton(onClick = { timer.skip() }) { Text("跳过") }
            }
        }
    }
}

@Composable
private fun WorkoutSummary(
    status: DayStatus,
    goal: Int,
    checked: Int,
    sessionStarted: Boolean,
    ended: Boolean,
    future: Boolean,
    busy: Boolean,
    onOpenPlan: () -> Unit,
    onStart: () -> Unit,
    onResume: () -> Unit,
) {
    val title = when (status) {
        DayStatus.REST -> "休息日"
        DayStatus.PLANNED -> if (sessionStarted && !ended) "训练已开始" else "待训练"
        DayStatus.PARTIAL -> if (!ended) "训练进行中" else "部分完成"
        DayStatus.COMPLETE -> "已完成"
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (goal > 0) Text("$checked / $goal 组", style = MaterialTheme.typography.titleMedium)
            }
            if (goal > 0) {
                LinearProgressIndicator(
                    progress = { (checked.toFloat() / goal).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            when {
                goal == 0 -> {
                    Text("这一天没有安排动作。", style = MaterialTheme.typography.bodyMedium)
                    FilledTonalButton(onClick = onOpenPlan) { Text("设置训练计划") }
                }
                future -> Text("到训练日后可记录实际重量和次数。", style = MaterialTheme.typography.bodyMedium)
                ended -> Button(onClick = onResume, enabled = !busy) { Text("继续训练") }
                !sessionStarted -> {
                    Text("开始后会保留当天计划，之后修改周计划不影响这次训练。", style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = onStart, enabled = !busy) { Text("开始训练") }
                }
                else -> Text(
                    "按实际重量和次数记组。没达到目标次数，也算完成一组。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun WorkoutExerciseCard(
    ordinal: Int,
    plan: ExercisePlan,
    records: List<SetLog>,
    enabled: Boolean,
    next: SetEditor?,
    onDefault: (SetEditor) -> Unit,
    onEdit: (SetEditor) -> Unit,
    onRemove: (SetEditor) -> Unit,
    toEditor: (SetLog) -> SetEditor,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        ordinal.toString().padStart(2, '0'),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Text(plan.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Text(
                "目标：${kgLabel(plan.weight)} × ${plan.reps} 次 · ${plan.sets} 组",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (plan.restSeconds == 0) "不设组间休息" else "组间休息 ${plan.restSeconds} 秒",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (records.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))
                records.forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            val label = if (row.setIndex < plan.sets) "第 ${row.setIndex + 1} 组" else "加练 ${row.setIndex - plan.sets + 1}"
                            Text(
                                "$label：${kgLabel(row.weight)} × ${row.reps} 次",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (row.setIndex < plan.sets && (row.reps < plan.reps || row.weight < plan.weight)) {
                                Text("已记录，低于目标", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        TextButton(onClick = { onEdit(toEditor(row)) }, enabled = enabled) { Text("编辑") }
                        TextButton(onClick = { onRemove(toEditor(row)) }, enabled = enabled) { Text("撤销") }
                    }
                }
            }
            if (next != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val label = if (next.setIndex < plan.sets) "记录第 ${next.setIndex + 1} 组" else "记录加练"
                    Button(
                        onClick = { onDefault(next) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text(label) }
                    OutlinedButton(onClick = { onEdit(next) }, enabled = enabled) { Text("改重量 / 次数") }
                }
            }
        }
    }
}

@Composable
private fun SetEditorDialog(
    target: SetEditor,
    enabled: Boolean,
    error: String?,
    onClose: () -> Unit,
    onSave: (Double, Int) -> Unit,
) {
    var weight by remember(target) { mutableStateOf(numberLabel(target.record?.weight ?: target.plan.weight)) }
    var reps by remember(target) { mutableStateOf((target.record?.reps ?: target.plan.reps).toString()) }
    val validWeight = weight.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..5000.0 }
    val validReps = reps.trim().toIntOrNull()?.takeIf { it in 1..999 }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("第 ${target.setIndex + 1} 组 · ${target.plan.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = weight,
                    onValueChange = { if (it.length <= 12) weight = it },
                    label = { Text("实际重量 (kg)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    isError = weight.isNotEmpty() && validWeight == null,
                )
                OutlinedTextField(
                    value = reps,
                    onValueChange = { if (it.length <= 5) reps = it },
                    label = { Text("实际次数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = reps.isNotEmpty() && validReps == null,
                )
                Text(
                    error ?: "重量可填 0（如自重动作），次数需大于 0。",
                    color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = enabled && validWeight != null && validReps != null,
                onClick = { if (validWeight != null && validReps != null) onSave(validWeight, validReps) },
            ) { Text("保存记录") }
        },
        dismissButton = { TextButton(onClick = onClose, enabled = enabled) { Text("取消") } },
    )
}

private fun numberLabel(value: Double): String =
    java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

private fun kgLabel(value: Double): String = "${numberLabel(value)} kg"

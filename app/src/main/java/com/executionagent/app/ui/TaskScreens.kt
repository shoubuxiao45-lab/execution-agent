package com.executionagent.app.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.model.TaskEvent
import com.executionagent.app.domain.model.TaskDraft
import com.executionagent.app.domain.model.TaskStatus
import com.executionagent.app.domain.engine.RiskLevel
import com.executionagent.app.domain.engine.RiskAssessment
import com.executionagent.app.domain.usecase.projectToday
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.catch

private fun timestamp(value: Long?, zoneId: ZoneId): String =
    if (value == null) "未设置" else DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(value).atZone(zoneId))
private fun TaskStatus.label(): String = when (this) {
    TaskStatus.PLANNED -> "待开始"
    TaskStatus.ACTIVE -> "执行中"
    TaskStatus.SNOOZED -> "已推迟"
    TaskStatus.OVERDUE -> "已延期"
    TaskStatus.COMPLETED -> "已完成"
    TaskStatus.CANCELLED -> "已放弃"
}
private fun RiskLevel.label(): String = when (this) { RiskLevel.LOW -> "低"; RiskLevel.MEDIUM -> "中"; RiskLevel.HIGH -> "高" }

@Composable
fun TodayScreen(state: TaskUiState, zoneId: ZoneId, onNew: () -> Unit, onOpen: (String) -> Unit, onRetry: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf("今日") }
    val groups = remember(state.tasks, state.now, zoneId) { projectToday(state.tasks, state.now, zoneId) }
    val tasks = when (tab) {
        "以后" -> groups.upcoming
        "已结束" -> groups.closed
        "全部" -> state.tasks
        else -> groups.today
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("今天，从一个小步骤开始", style = MaterialTheme.typography.headlineSmall)
                Text("今日已完成 ${groups.completedToday} · 待执行 ${groups.today.size}")
                Text("高风险 ${groups.today.count { state.risks[it.id]?.level == RiskLevel.HIGH }} 项")
                Button(onClick = onNew, enabled = state.loaded && state.loadError == null && !state.busy) { Text("新建任务") }
                Text("计划开始和执行检查可在设置中管理", style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("今日", "以后", "已结束", "全部").forEach { label ->
                    FilterChip(selected = tab == label, onClick = { tab = label }, label = { Text(label) })
                }
            }
        }
        when {
            state.loadError != null -> item { LoadFailure(state.loadError, onRetry) }
            !state.loaded -> item { Text("正在读取任务…") }
            tasks.isEmpty() -> item { Text(if (tab == "今日") "今日没有待执行任务，先安排一个小步骤。" else "这里还没有任务。") }
        }
        items(tasks, key = { it.id }) { task ->
            Card(onClick = { onOpen(task.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium)
                    val status = task.displayStatus(state.now)
                    Text("${status.label()} · ${task.category.ifBlank { "未分类" }} · ${task.estimatedDurationMinutes} 分钟",
                        color = if (status == TaskStatus.OVERDUE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    if (task.goal.isNotBlank()) Text(task.goal, maxLines = 2)
                    Text("计划 ${timestamp(task.plannedStartAt, zoneId)} · 进度 ${task.progress}%")
                    if (!task.isTerminal) state.risks[task.id]?.let { risk ->
                        Text("风险${risk.level.label()} · ${risk.score}/100",
                            color = if (risk.level == RiskLevel.HIGH) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                    LinearProgressIndicator(progress = { task.progress / 100f }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        item { Text("", Modifier.padding(bottom = 12.dp)) }
    }
}

@Composable
private fun LoadFailure(message: String, onRetry: () -> Unit) {
    Column {
        Text(message, color = MaterialTheme.colorScheme.error)
        Button(onClick = onRetry) { Text("重试") }
    }
}

@Composable
fun TaskDetailScreen(state: TaskUiState, id: String, zoneId: ZoneId,
    onAction: (String, TaskAction, Int) -> Unit, onEdit: () -> Unit, onBack: () -> Unit, onRetry: () -> Unit,
    history: Flow<List<TaskEvent>> = flowOf(emptyList()), onCoach: () -> Unit = {}) {
    val task = state.tasks.find { it.id == id }
    val historyState by remember(history) {
        history.map { HistoryUiState(events = it, loaded = true) }.catch { emit(HistoryUiState(error = true)) }
    }.collectAsStateWithLifecycle(HistoryUiState())
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler { if (!state.busy) onBack() }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            TextButton(onClick = onBack, enabled = !state.busy) { Text("返回") }
            Text("任务详情", style = MaterialTheme.typography.headlineSmall)
        }
        if (state.loadError != null) item { LoadFailure(state.loadError, onRetry) }
        else if (!state.loaded) item { Text("正在读取任务…") }
        else if (task == null) item { Text("任务已不存在") }
        else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleLarge)
                    Text(task.displayStatus(state.now).label())
                    Text("目标：${task.goal.ifBlank { "未填写" }}")
                    Text("分类：${task.category.ifBlank { "未分类" }}")
                    Text("计划开始：${timestamp(task.plannedStartAt, zoneId)}")
                    Text("预计完成：${timestamp(task.expectedFinishAt, zoneId)}")
                    Text("最晚完成（可选）：${timestamp(task.deadlineAt, zoneId)}")
                    if (task.isPlanOverrun) Text("已超过计划时长，可继续执行或调整目标")
                    Text("预计 ${task.estimatedDurationMinutes} 分钟 · 进度 ${task.progress}%")
                    Text("已推迟 ${task.snoozeCount} 次")
                    if (!task.isTerminal) task.nextCheckAt?.let { Text("下一次检查 ${timestamp(it, zoneId)}（受安静时段及上限影响）") }
                    task.actualStartAt?.let { Text("实际开始：${timestamp(it, zoneId)}") }
                    task.completedAt?.let { Text("完成时间：${timestamp(it, zoneId)}") }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (state.busy) Text("正在保存…")
                    if (!task.isTerminal) state.risks[id]?.let { RiskExplanation(it) }
                    HorizontalDivider()
                }
            }
            item { OutlinedButton(onClick = onCoach, enabled = !state.busy) { Text("讨论下一步") } }
            if (!task.isTerminal) {
                item { OutlinedButton(onClick = onEdit, enabled = !state.busy) { Text("编辑任务") } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onAction(id, TaskAction.SNOOZE, 10) }, enabled = !state.busy) { Text("推迟10分钟") }
                        OutlinedButton(onClick = { onAction(id, TaskAction.SNOOZE, 60) }, enabled = !state.busy) { Text("推迟1小时") }
                    }
                    Text("推迟仅调整提醒时间，最晚完成时间保留。", style = MaterialTheme.typography.bodySmall)
                }
                item {
                    Button(onClick = { onAction(id, TaskAction.START, 0) }, enabled = !state.busy) { Text(if (task.status == TaskStatus.ACTIVE) "仍在做（动态安排检查）" else "开始执行") }
                }
                item { ProgressEditor(task, state.busy) { onAction(id, TaskAction.PROGRESS, it) } }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { confirm = "complete" }, enabled = !state.busy) { Text("标记完成") }
                        OutlinedButton(onClick = { confirm = "cancel" }, enabled = !state.busy) { Text("放弃任务") }
                    }
                }
            }
            item { TextButton(onClick = { confirm = "delete" }, enabled = !state.busy) { Text("删除任务") } }
            item {
                HorizontalDivider()
                Text("操作历史（最近100条）", style = MaterialTheme.typography.titleMedium)
                if (historyState.error) Text("历史读取失败，请重新打开此页", color = MaterialTheme.colorScheme.error)
                else if (!historyState.loaded) Text("正在读取历史…")
                else if (historyState.events.isEmpty()) Text("暂无历史记录；旧版本的操作不会补记。")
            }
            items(historyState.events, key = { "event-${it.id}" }) { event ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(event.label(), style = MaterialTheme.typography.titleSmall)
                    Text(timestamp(event.occurredAt, zoneId), style = MaterialTheme.typography.bodySmall)
                    val description = event.description(zoneId)
                    if (description.isNotBlank()) Text(description)
                }
            }
        }
        item { Text("", Modifier.padding(bottom = 16.dp)) }
    }
    confirm?.let { action ->
        val label = when (action) { "complete" -> "完成任务"; "cancel" -> "放弃任务"; else -> "删除任务" }
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text("确认$label？") },
            text = { Text(when (action) {
                "delete" -> "任务及其关联的事件和聊天历史将永久删除。此操作不能撤销。"
                "cancel" -> "任务将移入已结束列表，保留当前进度。本阶段不支持重新打开。"
                else -> "任务进度将设为 100%，并记录完成时间。"
            }) },
            confirmButton = { TextButton(onClick = {
                confirm = null
                onAction(id, when (action) { "complete" -> TaskAction.COMPLETE; "cancel" -> TaskAction.CANCEL; else -> TaskAction.DELETE }, 0)
            }) { Text("确认") } }, dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } })
    }
}

@Composable
private fun RiskExplanation(risk: RiskAssessment) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("风险${risk.level.label()} · ${risk.score}/100", style = MaterialTheme.typography.titleMedium)
            risk.reasons.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text(risk.nextStep)
            Text("本地规则判断，分数不是完成概率。需要调整时请编辑任务。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProgressEditor(task: Task, busy: Boolean, onSave: (Int) -> Unit) {
    var value by rememberSaveable(task.id, task.progress) { mutableFloatStateOf(task.progress.toFloat()) }
    Column {
        Text("进度 ${value.roundToInt()}%")
        Slider(value = value, onValueChange = { value = it }, valueRange = 0f..100f, steps = 99,
            enabled = !busy, modifier = Modifier.testTag("progress-slider"))
        OutlinedButton(onClick = { onSave(value.roundToInt()) }, enabled = !busy && value.roundToInt() != task.progress) { Text("保存进度") }
        Text("进度达到 100% 后，仍需点击“标记完成”。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun TaskEditorScreen(state: TaskUiState, id: String?, zoneId: ZoneId, onSave: (String?, TaskDraft) -> Unit,
    onBack: () -> Unit, onRetry: () -> Unit) {
    val task = state.tasks.find { it.id == id }
    if (state.loadError != null || !state.loaded || (id != null && task == null)) {
        Column(Modifier.padding(20.dp)) {
            TextButton(onClick = onBack) { Text("返回") }
            if (state.loadError != null) LoadFailure(state.loadError, onRetry)
            else Text(if (!state.loaded) "正在读取任务…" else "任务已不存在")
        }
        return
    }
    if (task?.isTerminal == true) {
        Column(Modifier.padding(20.dp)) {
            Text("已结束任务不能编辑")
            TextButton(onClick = onBack) { Text("返回") }
        }
        return
    }
    TaskForm(task, state, zoneId, onSave, onBack)
}

@Composable
private fun TaskForm(task: Task?, state: TaskUiState, zoneId: ZoneId,
    onSave: (String?, TaskDraft) -> Unit, onBack: () -> Unit) {
    val id = task?.id
    val initialStart = rememberSaveable(id) { task?.plannedStartAt ?: (state.now / 60_000 * 60_000) }
    val initialDeadline = rememberSaveable(id) { task?.deadlineAt ?: (initialStart + 60 * 60_000) }
    var title by rememberSaveable(id) { mutableStateOf(task?.title.orEmpty()) }
    var goal by rememberSaveable(id) { mutableStateOf(task?.goal.orEmpty()) }
    var category by rememberSaveable(id) { mutableStateOf(task?.category.orEmpty()) }
    var duration by rememberSaveable(id) { mutableStateOf((task?.estimatedDurationMinutes ?: 30).toString()) }
    var start by rememberSaveable(id) { mutableLongStateOf(initialStart) }
    var deadline by rememberSaveable(id) { mutableLongStateOf(initialDeadline) }
    var hasStart by rememberSaveable(id) { mutableStateOf(task == null || task.plannedStartAt != null) }
    var hasDeadline by rememberSaveable(id) { mutableStateOf(task?.deadlineAt != null) }
    var prepare by rememberSaveable(id) { mutableStateOf(task?.prepareReminderMinutes ?: 0) }
    var explicit by rememberSaveable(id) { mutableStateOf(task?.reminderMode == com.executionagent.app.domain.model.ReminderMode.EXPLICIT) }
    var localError by rememberSaveable(id) { mutableStateOf<String?>(null) }
    var discard by rememberSaveable(id) { mutableStateOf(false) }
    val dirty = title != task?.title.orEmpty() || goal != task?.goal.orEmpty() || category != task?.category.orEmpty() ||
        duration != (task?.estimatedDurationMinutes ?: 30).toString() || start != initialStart || deadline != initialDeadline || hasStart != (task == null || task.plannedStartAt != null) || hasDeadline != (task?.deadlineAt != null) || prepare != (task?.prepareReminderMinutes ?: 0) || explicit != (task?.reminderMode == com.executionagent.app.domain.model.ReminderMode.EXPLICIT)
    val leave = { if (!state.busy) { if (dirty) discard = true else onBack() } }
    BackHandler { leave() }
    LazyColumn(Modifier.fillMaxSize().imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            TextButton(onClick = leave, enabled = !state.busy) { Text("返回") }
            Text(if (id == null) "新建任务" else "编辑任务", style = MaterialTheme.typography.headlineSmall)
        }
        item { OutlinedTextField(title, { title = it; localError = null }, label = { Text("标题（必填）") },
            enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("task-title")) }
        item { OutlinedTextField(goal, { goal = it }, label = { Text("目标，例如完成两道题") },
            enabled = !state.busy, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth().testTag("task-goal")) }
        item { OutlinedTextField(category, { category = it }, label = { Text("分类（可选）") },
            enabled = !state.busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("task-category")) }
        item {
            FilterChip(selected = hasStart, onClick = { hasStart = !hasStart }, label = { Text("设置计划开始") }, enabled = !state.busy)
            if (hasStart) DateTimeField("计划开始", start, zoneId, !state.busy) { start = it }
            else Text("旧任务没有计划时间；补充后才能按计划开始提醒。")
        }
        item { OutlinedTextField(duration, { duration = it }, label = { Text("预计时长（分钟）") },
            enabled = !state.busy, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("task-duration")) }
        item {
            Text("预计完成：${timestamp(if (hasStart) start + (duration.toIntOrNull() ?: 0) * 60_000L else null, zoneId)}")
            if ((duration.toIntOrNull() ?: 0) > 120) Text("任务超过两小时，建议拆成较小步骤。")
            FilterChip(selected = hasDeadline, onClick = { hasDeadline = !hasDeadline }, label = { Text("设置最晚完成时间（可选）") }, enabled = !state.busy)
            if (hasDeadline) DateTimeField("最晚完成", deadline, zoneId, !state.busy) { deadline = it }
        }
        item {
            Text("准备提醒（默认关闭）")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 5, 10, 15).forEach { minutes -> FilterChip(selected = prepare == minutes,
                    onClick = { prepare = minutes }, enabled = !state.busy,
                    label = { Text(if (minutes == 0) "关闭" else "${minutes}分钟") }) }
            }
            FilterChip(selected = explicit, onClick = { explicit = !explicit }, enabled = !state.busy,
                label = { Text("按指定时间提醒（包括安静时段）") })
            Text("普通提醒顺延到安静时段结束；指定时间提醒仍受每日上限影响。", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("时间采用设备时区：${zoneId.id}。允许记录已延期的任务。", style = MaterialTheme.typography.bodySmall)
            (localError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = {
                val input = TaskDraft(title, goal, category, if (hasStart) start else null, if (hasDeadline) deadline else null, duration.toIntOrNull() ?: 0,
                    prepare.takeIf { it > 0 }, if (explicit) com.executionagent.app.domain.model.ReminderMode.EXPLICIT else com.executionagent.app.domain.model.ReminderMode.NORMAL)
                try { onSave(id, input.validated()); localError = null }
                catch (invalid: IllegalArgumentException) { localError = invalid.message }
            }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("task-save")) {
                Text(if (state.busy) "正在保存…" else "保存任务")
            }
        }
        item { Text("", Modifier.padding(bottom = 16.dp)) }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("放弃未保存的修改？") },
        text = { Text("本次填写的内容尚未保存。") }, confirmButton = {
            TextButton(onClick = { discard = false; onBack() }) { Text("放弃修改") }
        }, dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}

@Composable
private fun DateTimeField(label: String, value: Long, zoneId: ZoneId, enabled: Boolean, onChange: (Long) -> Unit) {
    val context = LocalContext.current
    val local = Instant.ofEpochMilli(value).atZone(zoneId).toLocalDateTime()
    Column {
        Text(label)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                DatePickerDialog(context, { _, year, month, day ->
                    onChange(LocalDate.of(year, month + 1, day).atTime(local.toLocalTime()).atZone(zoneId).toInstant().toEpochMilli())
                }, local.year, local.monthValue - 1, local.dayOfMonth).show()
            }, enabled = enabled) { Text(DateTimeFormatter.ofPattern("yyyy-MM-dd").format(local)) }
            OutlinedButton(onClick = {
                TimePickerDialog(context, { _, hour, minute ->
                    onChange(local.withHour(hour).withMinute(minute).withSecond(0).withNano(0).atZone(zoneId).toInstant().toEpochMilli())
                }, local.hour, local.minute, true).show()
            }, enabled = enabled) { Text(DateTimeFormatter.ofPattern("HH:mm").format(local)) }
        }
    }
}

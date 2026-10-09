package com.executionagent.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.executionagent.app.domain.ai.*
import com.executionagent.app.domain.model.Task
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun CoachScreen(tasks: List<Task>, zone: ZoneId, initialId: String? = null, vm: CoachViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    LaunchedEffect(initialId, tasks.map { it.id }, state.busy) {
        val id = initialId?.takeIf { wanted -> tasks.any { it.id == wanted } }
            ?: state.taskId?.takeIf { wanted -> tasks.any { it.id == wanted } } ?: tasks.firstOrNull()?.id
        if (id != null) vm.select(id)
    }
    CoachContent(tasks, zone, state, config.isMock, vm::select, vm::input, vm::send, vm::apply, vm::undo)
}
@Composable
fun CoachContent(tasks: List<Task>, zone: ZoneId, state: CoachState, mock: Boolean, onSelect: (String) -> Unit,
    onInput: (String) -> Unit, onSend: () -> Unit, onApply: (String) -> Unit, onUndo: (String) -> Unit) {
    var confirmation by remember { mutableStateOf<ChatMessage?>(null) }
    val chatScroll = rememberLazyListState()
    LaunchedEffect(state.taskId, state.messages.map { it.id }) {
        if (state.messages.isNotEmpty()) chatScroll.animateScrollToItem(state.messages.lastIndex)
    }
    val selected = tasks.find { it.id == state.taskId }
    Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("任务教练 · ${if (mock) "离线 Mock" else "Kimi"}", style = MaterialTheme.typography.headlineSmall)
        Text(if (mock) "离线规则建议；每个动作都需要确认。" else "发送当前任务和有限聊天 / 事件上下文至 Kimi；动作仍需确认。", style = MaterialTheme.typography.bodySmall)
        if (tasks.isEmpty()) Text("先在 Today 创建一项任务，再来讨论下一步。")
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(tasks, key = { it.id }) { task ->
                FilterChip(selected = task.id == state.taskId, onClick = { onSelect(task.id) }, enabled = !state.busy, label = { Text(task.title.take(30)) })
            }
        }
        selected?.let { Text("${it.title} · 目标：${it.goal.ifBlank { "未填写" }}", style = MaterialTheme.typography.titleSmall) }
        LazyColumn(Modifier.weight(1f), state = chatScroll, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(state.messages, key = { it.id }) { message ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (message.role == "user") { Text("你", style = MaterialTheme.typography.labelMedium); Text(message.content) }
                        else {
                            val proposal = remember(message.content) { runCatching { Json.decodeFromString<Proposal>(message.content) }.getOrNull() }
                            Text("教练", style = MaterialTheme.typography.labelMedium)
                            if (proposal == null) Text("这条旧记录无法解析，未执行任何动作。") else {
                                Text(proposal.reply.reply)
                                proposal.reply.actions.forEach { Text(actionDescription(it, proposal.before, zone), style = MaterialTheme.typography.bodySmall) }
                                when (proposal.state) {
                                    ProposalState.PENDING -> if (proposal.reply.actions.isNotEmpty()) OutlinedButton(onClick = { confirmation = message }, enabled = !state.busy && selected != null) { Text("核对并应用") }
                                    ProposalState.APPLIED -> { Text("已应用"); OutlinedButton(onClick = { onUndo(message.id) }, enabled = !state.busy) { Text("撤销这次建议") } }
                                    ProposalState.UNDONE -> Text("已撤销")
                                }
                            }
                        }
                    }
                }
            }
        }
        (state.error ?: state.loadError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.loadError != null) OutlinedButton(onClick = { state.taskId?.let(onSelect) }, enabled = !state.busy) { Text("重读聊天") }
        OutlinedTextField(state.input, onInput, Modifier.fillMaxWidth(), label = { Text("说说现在卡在哪里") }, enabled = !state.busy && selected != null, maxLines = 3)
        Button(onClick = onSend, enabled = !state.busy && selected != null && state.input.isNotBlank()) { Text(if (state.busy) "处理中…" else if (state.error != null) "重试发送" else "发送") }
    }
    confirmation?.let { message ->
        val proposal = runCatching { Json.decodeFromString<Proposal>(message.content) }.getOrNull()
        if (proposal != null) AlertDialog(onDismissRequest = { confirmation = null }, title = { Text("确认修改「${proposal.before.title}」") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                proposal.reply.actions.forEach { Text(actionDescription(it, proposal.before, zone)) }
                Text("整批应用或整批失败。可撤销；如果任务或子任务后来有变化，撤销会被拒绝，以保留后续操作。拆分会取消原任务并创建子任务。")
            } }, confirmButton = { TextButton(onClick = { confirmation = null; onApply(message.id) }) { Text("确认应用") } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text("保持原计划") } })
    }
}
fun actionDescription(action: AgentAction, task: Task, zone: ZoneId): String {
    fun date(at: Long?) = if (at == null) "未设置" else Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    return when (action.type) {
        AgentActionType.START_TASK -> "开始任务，根据预计时长安排下一次检查"
        AgentActionType.COMPLETE_TASK -> "标记完成，进度改为100%，停止提醒"
        AgentActionType.CANCEL_TASK -> "取消任务，停止提醒"
        AgentActionType.SNOOZE_TASK -> "推迟 ${action.params.getValue("minutes").jsonPrimitive.content} 分钟；截止时间保持 ${date(task.deadlineAt)}"
        AgentActionType.CHANGE_GOAL -> "目标：${task.goal.ifBlank { "未填写" }} → ${action.params.getValue("goal").jsonPrimitive.content}"
        AgentActionType.CHANGE_DEADLINE -> "截止：${date(task.deadlineAt)} → ${date(action.params.getValue("deadlineAt").jsonPrimitive.long)}"
        AgentActionType.SCHEDULE_REMINDER -> "下次检查改为 ${date(action.params.getValue("at").jsonPrimitive.long)}，包括安静时段，仍受每日配额限制"
        AgentActionType.SPLIT_TASK -> "取消原任务，创建子任务（截止 ${date(task.deadlineAt)}）：" + action.params.getValue("children").jsonArray.joinToString("；") {
            val child = it.jsonObject
            "${child.getValue("title").jsonPrimitive.content} / ${child.getValue("goal").jsonPrimitive.content} / ${(child["estimatedDurationMinutes"] ?: child.getValue("estimatedMinutes")).jsonPrimitive.content}分钟"
        }
    }
}

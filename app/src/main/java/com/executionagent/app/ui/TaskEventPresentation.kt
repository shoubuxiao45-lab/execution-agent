package com.executionagent.app.ui

import com.executionagent.app.domain.model.TaskEvent
import com.executionagent.app.domain.model.TaskEventType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class HistoryUiState(val events: List<TaskEvent> = emptyList(), val loaded: Boolean = false, val error: Boolean = false)
internal fun TaskEvent.label(): String = when (type) {
    TaskEventType.CREATED -> "创建任务"
    TaskEventType.EDITED -> "编辑任务"
    TaskEventType.STARTED -> "开始 / 正在执行"
    TaskEventType.PROGRESS_UPDATED -> "更新进度"
    TaskEventType.REMINDER_SENT -> "发送提醒"
    TaskEventType.PROGRESS_CHECK -> "静默检查进度"
    TaskEventType.EXPECTED_FINISH_REACHED -> "到达预计完成时间"
    TaskEventType.OVERDUE_ENTERED -> "超过最晚完成时间"
    TaskEventType.SNOOZED -> "推迟提醒"
    TaskEventType.GOAL_REDUCED -> "缩小目标"
    TaskEventType.TASK_SPLIT -> "拆分任务"
    TaskEventType.RESCHEDULED -> "重新安排"
    TaskEventType.AI_INTERVENTION -> "Agent 干预"
    TaskEventType.COMPLETED -> "完成任务"
    TaskEventType.CANCELLED -> "放弃任务"
}
internal fun TaskEvent.description(zone: ZoneId): String = try {
    val root = Json.parseToJsonElement(payloadJson) as? JsonObject
    val after = root?.get("after") as? JsonObject
    fun value(key: String) = after?.get(key)?.jsonPrimitive?.content.orEmpty()
    fun time(key: String): String {
        val legacy = mapOf("nextCheckAt" to "nextReminderAt", "plannedStartAt" to "startAt", "actualStartAt" to "startedAt")
        val raw = (after?.get(key) ?: after?.get(legacy[key]))?.jsonPrimitive?.longOrNull ?: return ""
        return DateTimeFormatter.ofPattern("MM-dd HH:mm").format(Instant.ofEpochMilli(raw).atZone(zone))
    }
    when (type) {
        TaskEventType.PROGRESS_UPDATED, TaskEventType.COMPLETED -> "进度 ${value("progress")}%"
        TaskEventType.SNOOZED -> "累计推迟 ${value("snoozeCount")} 次 · 下次不早于 ${time("nextCheckAt")}"
        TaskEventType.RESCHEDULED -> "新截止 ${time("deadlineAt")}"
        TaskEventType.GOAL_REDUCED -> value("goal")
        TaskEventType.REMINDER_SENT -> {
            val policy = root?.get("policy") as? JsonObject
            val kind = when (root?.get("notificationType")?.jsonPrimitive?.content) {
                "PREPARE_REMINDER" -> "准备提醒"; "START_REMINDER" -> "开始提醒"; "START_DELAYED" -> "延迟开始提醒"
                "PROGRESS_CHECK" -> "进度检查"; "EXPECTED_FINISH_CHECK" -> "预计完成询问"; "OVERDUE_INTERVENTION" -> "逾期干预"; else -> ""
            }
            "已提交系统通知" + kind.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() + (policy?.get("nextStep")?.jsonPrimitive?.content?.let { " · $it" } ?: "")
        }
        TaskEventType.AI_INTERVENTION -> if (root?.get("operation")?.jsonPrimitive?.content == "UNDO") "已撤销这次建议" else "用户确认后应用建议"
        TaskEventType.TASK_SPLIT -> "原任务已取消，子任务替代原计划"
        TaskEventType.CREATED, TaskEventType.EDITED -> value("title")
        else -> ""
    }
} catch (_: Exception) { "" }

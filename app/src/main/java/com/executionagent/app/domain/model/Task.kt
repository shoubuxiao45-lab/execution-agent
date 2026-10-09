package com.executionagent.app.domain.model

@kotlinx.serialization.Serializable
data class Task(
    val id: String,
    val title: String,
    val goal: String,
    val category: String,
    @kotlinx.serialization.SerialName("startAt") val plannedStartAt: Long? = null,
    val deadlineAt: Long? = null,
    @kotlinx.serialization.SerialName("estimatedMinutes") val estimatedDurationMinutes: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val status: TaskStatus = TaskStatus.PLANNED,
    val progress: Int = 0,
    val snoozeCount: Int = 0,
    @kotlinx.serialization.SerialName("nextReminderAt") val nextCheckAt: Long? = null,
    @kotlinx.serialization.SerialName("startedAt") val actualStartAt: Long? = null,
    val completedAt: Long? = null,
    val prepareReminderMinutes: Int? = null,
    val reminderMode: ReminderMode = ReminderMode.NORMAL,
    val prepareReminderSent: Boolean = false,
    val startReminderSent: Boolean = false,
    val expectedFinishReached: Boolean = false,
    val overdueEnteredAt: Long? = null,
    val overdueCheckCount: Int = 0,
    val lastCheckAt: Long? = null,
    val snoozedUntil: Long? = null,
    val snoozedFromStatus: TaskStatus? = null
) {
    val expectedFinishAt: Long? get() = plannedStartAt?.let { it + estimatedDurationMinutes * 60_000L }
    val isPlanOverrun: Boolean get() = expectedFinishReached && !isTerminal
    val isTerminal: Boolean get() = status == TaskStatus.COMPLETED || status == TaskStatus.CANCELLED
    fun displayStatus(now: Long): TaskStatus =
        if (!isTerminal && deadlineAt != null && deadlineAt <= now) TaskStatus.OVERDUE else status
}

@kotlinx.serialization.Serializable
enum class ReminderMode { NORMAL, EXPLICIT }

data class TaskDraft(
    val title: String,
    val goal: String,
    val category: String,
    val plannedStartAt: Long?,
    val deadlineAt: Long?,
    val estimatedDurationMinutes: Int,
    val prepareReminderMinutes: Int? = null,
    val reminderMode: ReminderMode = ReminderMode.NORMAL
) {
    fun validated(): TaskDraft {
        val clean = copy(title = title.trim(), goal = goal.trim(), category = category.trim())
        require(clean.title.isNotEmpty() && clean.title.length <= 120) { "标题须为 1–120 个字符" }
        require(clean.goal.length <= 2000) { "目标不能超过 2000 个字符" }
        require(clean.category.length <= 40) { "分类不能超过 40 个字符" }
        require(clean.estimatedDurationMinutes in 1..1440) { "预计时长须为 1–1440 分钟" }
        require(clean.deadlineAt == null || clean.plannedStartAt == null || clean.deadlineAt > clean.plannedStartAt) { "最晚完成时间须晚于计划开始" }
        require(clean.prepareReminderMinutes == null || clean.prepareReminderMinutes in setOf(5, 10, 15)) { "提前提醒只支持5、10、15分钟" }
        return clean
    }
}


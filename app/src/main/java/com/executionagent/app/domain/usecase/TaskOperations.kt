package com.executionagent.app.domain.usecase

import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.model.TaskDraft
import com.executionagent.app.domain.model.TaskStatus
import com.executionagent.app.domain.model.TaskEventType
import com.executionagent.app.domain.repository.TaskRepository
import com.executionagent.app.domain.time.ClockProvider
import com.executionagent.app.domain.reminder.ReminderRefresh
import com.executionagent.app.domain.reminder.NoReminderRefresh
import java.util.UUID
import javax.inject.Inject

class TaskOperations @Inject constructor(
    private val repository: TaskRepository,
    private val clock: ClockProvider,
    private val reminders: ReminderRefresh = NoReminderRefresh
) {
    private val execution = com.executionagent.app.domain.engine.ExecutionEngine(clock)
    fun observeTasks() = repository.observeTasks()
    fun observeEvents(id: String) = repository.observeEvents(id)
    fun now() = clock.now().toEpochMilli()
    fun zoneId() = clock.zoneId()

    suspend fun save(id: String?, draft: TaskDraft) {
        val input = draft.validated()
        if (id == null) {
            val now = now()
            val task = Task(UUID.randomUUID().toString(), input.title, input.goal, input.category,
                input.plannedStartAt, input.deadlineAt, input.estimatedDurationMinutes, now, now,
                prepareReminderMinutes = input.prepareReminderMinutes, reminderMode = input.reminderMode)
            repository.create(task.copy(nextCheckAt = execution.calculateNextCheck(task, now)))
        } else repository.update(id, TaskEventType.EDITED, now()) { task ->
            require(!task.isTerminal) { "已结束任务不能编辑" }
            val timingChanged = input.plannedStartAt != task.plannedStartAt || input.deadlineAt != task.deadlineAt ||
                input.estimatedDurationMinutes != task.estimatedDurationMinutes || input.prepareReminderMinutes != task.prepareReminderMinutes || input.reminderMode != task.reminderMode
            val changed = task.copy(title = input.title, goal = input.goal, category = input.category,
                plannedStartAt = input.plannedStartAt, deadlineAt = input.deadlineAt,
                estimatedDurationMinutes = input.estimatedDurationMinutes, updatedAt = now(),
                prepareReminderMinutes = input.prepareReminderMinutes, reminderMode = input.reminderMode,
                status = if (!timingChanged) task.status else if (task.actualStartAt != null) TaskStatus.ACTIVE else TaskStatus.PLANNED,
                prepareReminderSent = if (input.plannedStartAt != task.plannedStartAt) false else task.prepareReminderSent,
                startReminderSent = if (input.plannedStartAt != task.plannedStartAt) false else task.startReminderSent,
                expectedFinishReached = if (timingChanged) false else task.expectedFinishReached,
                overdueEnteredAt = if (input.deadlineAt != task.deadlineAt) null else task.overdueEnteredAt,
                overdueCheckCount = if (input.deadlineAt != task.deadlineAt) 0 else task.overdueCheckCount,
                snoozedUntil = if (timingChanged) null else task.snoozedUntil,
                snoozedFromStatus = if (timingChanged) null else task.snoozedFromStatus)
            if (timingChanged) changed.copy(nextCheckAt = uniqueNext(execution.calculateNextCheck(changed, now()), task.nextCheckAt)) else changed
        }
        reminders.refresh()
    }

    suspend fun start(id: String, expectedNext: Long? = null, renewGrace: Boolean = false) {
        repository.update(id, TaskEventType.STARTED, now()) { task ->
        require(expectedNext == null || task.nextCheckAt == expectedNext) { "提醒已失效" }
        require(!task.isTerminal) { "已结束任务不能开始" }
        if (task.status == TaskStatus.ACTIVE && expectedNext == null && !renewGrace) task
        else {
            val active = execution.begin(task, now(), renewGrace)
            active.copy(nextCheckAt = uniqueNext(active.nextCheckAt, task.nextCheckAt))
        }
        }
        reminders.refresh()
    }

    suspend fun progress(id: String, value: Int) {
        repository.update(id, TaskEventType.PROGRESS_UPDATED, now()) { task ->
        require(!task.isTerminal) { "已结束任务不能更新进度" }
        require(value in 0..100) { "进度须为 0–100" }
        // 100% progress does not silently end the task; completion is explicit.
        val changed = task.copy(progress = value, updatedAt = now())
        if (changed.expectedFinishReached) changed.copy(nextCheckAt = uniqueNext(execution.calculateNextCheck(changed, now()), task.nextCheckAt)) else changed
        }
        reminders.refresh()
    }

    suspend fun complete(id: String, expectedNext: Long? = null) {
        repository.update(id, TaskEventType.COMPLETED, now()) { task ->
        require(expectedNext == null || task.nextCheckAt == expectedNext) { "提醒已失效" }
        require(task.status != TaskStatus.CANCELLED) { "已放弃任务不能完成" }
        if (task.status == TaskStatus.COMPLETED) task
        else task.copy(status = TaskStatus.COMPLETED, progress = 100, completedAt = now(),
            nextCheckAt = null, updatedAt = now())
        }
        reminders.refresh()
    }

    suspend fun cancel(id: String) {
        repository.update(id, TaskEventType.CANCELLED, now()) { task ->
        require(task.status != TaskStatus.COMPLETED) { "已完成任务不能放弃" }
        if (task.status == TaskStatus.CANCELLED) task
        else task.copy(status = TaskStatus.CANCELLED, nextCheckAt = null, updatedAt = now())
        }
        reminders.refresh()
    }

    suspend fun snooze(id: String, minutes: Int, expectedNext: Long? = null) {
        require(minutes in 1..1440) { "推迟时长须为 1–1440 分钟" }
        repository.update(id, TaskEventType.SNOOZED, now()) { task ->
            require(!task.isTerminal) { "已结束任务不能推迟" }
            require(expectedNext == null || task.nextCheckAt == expectedNext) { "提醒已失效" }
            val snoozed = execution.snooze(task, minutes, now())
            snoozed.copy(nextCheckAt = uniqueNext(snoozed.nextCheckAt, task.nextCheckAt))
        }
        reminders.refresh()
    }
    suspend fun delete(id: String) { repository.delete(id); reminders.refresh() }
    private fun uniqueNext(value: Long?, previous: Long?): Long? = if (value != null && value == previous) value + 1 else value
}

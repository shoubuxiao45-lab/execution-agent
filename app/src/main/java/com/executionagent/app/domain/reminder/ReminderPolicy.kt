package com.executionagent.app.domain.reminder

import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.repository.AgentSettings
import com.executionagent.app.domain.engine.ExecutionEngine
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

interface ReminderRefresh { suspend fun refresh() }
object NoReminderRefresh : ReminderRefresh { override suspend fun refresh() = Unit }
interface TaskNotifications {
    fun available(): Boolean
    fun post(task: Task, message: String): Boolean
    fun post(task: Task, message: String, type: com.executionagent.app.domain.engine.NotificationType): Boolean = post(task, message)
    fun cancel(id: String)
}
object NoTaskNotifications : TaskNotifications {
    override fun available() = false
    override fun post(task: Task, message: String) = false
    override fun cancel(id: String) = Unit
}

class ReminderPolicy @Inject constructor(private val execution: ExecutionEngine) {
    constructor() : this(ExecutionEngine())
    fun decision(task: Task, now: Long) = execution.assess(task, now)
    fun check(task: Task, now: Long) = execution.check(task, now)
    fun checkMessage(task: Task, decision: com.executionagent.app.domain.engine.ExecutionDecision) = execution.message(task, decision)
    fun deliveryFailed(task: Task, now: Long) = execution.deliveryFailed(task, now)
    fun repeatMinutes(task: Task, now: Long): Long = execution.assess(task, now).repeatMinutes
    fun message(task: Task, now: Long): String = "当前进度 ${task.progress}%。" + execution.assess(task, now).nextStep
    fun eligibleAt(task: Task, settings: AgentSettings, now: Long, zone: ZoneId, sentToday: Int): Long? {
        val adjusted = notificationAt(task, settings, now, zone, sentToday) ?: return null
        return execution.scheduledCheckAt(task, adjusted, now)
    }
    fun notificationAt(task: Task, settings: AgentSettings, now: Long, zone: ZoneId, sentToday: Int): Long? {
        if (!settings.remindersEnabled || task.isTerminal) return null
        var candidate = maxOf(now, task.nextCheckAt ?: return null)
        if (sentToday >= settings.dailyLimit) {
            val tomorrow = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone)
            candidate = maxOf(candidate, tomorrow.toInstant().toEpochMilli())
        }
        return if (task.reminderMode == com.executionagent.app.domain.model.ReminderMode.EXPLICIT) candidate else outsideQuiet(candidate, settings, zone)
    }
    fun outsideQuiet(at: Long, settings: AgentSettings, zone: ZoneId): Long {
        if (!settings.quietEnabled) return at
        val local = Instant.ofEpochMilli(at).atZone(zone)
        val minute = local.hour * 60 + local.minute
        val start = settings.quietStartMinute
        val end = settings.quietEndMinute
        val overnight = start > end
        val inside = if (overnight) minute >= start || minute < end else minute >= start && minute < end
        if (!inside) return at
        val date = if (overnight && minute >= start) local.toLocalDate().plusDays(1) else local.toLocalDate()
        val finish = date.atTime(LocalTime.of(end / 60, end % 60)).atZone(zone)
        val first = finish.toInstant().toEpochMilli()
        return if (first < at) finish.withLaterOffsetAtOverlap().toInstant().toEpochMilli() else first
    }
}

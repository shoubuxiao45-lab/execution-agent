package com.executionagent.app.domain.engine

import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.model.TaskStatus
import com.executionagent.app.domain.time.ClockProvider
import com.executionagent.app.domain.time.SystemClockProvider
import javax.inject.Inject
import kotlin.math.ceil

// Scores are transparent heuristics, not probabilities or learned predictions.
enum class RiskLevel { LOW, MEDIUM, HIGH }
enum class InterventionStrategy { NONE, START_STEP, KEEP_GOING, REDUCE_SCOPE, SPLIT_TASK, RESCHEDULE }
data class RiskAssessment(val score: Int, val level: RiskLevel, val strategy: InterventionStrategy,
    val reasons: List<String>, val nextStep: String, val repeatMinutes: Long)

class ExecutionEngine @Inject constructor(private val clock: ClockProvider) {
    constructor() : this(SystemClockProvider())
    fun assess(task: Task, now: Long = clock.now().toEpochMilli()): RiskAssessment {
        if (task.isTerminal) return RiskAssessment(0, RiskLevel.LOW, InterventionStrategy.NONE, emptyList(), "任务已结束。", 60)
        var score = 0
        val reasons = mutableListOf<String>()
        val remaining = ceil(task.estimatedDurationMinutes.coerceIn(1, 1440) * (100 - task.progress.coerceIn(0, 100)) / 100.0).toLong()
        val available = task.deadlineAt?.let { (it - now) / 60_000 } ?: Long.MAX_VALUE
        val overdue = task.deadlineAt?.takeIf { it <= now }?.let { (now - it) / 60_000 } ?: -1
        if (overdue >= 0) {
            score += 30
            if (overdue >= 60) score += 15
            if (overdue >= 180) score += 15
            reasons += "已超过截止时间 ${overdue} 分钟"
        }
        if (remaining > 0 && remaining >= maxOf(1, available)) {
            score += 20
            reasons += "剩余目标预计耗时已接近或超过可用时间"
        }
        if (task.actualStartAt == null && task.plannedStartAt?.let { it <= now } == true && task.progress < 100) {
            score += 10
            reasons += "计划开始时间已到，尚未开始"
        }
        if (task.snoozeCount > 0) {
            score += minOf(30, task.snoozeCount.coerceAtMost(3) * 10)
            reasons += "累计推迟 ${task.snoozeCount} 次"
        }
        score = score.coerceIn(0, 100)
        val level = when { score >= 60 -> RiskLevel.HIGH; score >= 30 -> RiskLevel.MEDIUM; else -> RiskLevel.LOW }
        val strategy = when {
            task.progress >= 100 -> InterventionStrategy.KEEP_GOING
            task.snoozeCount >= 3 -> InterventionStrategy.REDUCE_SCOPE
            overdue >= 180 -> InterventionStrategy.RESCHEDULE
            level == RiskLevel.HIGH && remaining >= 90 -> InterventionStrategy.SPLIT_TASK
            task.status == TaskStatus.ACTIVE -> InterventionStrategy.KEEP_GOING
            else -> InterventionStrategy.START_STEP
        }
        val step = when (strategy) {
            InterventionStrategy.REDUCE_SCOPE -> "先把目标缩小为一个能立即完成的小步骤，再开始执行。"
            InterventionStrategy.RESCHEDULE -> "这项计划已延期较久，先重新安排一个现实的开始和截止时间。"
            InterventionStrategy.SPLIT_TASK -> "目标还较长，先拆成几个小步骤，把第一步写进目标。"
            InterventionStrategy.KEEP_GOING -> if (task.progress >= 100) "进度已到100%，确认完成后点击标记完成。" else "继续当前步骤，按剩余工作量安排下一次检查。"
            else -> "先做一个最小步骤，再根据预计时长检查进度。"
        }
        val repeat = when {
            task.snoozeCount >= 3 || strategy == InterventionStrategy.RESCHEDULE -> 60L
            level == RiskLevel.HIGH -> 20L
            level == RiskLevel.MEDIUM -> 30L
            else -> 60L
        }
        return RiskAssessment(score, level, strategy, reasons, step, repeat)
    }

    fun adaptiveMinutes(task: Task): Long {
        val remaining = task.estimatedDurationMinutes * (100 - task.progress).coerceIn(0, 100) / 100
        return (remaining / 2L + task.snoozeCount.coerceAtMost(4) * 5L).coerceIn(10, 60)
    }

    fun begin(task: Task, now: Long, stillWorking: Boolean = false): Task {
        val active = task.copy(status = TaskStatus.ACTIVE, actualStartAt = task.actualStartAt ?: now, updatedAt = now,
            snoozedUntil = null, snoozedFromStatus = null)
        val next = if (stillWorking && task.actualStartAt != null) {
            val next = now + adaptiveMinutes(active) * 60_000
            active.deadlineAt?.takeIf { it > now }?.let { minOf(next, it) } ?: next
        } else calculateNextCheck(active, now)
        return active.copy(nextCheckAt = next)
    }

    fun snooze(task: Task, minutes: Int, now: Long): Task = task.copy(status = TaskStatus.SNOOZED,
        snoozeCount = task.snoozeCount + 1, nextCheckAt = now + minutes * 60_000L, snoozedUntil = now + minutes * 60_000L,
        snoozedFromStatus = if (task.status == TaskStatus.SNOOZED) task.snoozedFromStatus else task.status, updatedAt = now)

    fun scheduledCheckAt(task: Task, notificationAt: Long, now: Long): Long? {
        if (task.isTerminal) return null
        val raw = maxOf(now, task.nextCheckAt ?: return null)
        val checkAt = if (task.status == TaskStatus.SNOOZED || !check(task, raw).shouldNotify) raw else notificationAt
        // Preserve a user's snooze wake, while observing an earlier hard boundary silently.
        val deadline = task.deadlineAt?.takeIf { task.overdueEnteredAt == null }
        return deadline?.let { minOf(checkAt, maxOf(now, it)) } ?: checkAt
    }

    /** One persisted check, including silent midpoint checks; hard deadlines are independent boundaries. */
    fun calculateNextCheck(task: Task, now: Long = clock.now().toEpochMilli()): Long? {
        if (task.isTerminal) return null
        if (task.snoozedUntil?.let { it > now } == true) return task.snoozedUntil
        if (task.deadlineAt?.let { it <= now } == true) return if (task.overdueEnteredAt == null) now else now + adaptiveMinutes(task) * 60_000
        val next = if (task.actualStartAt != null || task.status == TaskStatus.ACTIVE) {
            val finish = task.expectedFinishAt ?: task.actualStartAt?.let { it + task.estimatedDurationMinutes * 60_000L } ?: return task.deadlineAt
            val anchor = task.actualStartAt ?: task.plannedStartAt ?: now
            val fractions = when (task.estimatedDurationMinutes) {
                in 30..60 -> listOf(0.5)
                in 61..120 -> listOf(0.4, 0.75)
                else -> emptyList()
            }
            val checkpoints = fractions.map { anchor + (task.estimatedDurationMinutes * 60_000L * it).toLong() }.filter { it < finish }
            if (!task.expectedFinishReached) (checkpoints + finish).filter { it > now && it > (task.lastCheckAt ?: Long.MIN_VALUE) }.minOrNull() ?: now
            else now + adaptiveMinutes(task) * 60_000
        } else {
            val start = task.plannedStartAt ?: return task.deadlineAt
            val prepare = task.prepareReminderMinutes?.let { start - it * 60_000L }
            when {
                !task.prepareReminderSent && prepare != null && prepare > now -> prepare
                !task.startReminderSent -> maxOf(now, start)
                else -> now + adaptiveMinutes(task) * 60_000
            }
        }
        return task.deadlineAt?.takeIf { it > now }?.let { minOf(next, it) } ?: next
    }

    fun check(task: Task, now: Long = clock.now().toEpochMilli()): ExecutionDecision {
        if (task.isTerminal) return ExecutionDecision(false, null, null, InterventionStrategy.NONE, null, task.copy(nextCheckAt = null))
        var current = task
        if (current.status == TaskStatus.SNOOZED || current.snoozedUntil != null) {
            if ((current.snoozedUntil ?: current.nextCheckAt ?: now) > now) {
                val hardDue = current.deadlineAt?.let { it <= now } == true
                val waiting = if (hardDue) current.copy(status = TaskStatus.OVERDUE, overdueEnteredAt = current.overdueEnteredAt ?: now) else current
                return ExecutionDecision(false, null, waiting.nextCheckAt, InterventionStrategy.NONE,
                    waiting.status.takeIf { it != task.status }, waiting)
            }
            current = current.copy(status = current.snoozedFromStatus ?: if (current.actualStartAt != null) TaskStatus.ACTIVE else TaskStatus.PLANNED,
                snoozedUntil = null, snoozedFromStatus = null)
        }
        val overdue = current.deadlineAt?.let { it <= now } == true
        val type = when {
            overdue -> NotificationType.OVERDUE_INTERVENTION
            current.actualStartAt != null || current.status == TaskStatus.ACTIVE -> {
                val finish = current.expectedFinishAt ?: current.actualStartAt?.let { it + current.estimatedDurationMinutes * 60_000L }
                if (finish?.let { now >= it } == true || current.expectedFinishReached) NotificationType.EXPECTED_FINISH_CHECK else NotificationType.PROGRESS_CHECK
            }
            current.prepareReminderMinutes != null && current.plannedStartAt != null && now < current.plannedStartAt -> NotificationType.PREPARE_REMINDER
            current.startReminderSent -> NotificationType.START_DELAYED
            else -> NotificationType.START_REMINDER
        }
        val strategy = when {
            overdue && (current.overdueCheckCount >= 3 || current.snoozeCount >= 4) -> InterventionStrategy.RESCHEDULE
            overdue && (current.overdueCheckCount >= 1 || current.snoozeCount >= 2) -> InterventionStrategy.REDUCE_SCOPE
            overdue -> InterventionStrategy.START_STEP
            current.snoozeCount >= 3 -> InterventionStrategy.REDUCE_SCOPE
            current.estimatedDurationMinutes > 120 -> InterventionStrategy.SPLIT_TASK
            current.actualStartAt != null -> InterventionStrategy.KEEP_GOING
            else -> InterventionStrategy.START_STEP
        }
        current = current.copy(status = if (overdue) TaskStatus.OVERDUE else current.status,
            prepareReminderSent = current.prepareReminderSent || type == NotificationType.PREPARE_REMINDER,
            startReminderSent = current.startReminderSent || type in setOf(NotificationType.START_REMINDER, NotificationType.START_DELAYED),
            expectedFinishReached = current.expectedFinishReached || type == NotificationType.EXPECTED_FINISH_CHECK,
            overdueEnteredAt = if (overdue) current.overdueEnteredAt ?: now else current.overdueEnteredAt,
            overdueCheckCount = current.overdueCheckCount + if (overdue) 1 else 0,
            lastCheckAt = now, updatedAt = now)
        val next = calculateNextCheck(current, now)
        return ExecutionDecision(type != NotificationType.PROGRESS_CHECK, type, next, strategy,
            current.status.takeIf { it != task.status }, current.copy(nextCheckAt = next))
    }

    fun deliveryFailed(task: Task, now: Long): Task = task.copy(nextCheckAt = now + 15 * 60_000L)

    fun message(task: Task, decision: ExecutionDecision): String = when (decision.notificationType) {
        NotificationType.PREPARE_REMINDER -> "${task.prepareReminderMinutes ?: 0}分钟后计划开始${task.title}，可以先准备所需材料。"
        NotificationType.START_REMINDER -> "现在该开始${task.title}了，预计需要${task.estimatedDurationMinutes}分钟。先做一个小步骤吧。"
        NotificationType.START_DELAYED -> "还没开始？可以先做两分钟，或重新安排时间。"
        NotificationType.PROGRESS_CHECK -> "进度检查：当前 ${task.progress}%。"
        NotificationType.EXPECTED_FINISH_CHECK -> "已到预计完成时间，完成了吗？仍在做可以按剩余工作量调整。"
        NotificationType.OVERDUE_INTERVENTION -> when (decision.suggestedStrategy) {
            InterventionStrategy.REDUCE_SCOPE -> "已超过最晚完成时间，先缩小目标，完成最重要的一步。"
            InterventionStrategy.RESCHEDULE -> "已多次延期，建议拆分任务或重新安排。"
            else -> "已超过最晚完成时间，先做一个最小步骤。"
        }
        null -> ""
    }
}

enum class NotificationType { PREPARE_REMINDER, START_REMINDER, START_DELAYED, PROGRESS_CHECK, EXPECTED_FINISH_CHECK, OVERDUE_INTERVENTION }
data class ExecutionDecision(val shouldNotify: Boolean, val notificationType: NotificationType?, val nextCheckAt: Long?,
    val suggestedStrategy: InterventionStrategy, val statusUpdate: TaskStatus?, val task: Task)

package com.executionagent.app.domain.profile

import com.executionagent.app.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class ProfileSnapshot(val tasks: List<Task>, val events: List<TaskEvent>)
interface ProfileRepository { fun observe(): Flow<ProfileSnapshot> }
data class Rate(val count: Int, val total: Int) { val fraction: Double? get() = if (total == 0) null else count.toDouble() / total }
data class CategoryProfile(val name: String, val completion: Rate, val cancelled: Int)
data class BucketProfile(val label: String, val completion: Rate)
data class DayProfile(val date: LocalDate, val completion: Rate, val cancelled: Int)
data class StrategyProfile(val strategy: String, val response: Rate)
data class InterventionProfile(val total: Int, val observed: Int, val responded: Int, val completed: Int,
    val pending: Int, val overlapping: Int, val undone: Int, val reminders: Rate, val coach: Rate, val strategies: List<StrategyProfile>)
data class ExecutionProfile(val days: Int, val start: LocalDate, val zone: String, val cohort: Int, val open: Int, val cancelled: Int,
    val completion: Rate, val onTime: Rate, val averageDelayMinutes: Double?, val missingCompletionTime: Int,
    val snoozeCount: Int, val categories: List<CategoryProfile>, val bestTime: BucketProfile?, val bestDuration: BucketProfile?,
    val trends: List<DayProfile>, val intervention: InterventionProfile)

class ExecutionProfileCalculator @Inject constructor() {
    fun calculate(snapshot: ProfileSnapshot, days: Int, now: Long, zone: ZoneId): ExecutionProfile {
        require(days == 7 || days == 30)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = today.minusDays(days.toLong() - 1)
        val from = start.atStartOfDay(zone).toInstant().toEpochMilli()
        // Tasks without a hard deadline use their expected finish only for grouping, never overdue scoring.
        fun cohortTime(task: Task) = task.deadlineAt ?: task.expectedFinishAt
        val cohort = snapshot.tasks.filter { cohortTime(it)?.let { at -> at in from..now } == true }
        val eligible = cohort.filter { it.status != TaskStatus.CANCELLED }
        val completed = eligible.filter { it.status == TaskStatus.COMPLETED }
        val timed = completed.filter { it.completedAt != null && it.completedAt in it.createdAt..now }
        val hardTimed = timed.filter { it.deadlineAt != null }
        val categories = cohort.groupBy { it.category.ifBlank { "未分类" } }.map { (name, tasks) ->
            CategoryProfile(name, rate(tasks.filter { it.status != TaskStatus.CANCELLED }), tasks.count { it.status == TaskStatus.CANCELLED })
        }.sortedWith(compareByDescending<CategoryProfile> { it.completion.total }.thenBy { it.name })
        val times = eligible.filter { it.actualStartAt != null && it.actualStartAt in it.createdAt..now }.groupBy {
            val hour = Instant.ofEpochMilli(requireNotNull(it.actualStartAt)).atZone(zone).hour
            when (hour) { in 0..5 -> "00:00–06:00"; in 6..11 -> "06:00–12:00"; in 12..17 -> "12:00–18:00"; else -> "18:00–24:00" }
        }.map { (label, tasks) -> BucketProfile(label, rate(tasks)) }
        val durations = eligible.groupBy { when (it.estimatedDurationMinutes) {
            in 1..15 -> "1–15分钟"; in 16..30 -> "16–30分钟"; in 31..60 -> "31–60分钟"; in 61..120 -> "61–120分钟"; else -> "超过120分钟"
        } }.map { (label, tasks) -> BucketProfile(label, rate(tasks)) }
        val byDate = cohort.groupBy { Instant.ofEpochMilli(requireNotNull(cohortTime(it))).atZone(zone).toLocalDate() }
        val trends = (0 until days).map { offset ->
            val date = start.plusDays(offset.toLong()); val tasks = byDate[date].orEmpty()
            DayProfile(date, rate(tasks.filter { it.status != TaskStatus.CANCELLED }), tasks.count { it.status == TaskStatus.CANCELLED })
        }
        return ExecutionProfile(days, start, zone.id, cohort.size, eligible.count { !it.isTerminal }, cohort.size - eligible.size,
            Rate(completed.size, eligible.size), Rate(hardTimed.count { requireNotNull(it.completedAt) <= requireNotNull(it.deadlineAt) }, hardTimed.size),
            hardTimed.map { (requireNotNull(it.completedAt) - requireNotNull(it.deadlineAt)).coerceAtLeast(0) / 60_000.0 }.takeIf { it.isNotEmpty() }?.average(),
            completed.size - timed.size, eligible.sumOf { it.snoozeCount }, categories, best(times), best(durations), trends,
            interventions(snapshot.events, from, now))
    }
    private fun rate(tasks: List<Task>) = Rate(tasks.count { it.status == TaskStatus.COMPLETED }, tasks.size)
    private fun best(buckets: List<BucketProfile>) = buckets.filter { it.completion.total >= 5 }
        .sortedWith(compareByDescending<BucketProfile> { it.completion.fraction }.thenByDescending { it.completion.total }.thenBy { it.label }).firstOrNull()
    private fun interventions(events: List<TaskEvent>, from: Long, now: Long): InterventionProfile {
        fun meta(event: TaskEvent, field: String): String? = runCatching {
            Json.parseToJsonElement(event.payloadJson).jsonObject[field]?.jsonPrimitive?.content
        }.getOrNull()
        val candidates = events.filter { event -> event.occurredAt in from..now &&
            (event.type == TaskEventType.REMINDER_SENT || event.type == TaskEventType.AI_INTERVENTION && meta(event, "operation") == "APPLY") }
        val byTask = events.filter { it.occurredAt <= now }.groupBy { it.taskId }
        var observed = 0; var responded = 0; var completed = 0; var pending = 0; var overlapping = 0; var undone = 0
        var reminderTotal = 0; var reminderResponse = 0; var coachTotal = 0; var coachResponse = 0
        val strategies = mutableMapOf<String, Rate>()
        for (event in candidates) {
            val own = byTask[event.taskId].orEmpty(); val end = event.occurredAt + 60 * 60_000
            val proposal = meta(event, "proposalId")
            if (proposal != null && own.any { it.type == TaskEventType.AI_INTERVENTION && meta(it, "operation") == "UNDO" && meta(it, "proposalId") == proposal && it.occurredAt >= event.occurredAt }) { undone++; continue }
            if (end > now) { pending++; continue }
            if (candidates.any { it.id != event.id && it.taskId == event.taskId && it.occurredAt in event.occurredAt..end }) { overlapping++; continue }
            observed++
            val response = own.filter { it.occurredAt > event.occurredAt && it.occurredAt <= end &&
                (it.type == TaskEventType.STARTED || it.type == TaskEventType.COMPLETED) && (proposal == null || meta(it, "proposalId") != proposal) }
            val acted = response.isNotEmpty()
            val strategy = if (event.type == TaskEventType.REMINDER_SENT) runCatching {
                Json.parseToJsonElement(event.payloadJson).jsonObject["policy"]?.jsonObject?.get("strategy")?.jsonPrimitive?.content
            }.getOrNull() ?: "UNKNOWN" else meta(event, "strategy") ?: "UNKNOWN"
            val key = (if (event.type == TaskEventType.REMINDER_SENT) "提醒 · " else "教练 · ") + strategyLabel(strategy)
            val previous = strategies[key] ?: Rate(0, 0)
            strategies[key] = Rate(previous.count + if (acted) 1 else 0, previous.total + 1)
            if (acted) responded++
            if (response.any { it.type == TaskEventType.COMPLETED }) completed++
            if (event.type == TaskEventType.REMINDER_SENT) { reminderTotal++; if (acted) reminderResponse++ }
            else { coachTotal++; if (acted) coachResponse++ }
        }
        return InterventionProfile(candidates.size, observed, responded, completed, pending, overlapping, undone,
            Rate(reminderResponse, reminderTotal), Rate(coachResponse, coachTotal), strategies.toSortedMap().map { StrategyProfile(it.key, it.value) })
    }
}
private fun strategyLabel(value: String) = when (value) {
    "NONE" -> "一般回应"; "START_STEP" -> "开始一步"; "KEEP_GOING" -> "继续执行"; "REDUCE_SCOPE" -> "缩小目标"; "SPLIT_TASK" -> "拆分任务"; "RESCHEDULE" -> "重新安排"; else -> "未记录策略"
}

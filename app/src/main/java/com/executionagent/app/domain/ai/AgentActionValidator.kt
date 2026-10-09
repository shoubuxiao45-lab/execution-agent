package com.executionagent.app.domain.ai

import com.executionagent.app.domain.model.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID
import javax.inject.Inject

@Serializable enum class AgentActionType { START_TASK, COMPLETE_TASK, SNOOZE_TASK, CHANGE_GOAL, CHANGE_DEADLINE, SPLIT_TASK, SCHEDULE_REMINDER, CANCEL_TASK }
@Serializable data class AgentAction(val type: AgentActionType, val taskId: String, val params: JsonObject)
@Serializable data class CoachReply(val reply: String, val strategy: String, val actions: List<AgentAction>)
data class ActionPlan(val after: Task, val children: List<Task>, val events: List<TaskEventType>)

class AgentActionValidator @Inject constructor() {
    fun parse(raw: String, task: Task, now: Long): CoachReply {
        require(raw.length <= 16_000) { "AI 回复过长" }
        try {
            val root = Json.parseToJsonElement(raw).jsonObject
            require(root.keys == setOf("reply", "strategy", "actions")) { "AI 回复字段无效" }
            val reply = root.string("reply", 1, 600)
            require(reply.split(Regex("[。！？!?]+" )).count { it.isNotBlank() } <= 3) { "教练回复超过三句话" }
            val strategy = root.string("strategy", 1, 40)
            require(strategy in setOf("NONE", "START_STEP", "KEEP_GOING", "REDUCE_SCOPE", "SPLIT_TASK", "RESCHEDULE")) { "未知教练策略" }
            val array = root.getValue("actions").jsonArray
            require(array.size <= 3) { "一次最多3个动作" }
            val actions = array.map { element ->
                val obj = element.jsonObject
                require(obj.keys == setOf("type", "taskId", "params")) { "动作字段无效" }
                AgentAction(AgentActionType.valueOf(obj.string("type", 1, 40)), obj.string("taskId", 1, 100), obj.getValue("params").jsonObject)
            }
            plan(actions, task, now)
            return CoachReply(reply, strategy, actions)
        } catch (invalid: IllegalArgumentException) { throw IllegalArgumentException(invalid.message ?: "AI JSON 无效") }
        catch (_: Exception) { throw IllegalArgumentException("AI JSON 类型或字段无效") }
    }
    fun plan(actions: List<AgentAction>, before: Task, now: Long): ActionPlan {
        require(actions.size <= 3) { "一次最多3个动作" }
        val execution = com.executionagent.app.domain.engine.ExecutionEngine()
        var task = before
        val children = mutableListOf<Task>()
        val events = mutableListOf<TaskEventType>()
        val used = mutableSetOf<AgentActionType>()
        for (action in actions) {
            require(action.taskId == before.id) { "动作不属于当前任务" }
            require(!task.isTerminal) { "已结束任务不能执行动作" }
            require(used.add(action.type)) { "不能重复同类动作" }
            val params = action.params
            fun keys(vararg names: String) { require(params.keys == names.toSet()) { "动作参数无效" } }
            fun future(field: String): Long {
                val value = params.integer(field)
                require(value > now && value <= now + 366L * 24 * 60 * 60_000) { "时间须在未来一年内" }
                return value
            }
            fun next(value: Long) = if (value == task.nextCheckAt) value + 1 else value
            task = when (action.type) {
                AgentActionType.START_TASK -> { keys(); events += TaskEventType.STARTED; execution.begin(task, now).let { it.copy(nextCheckAt = it.nextCheckAt?.let(::next)) } }
                AgentActionType.COMPLETE_TASK -> { keys(); events += TaskEventType.COMPLETED; task.copy(status = TaskStatus.COMPLETED, progress = 100, completedAt = now, nextCheckAt = null) }
                AgentActionType.CANCEL_TASK -> { keys(); events += TaskEventType.CANCELLED; task.copy(status = TaskStatus.CANCELLED, nextCheckAt = null) }
                AgentActionType.SNOOZE_TASK -> { keys("minutes"); val minutes = params.integer("minutes"); require(minutes in 1..1440) { "推迟须为1–1440分钟" }; events += TaskEventType.SNOOZED
                    execution.snooze(task, minutes.toInt(), now).let { it.copy(nextCheckAt = next(requireNotNull(it.nextCheckAt))) } }
                AgentActionType.CHANGE_GOAL -> { keys("goal"); events += TaskEventType.GOAL_REDUCED; task.copy(goal = params.string("goal", 1, 2000)) }
                AgentActionType.CHANGE_DEADLINE -> { keys("deadlineAt"); val deadline = future("deadlineAt"); require(task.plannedStartAt == null || deadline > requireNotNull(task.plannedStartAt)) { "截止须晚于开始" }; events += TaskEventType.RESCHEDULED
                    task.copy(deadlineAt = deadline, overdueEnteredAt = null, overdueCheckCount = 0, status = if (task.actualStartAt != null) TaskStatus.ACTIVE else TaskStatus.PLANNED).let { it.copy(nextCheckAt = execution.calculateNextCheck(it, now)) } }
                AgentActionType.SCHEDULE_REMINDER -> { keys("at"); val at = future("at"); events += TaskEventType.RESCHEDULED; task.copy(nextCheckAt = next(at), reminderMode = ReminderMode.EXPLICIT) }
                AgentActionType.SPLIT_TASK -> { keys("children"); require(children.isEmpty()) { "不能重复拆分" }; val parts = params.getValue("children").jsonArray; require(parts.size in 2..5) { "拆分须为2–5项" }
                    require(task.deadlineAt == null || requireNotNull(task.deadlineAt) > maxOf(now, task.plannedStartAt ?: now)) { "请先重新安排截止时间再拆分" }
                    parts.forEach { part ->
                        val child = part.jsonObject
                        require(child.keys == setOf("title", "goal", "estimatedDurationMinutes") || child.keys == setOf("title", "goal", "estimatedMinutes")) { "子任务字段无效" }
                        val minutes = child.integer(if ("estimatedDurationMinutes" in child) "estimatedDurationMinutes" else "estimatedMinutes"); require(minutes in 1..1440) { "子任务时长无效" }
                        children += Task(UUID.randomUUID().toString(), child.string("title", 1, 120), child.string("goal", 0, 2000), task.category,
                            maxOf(now, task.plannedStartAt ?: now), task.deadlineAt, minutes.toInt(), now, now, nextCheckAt = maxOf(now, task.plannedStartAt ?: now))
                    }
                    events += TaskEventType.TASK_SPLIT
                    // Children replace the original plan; no second open parent is counted/reminded.
                    task.copy(status = TaskStatus.CANCELLED, nextCheckAt = null)
                }
            }.copy(updatedAt = maxOf(now, task.updatedAt + 1))
        }
        return ActionPlan(task, children, events)
    }
}
private fun JsonObject.string(field: String, min: Int, max: Int): String {
    val value = getValue(field).jsonPrimitive
    require(value.isString) { "$field 须为字符串" }
    return value.content.trim().also { require(it.length in min..max && it.none { ch -> ch == '\u0000' }) { "$field 长度无效" } }
}
private fun JsonObject.integer(field: String): Long {
    val value = getValue(field).jsonPrimitive
    require(!value.isString && value.content.matches(Regex("-?[0-9]+"))) { "$field 须为整数" }
    return requireNotNull(value.longOrNull) { "$field 整数越界" }
}

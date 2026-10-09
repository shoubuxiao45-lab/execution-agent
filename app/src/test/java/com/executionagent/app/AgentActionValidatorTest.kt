package com.executionagent.app

import com.executionagent.app.domain.ai.*
import com.executionagent.app.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AgentActionValidatorTest {
    private val validator = AgentActionValidator()
    private val now = 1_000_000L
    private val task = Task("t", "电路", "完成三题", "学习", now - 1000, now + 3_600_000, 60, now - 2000, now - 2000, nextCheckAt = now + 3_600_000)
    private fun raw(type: String = "START_TASK", params: String = "{}", id: String = "t") = """{"reply":"先做第一步。","strategy":"START_STEP","actions":[{"type":"$type","taskId":"$id","params":$params}]}"""
    private fun reject(raw: String, task: Task = this.task) { assertThrows(IllegalArgumentException::class.java) { validator.parse(raw, task, now) } }
    @Test fun everyWhitelistedActionHasAPlan() {
        val cases = mapOf("START_TASK" to "{}", "COMPLETE_TASK" to "{}", "CANCEL_TASK" to "{}", "SNOOZE_TASK" to """{"minutes":20}""",
            "CHANGE_GOAL" to """{"goal":"第一题"}""", "CHANGE_DEADLINE" to """{"deadlineAt":5000000}""", "SCHEDULE_REMINDER" to """{"at":5000000}""",
            "SPLIT_TASK" to """{"children":[{"title":"a","goal":"","estimatedDurationMinutes":5},{"title":"b","goal":"","estimatedDurationMinutes":10}]}""")
        for ((type, params) in cases) assertEquals(1, validator.plan(validator.parse(raw(type, params), task, now).actions, task, now).events.size)
    }
    @Test fun rejectsUnknownActionAndCrossTask() { reject(raw("DELETE_TASK")); reject(raw(id = "other")) }
    @Test fun rejectsUnknownFieldsAndMissingFields() {
        reject(raw().replace("\"params\":{}", "\"params\":{},\"extra\":1")); reject(raw().replace("\"taskId\":\"t\",", "")); reject(raw(params = "{\"extra\":1}"))
    }
    @Test fun rejectsMalformedJsonAndWrongTypes() {
        for (value in listOf("broken", "[]", raw().replace("\"actions\":[", "\"actions\":{\"x\":["), raw().replace("\"reply\":\"先做第一步。\"", "\"reply\":1"))) reject(value)
    }
    @Test fun integersCannotBeStringsFloatsOrBooleans() { for (value in listOf("\"20\"", "20.0", "true", "null", "99999999999999999999")) reject(raw("SNOOZE_TASK", "{\"minutes\":$value}")) }
    @Test fun validatesMinutesAndTimeBounds() {
        reject(raw("SNOOZE_TASK", "{\"minutes\":0}")); reject(raw("SNOOZE_TASK", "{\"minutes\":1441}"))
        reject(raw("CHANGE_DEADLINE", "{\"deadlineAt\":$now}")); reject(raw("CHANGE_DEADLINE", "{\"deadlineAt\":999999999999}"))
        assertEquals(1000001L, validator.plan(validator.parse(raw("SCHEDULE_REMINDER", "{\"at\":1000001}"), task, now).actions, task, now).after.nextCheckAt)
    }
    @Test fun validatesStringsAndSentenceCount() {
        reject(raw("CHANGE_GOAL", "{\"goal\":\" \"}")); reject(raw("CHANGE_GOAL", "{\"goal\":\"${"a".repeat(2001)}\"}"))
        reject(raw().replace("先做第一步。", "一。二。三。四。")); reject(raw().replace("START_STEP", "UNKNOWN"))
    }
    @Test fun terminalTasksRejectActionsButAllowDiscussion() {
        reject(raw(), task.copy(status = TaskStatus.COMPLETED)); reject(raw(), task.copy(status = TaskStatus.CANCELLED))
        assertTrue(validator.parse("""{"reply":"回顾记录。","strategy":"NONE","actions":[]}""", task.copy(status = TaskStatus.COMPLETED), now).actions.isEmpty())
    }
    @Test fun entireBatchRejectsBeforeAnyWrite() {
        val valid = """{"type":"START_TASK","taskId":"t","params":{}}"""
        val invalid = """{"type":"SNOOZE_TASK","taskId":"other","params":{"minutes":20}}"""
        reject("""{"reply":"第一步。","strategy":"START_STEP","actions":[$valid,$invalid]}""")
        val duplicated = """{"reply":"第一步。","strategy":"START_STEP","actions":[$valid,$valid]}"""
        reject(duplicated)
        reject("""{"reply":"第一步。","strategy":"START_STEP","actions":[$valid,$valid,$valid,$valid]}""")
    }
    @Test fun terminalTransitionCannotBeFollowedByMutation() {
        val complete = AgentAction(AgentActionType.COMPLETE_TASK, "t", buildJsonObject {})
        val start = AgentAction(AgentActionType.START_TASK, "t", buildJsonObject {})
        assertThrows(IllegalArgumentException::class.java) { validator.plan(listOf(complete, start), task, now) }
    }
    @Test fun splitCreatesBoundedChildrenAndReplacesParent() {
        val reply = validator.parse(raw("SPLIT_TASK", """{"children":[{"title":"a","goal":"","estimatedDurationMinutes":5},{"title":"b","goal":"","estimatedDurationMinutes":10}]}"""), task, now)
        val plan = validator.plan(reply.actions, task, now)
        assertEquals(TaskStatus.CANCELLED, plan.after.status); assertNull(plan.after.nextCheckAt)
        assertEquals(2, plan.children.size); assertEquals(2, plan.children.map { it.id }.toSet().size)
        assertTrue(plan.children.all { it.deadlineAt == task.deadlineAt && it.category == task.category && it.createdAt == now })
        assertThrows(IllegalArgumentException::class.java) { validator.plan(reply.actions, task.copy(deadlineAt = now), now) }
    }
    @Test fun snoozeKeepsDeadlineAndInvalidatesConsumedSlot() {
        val reply = validator.parse(raw("SNOOZE_TASK", "{\"minutes\":20}"), task, now)
        val plan = validator.plan(reply.actions, task.copy(nextCheckAt = now + 20 * 60_000), now)
        assertEquals(task.deadlineAt, plan.after.deadlineAt); assertEquals(now + 20 * 60_000 + 1, plan.after.nextCheckAt)
    }
}

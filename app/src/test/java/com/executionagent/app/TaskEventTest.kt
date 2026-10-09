package com.executionagent.app

import com.executionagent.app.data.repository.eventPayload
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.model.TaskDraft
import com.executionagent.app.domain.model.TaskEvent
import com.executionagent.app.domain.model.TaskEventType
import com.executionagent.app.domain.usecase.TaskOperations
import com.executionagent.app.domain.engine.ExecutionEngine
import com.executionagent.app.ui.description
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class TaskEventTest {
    private val repository = MemoryTaskRepository()
    private val clock = FakeClock(Instant.parse("2026-10-06T02:00:00Z"))
    private val operations = TaskOperations(repository, clock)
    private fun draft() = TaskDraft("电路", "第一题", "学习", clock.now().toEpochMilli(), clock.now().plusSeconds(3600).toEpochMilli(), 30)
    private suspend fun id() = repository.observeTasks().first().single().id
    @Test fun taskActionsRecordOrderedEventsWithBeforeAndAfter() = runTest {
        operations.save(null, draft())
        val id = id()
        operations.start(id)
        operations.progress(id, 40)
        operations.snooze(id, 20)
        operations.complete(id)
        val events = repository.observeEvents(id).first().asReversed()
        assertEquals(listOf(TaskEventType.CREATED, TaskEventType.STARTED, TaskEventType.PROGRESS_UPDATED,
            TaskEventType.SNOOZED, TaskEventType.COMPLETED), events.map { it.type })
        val progress = Json.parseToJsonElement(events[2].payloadJson).jsonObject
        assertEquals("0", progress.getValue("before").jsonObject.getValue("progress").jsonPrimitive.content)
        assertEquals("40", progress.getValue("after").jsonObject.getValue("progress").jsonPrimitive.content)
        assertEquals(clock.now().toEpochMilli(), events.last().occurredAt)
    }
    @Test fun rescheduleKeepsExplicitPlanChangeEvent() = runTest {
        operations.save(null, draft())
        val id = id()
        clock.advance(Duration.ofMinutes(5))
        operations.save(id, draft().copy(title = "电路第二题"))
        val events = repository.observeEvents(id).first()
        assertEquals(TaskEventType.RESCHEDULED, events[0].type)
        assertEquals(TaskEventType.EDITED, events[1].type)
        assertEquals(TaskEventType.CREATED, events[2].type)
    }
    @Test fun rejectedAndRepeatedTerminalActionsDoNotInventEvents() = runTest {
        operations.save(null, draft())
        val id = id()
        operations.cancel(id)
        clock.advance(Duration.ofMinutes(1))
        operations.cancel(id)
        try { operations.complete(id); fail("Cancelled task completed") } catch (_: IllegalArgumentException) { }
        assertEquals(listOf(TaskEventType.CANCELLED, TaskEventType.CREATED), repository.observeEvents(id).first().map { it.type })
        operations.delete(id)
        assertTrue(repository.observeEvents(id).first().isEmpty())
    }
    @Test fun payloadEscapesUnicodeQuotesAndNewlinesWithoutLosingFields() {
        val text = "题目\"电路\"\n第一题"
        val task = Task("id", text, text, "", 1, 2, 30, 1, 1)
        val parsed = Json.parseToJsonElement(eventPayload(null, task, "USER")).jsonObject
        assertEquals("1", parsed.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals(text, parsed.getValue("after").jsonObject.getValue("title").jsonPrimitive.content)
    }
    @Test fun corruptEventPayloadNeverCrashesHistoryPresentation() {
        val event = TaskEvent("event", "task", TaskEventType.SNOOZED, 0, "not json")
        assertEquals("", event.description(ZoneId.of("Asia/Shanghai")))
    }
    @Test fun reminderPayloadPreservesTheRuleDecisionAtDelivery() {
        val now = clock.now().toEpochMilli()
        val task = Task("id", "电路", "两题", "", now, now, 30, now, now, snoozeCount = 3)
        val decision = ExecutionEngine(clock).assess(task)
        val json = Json.parseToJsonElement(eventPayload(task, task.copy(nextCheckAt = now + 3_600_000), "REMINDER", decision)).jsonObject
        val policy = json.getValue("policy").jsonObject
        assertEquals("REDUCE_SCOPE", policy.getValue("strategy").jsonPrimitive.content)
        assertEquals("60", policy.getValue("repeatMinutes").jsonPrimitive.content)
        assertEquals(decision.score.toString(), policy.getValue("score").jsonPrimitive.content)
    }
}

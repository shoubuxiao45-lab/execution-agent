package com.executionagent.app

import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.model.TaskDraft
import com.executionagent.app.domain.model.TaskStatus
import com.executionagent.app.domain.usecase.TaskOperations
import com.executionagent.app.domain.usecase.projectToday
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskOperationsTest {
    private val clock = FakeClock(Instant.parse("2026-10-05T15:50:00Z"))
    private val repository = MemoryTaskRepository()
    private val operations = TaskOperations(repository, clock)
    private fun draft() = TaskDraft(" 电路作业 ", " 完成两道题 ", " 学习 ", clock.now().toEpochMilli(),
        clock.now().plusSeconds(3600).toEpochMilli(), 30)
    private suspend fun onlyTask() = repository.observeTasks().first().single()
    private suspend fun rejects(block: suspend () -> Unit) {
        try { block(); throw AssertionError("Expected invalid operation") }
        catch (_: IllegalArgumentException) { /* Expected rejection. */ }
    }

    @Test fun createEditAndDeletePreserveIdentityAndExecutionFields() = runTest {
        operations.save(null, draft())
        val created = onlyTask()
        assertEquals("电路作业", created.title)
        assertEquals("完成两道题", created.goal)
        assertEquals("学习", created.category)
        assertEquals(clock.now().toEpochMilli(), created.createdAt)
        assertEquals(TaskStatus.PLANNED, created.status)
        operations.start(created.id)
        operations.progress(created.id, 40)
        val started = onlyTask()
        clock.advance(Duration.ofMinutes(5))
        operations.save(created.id, draft().copy(title = "新标题", goal = "第一题", estimatedDurationMinutes = 15))
        val edited = onlyTask()
        assertEquals(created.id, edited.id)
        assertEquals(created.createdAt, edited.createdAt)
        assertEquals(started.actualStartAt, edited.actualStartAt)
        assertEquals(40, edited.progress)
        assertEquals(TaskStatus.ACTIVE, edited.status)
        assertEquals("新标题", edited.title)
        assertEquals(clock.now().toEpochMilli(), edited.updatedAt)
        operations.delete(created.id)
        assertEquals(0, repository.observeCount().first())
    }

    @Test fun uniqueIdsAndInvalidDraftsNeverCreateRows() = runTest {
        val invalid = listOf(draft().copy(title = "  "), draft().copy(title = "a".repeat(121)),
            draft().copy(goal = "a".repeat(2001)), draft().copy(category = "a".repeat(41)),
            draft().copy(estimatedDurationMinutes = 0), draft().copy(estimatedDurationMinutes = 1441),
            draft().copy(deadlineAt = draft().plannedStartAt), draft().copy(deadlineAt = requireNotNull(draft().plannedStartAt) - 1))
        invalid.forEach { rejects { operations.save(null, it) } }
        assertEquals(0, repository.observeCount().first())
        operations.save(null, draft())
        operations.save(null, draft())
        val tasks = repository.observeTasks().first()
        assertNotEquals(tasks[0].id, tasks[1].id)
    }

    @Test fun repeatedStartAndCompletionDoNotChangeOriginalTimestamps() = runTest {
        operations.save(null, draft())
        val id = onlyTask().id
        operations.start(id)
        val actualStartAt = onlyTask().actualStartAt
        clock.advance(Duration.ofMinutes(2))
        operations.start(id)
        assertEquals(actualStartAt, onlyTask().actualStartAt)
        operations.progress(id, 100)
        assertEquals(TaskStatus.ACTIVE, onlyTask().status)
        operations.complete(id)
        val completed = onlyTask()
        assertEquals(100, completed.progress)
        assertNull(completed.nextCheckAt)
        assertEquals(clock.now().toEpochMilli(), completed.completedAt)
        clock.advance(Duration.ofMinutes(2))
        operations.complete(id)
        assertEquals(completed, onlyTask())
        rejects { operations.start(id) }
        rejects { operations.progress(id, 20) }
        rejects { operations.save(id, draft()) }
        rejects { operations.cancel(id) }
        assertEquals(completed, onlyTask())
    }

    @Test fun cancellationKeepsProgressAndRejectsCompletion() = runTest {
        operations.save(null, draft())
        val id = onlyTask().id
        operations.progress(id, 20)
        rejects { operations.progress(id, -1) }
        rejects { operations.progress(id, 101) }
        assertEquals(20, onlyTask().progress)
        operations.cancel(id)
        val cancelled = onlyTask()
        assertEquals(TaskStatus.CANCELLED, cancelled.status)
        assertEquals(20, cancelled.progress)
        assertNull(cancelled.completedAt)
        assertNull(cancelled.nextCheckAt)
        rejects { operations.complete(id) }
        operations.cancel(id)
        assertEquals(cancelled, onlyTask())
    }

    @Test fun overdueIsProjectedAtDeadlineWithoutOverwritingActiveState() = runTest {
        operations.save(null, draft())
        operations.start(onlyTask().id)
        val task = onlyTask()
        assertEquals(TaskStatus.ACTIVE, task.displayStatus(requireNotNull(task.deadlineAt) - 1))
        assertEquals(TaskStatus.OVERDUE, task.displayStatus(requireNotNull(task.deadlineAt)))
        assertEquals(TaskStatus.ACTIVE, onlyTask().status)
        operations.complete(task.id)
        assertEquals(TaskStatus.COMPLETED, onlyTask().displayStatus(requireNotNull(task.deadlineAt) + 1))
    }

    @Test fun todayIncludesOverdueAndPartitionsTomorrowInDeviceTimezone() {
        val now = clock.now().toEpochMilli() // 23:50 Asia/Shanghai
        val midnight = Instant.parse("2026-10-05T16:00:00Z").toEpochMilli()
        val overdue = Task("late", "延期", "", "", now - 1000, now, 30, now, now)
        val today = overdue.copy(id = "today", plannedStartAt = now, deadlineAt = midnight + 1000)
        val tomorrow = overdue.copy(id = "tomorrow", plannedStartAt = midnight, deadlineAt = midnight + 2000)
        val completed = overdue.copy(id = "done", status = TaskStatus.COMPLETED, completedAt = now)
        val cancelled = overdue.copy(id = "cancel", status = TaskStatus.CANCELLED)
        val projection = projectToday(listOf(tomorrow, completed, today, cancelled, overdue), now, clock.zoneId())
        assertEquals(listOf("late", "today"), projection.today.map { it.id })
        assertEquals(listOf("tomorrow"), projection.upcoming.map { it.id })
        assertEquals(2, projection.closed.size)
        assertEquals(1, projection.completedToday)
        clock.advance(Duration.ofMinutes(10))
        val next = projectToday(listOf(tomorrow, completed, today, overdue), clock.now().toEpochMilli(), clock.zoneId())
        assertTrue(next.upcoming.isEmpty())
        assertEquals(3, next.today.size)
        assertEquals(0, next.completedToday)
    }

    @Test fun missingTaskCannotBeEditedOrCompleted() = runTest {
        rejects { operations.save("missing", draft()) }
        rejects { operations.complete("missing") }
        rejects { operations.delete("missing") }
        assertEquals(0, repository.observeCount().first())
    }
}

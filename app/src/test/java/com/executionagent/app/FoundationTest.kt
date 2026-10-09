package com.executionagent.app

import com.executionagent.app.data.local.DatabaseConverters
import com.executionagent.app.domain.model.TaskEventType
import com.executionagent.app.domain.model.TaskStatus
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.usecase.ObserveTaskCount
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FoundationTest {
    @Test fun fakeClockAdvancesAcrossLocalMidnight() {
        val clock = FakeClock(Instant.parse("2026-10-05T15:50:00Z"))
        clock.advance(Duration.ofMinutes(20))
        assertEquals("2026-10-06", clock.now().atZone(clock.zoneId()).toLocalDate().toString())
    }

    @Test fun observeTaskCountPropagatesRepositoryChanges() = runTest {
        val repository = MemoryTaskRepository()
        val useCase = ObserveTaskCount(repository)
        assertEquals(0, useCase().first())
        repository.create(Task("test", "测试", "", "", 0, 1, 30, 0, 0))
        assertEquals(1, useCase().first())
    }

    @Test fun allDatabaseEnumsRoundTripWithoutLosingMeaning() {
        val converters = DatabaseConverters()
        TaskStatus.entries.forEach { assertEquals(it, converters.stringToStatus(converters.statusToString(it))) }
        TaskEventType.entries.forEach { assertEquals(it, converters.stringToEvent(converters.eventToString(it))) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun corruptStatusIsRejected() { DatabaseConverters().stringToStatus("UNKNOWN") }
}

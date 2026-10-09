package com.executionagent.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.executionagent.app.domain.model.*
import com.executionagent.app.ui.*
import com.executionagent.app.ui.theme.ExecutionAgentTheme
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class DynamicTaskUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-06T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun tag(name: String): SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(name)); return compose.onNodeWithTag(name)
    }
    @Test fun formDefaultsToPlannedStartWithoutInventingDeadlineOrPreparation() {
        var saved: TaskDraft? = null
        compose.setContent { ExecutionAgentTheme(false) { TaskEditorScreen(TaskUiState(loaded = true, now = now), null, zone, { _, draft -> saved = draft }, {}, {}) } }
        tag("task-title").performTextReplacement("电路作业"); tag("task-duration").performTextReplacement("60")
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("预计完成：10-06 21:00"))
        compose.onNodeWithText("预计完成：10-06 21:00").assertIsDisplayed()
        tag("task-save").performClick()
        compose.runOnIdle { assertEquals(now, saved?.plannedStartAt); assertEquals(60, saved?.estimatedDurationMinutes); assertNull(saved?.deadlineAt); assertNull(saved?.prepareReminderMinutes); assertEquals(ReminderMode.NORMAL, saved?.reminderMode) }
    }
    @Test fun legacyEditorPromptsForStartAndKeepsMissingTimeWhenSavingTitle() {
        val old = Task("old", "旧任务", "", "", null, now + 3600_000, 30, now, now); var saved: TaskDraft? = null
        compose.setContent { ExecutionAgentTheme(false) { TaskEditorScreen(TaskUiState(tasks = listOf(old), loaded = true, now = now), "old", zone, { _, draft -> saved = draft }, {}, {}) } }
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("旧任务没有计划时间；补充后才能按计划开始提醒。"))
        compose.onNodeWithText("旧任务没有计划时间；补充后才能按计划开始提醒。").assertIsDisplayed()
        tag("task-title").performTextReplacement("旧任务改标题"); tag("task-save").performClick()
        compose.runOnIdle { assertNull(saved?.plannedStartAt); assertEquals(old.deadlineAt, saved?.deadlineAt) }
    }
    @Test fun rescheduleNotificationRouteOpensEditor() {
        val task = Task("t", "调整验证", "", "", now, null, 30, now, now)
        compose.setContent { ExecutionAgentTheme(false) { AgentNavigation(TaskUiState(tasks = listOf(task), loaded = true, now = now), zone, false, {}, { _, _ -> }, { _, _, _ -> }, openTaskId = "t", openTaskRoute = "edit") } }
        compose.onNodeWithText("编辑任务").assertIsDisplayed()
        compose.onNodeWithTag("task-title").assertTextContains("调整验证")
    }
}

package com.executionagent.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.model.TaskDraft
import com.executionagent.app.domain.model.TaskStatus
import com.executionagent.app.ui.AgentNavigation
import com.executionagent.app.ui.TaskAction
import com.executionagent.app.ui.TaskUiState
import com.executionagent.app.ui.theme.ExecutionAgentTheme
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test

class TaskUiTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-06T02:00:00Z").toEpochMilli()
    private fun tag(name: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag(name))
        return compose.onNodeWithTag(name)
    }
    private fun text(label: String): androidx.compose.ui.test.SemanticsNodeInteraction {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(label))
        return compose.onNodeWithText(label)
    }

    private fun harness() {
        compose.setContent {
            var tasks by remember { mutableStateOf<List<Task>>(emptyList()) }
            val events = remember { Channel<Unit>(Channel.BUFFERED) }
            val flow = remember { events.receiveAsFlow() }
            val scope = rememberCoroutineScope()
            ExecutionAgentTheme(false) {
                AgentNavigation(TaskUiState(tasks = tasks, loaded = true, now = now), ZoneId.of("Asia/Shanghai"), false, {},
                    onSave = { id: String?, draft: TaskDraft ->
                        tasks = if (id == null) tasks + Task("ui-task", draft.title, draft.goal, draft.category,
                            draft.plannedStartAt, draft.deadlineAt, draft.estimatedDurationMinutes, now, now)
                        else tasks.map { if (it.id == id) it.copy(title = draft.title, goal = draft.goal,
                            category = draft.category, plannedStartAt = draft.plannedStartAt, deadlineAt = draft.deadlineAt,
                            estimatedDurationMinutes = draft.estimatedDurationMinutes) else it }
                        scope.launch { events.send(Unit) }
                    }, onAction = { id, action, value ->
                        tasks = if (action == TaskAction.DELETE) tasks.filterNot { it.id == id }
                        else tasks.map { task -> if (task.id != id) task else when (action) {
                            TaskAction.START -> task.copy(status = TaskStatus.ACTIVE, actualStartAt = now)
                            TaskAction.PROGRESS -> task.copy(progress = value)
                            TaskAction.COMPLETE -> task.copy(status = TaskStatus.COMPLETED, progress = 100, completedAt = now)
                            TaskAction.CANCEL -> task.copy(status = TaskStatus.CANCELLED)
                            TaskAction.DELETE -> task
                            TaskAction.SNOOZE -> task.copy(status = TaskStatus.SNOOZED, snoozeCount = task.snoozeCount + 1)
                        } }
                        if (action == TaskAction.DELETE) scope.launch { events.send(Unit) }
                    }, returnToToday = flow)
            }
        }
    }

    @Test fun createEditStartProgressCompleteAndDeleteThroughScreens() {
        harness()
        compose.onNodeWithText("新建任务").performClick()
        tag("task-title").performTextReplacement("电路作业")
        tag("task-goal").performTextReplacement("完成两道题")
        tag("task-save").performClick()
        compose.onNodeWithText("电路作业").assertIsDisplayed().performClick()
        text("编辑任务").performClick()
        tag("task-title").performTextReplacement("电路第一题")
        tag("task-save").performClick()
        compose.onNodeWithText("电路第一题").performClick()
        text("开始执行").performClick()
        tag("progress-slider").performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
        text("保存进度").performClick()
        text("标记完成").performClick()
        compose.onNodeWithText("确认").performClick()
        text("返回").performClick()
        compose.onNodeWithText("已结束").performClick()
        compose.onNodeWithText("电路第一题").performClick()
        text("删除任务").performClick()
        compose.onNodeWithText("任务及其关联的事件和聊天历史将永久删除。此操作不能撤销。").assertIsDisplayed()
        compose.onNodeWithText("确认").performClick()
        compose.onNodeWithText("这里还没有任务。").assertIsDisplayed()
    }

    @Test fun blankTitleIsRejectedAndUnsavedEditsRequireDiscard() {
        harness()
        compose.onNodeWithText("新建任务").performClick()
        tag("task-save").performClick()
        compose.onNodeWithText("标题须为 1–120 个字符").assertIsDisplayed()
        tag("task-title").performTextReplacement("未保存")
        text("返回").performClick()
        compose.onNodeWithText("放弃未保存的修改？").assertIsDisplayed()
        compose.onNodeWithText("放弃修改").performClick()
        compose.onNodeWithText("今日没有待执行任务，先安排一个小步骤。").assertIsDisplayed()
    }
}

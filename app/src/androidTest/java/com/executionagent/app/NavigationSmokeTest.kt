package com.executionagent.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.executionagent.app.ui.AgentNavigation
import com.executionagent.app.ui.TaskUiState
import com.executionagent.app.ui.theme.ExecutionAgentTheme
import java.time.Instant
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test

class NavigationSmokeTest {
    @get:Rule val compose = createComposeRule()
    @Test fun allDestinationsAndEditorReturnAreReachable() {
        compose.setContent {
            ExecutionAgentTheme(false) {
                AgentNavigation(TaskUiState(loaded = true, now = Instant.parse("2026-10-06T02:00:00Z").toEpochMilli()),
                    ZoneId.of("Asia/Shanghai"), false, {}, { _, _ -> }, { _, _, _ -> })
            }
        }
        compose.onNodeWithText("新建任务").performClick()
        compose.onNodeWithText("标题（必填）").assertIsDisplayed()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("今天，从一个小步骤开始").assertIsDisplayed()
        compose.onNodeWithText("Agent").performClick()
        compose.onNodeWithText("Agent Coach").assertIsDisplayed()
        compose.onNodeWithText("执行洞察").performClick()
        compose.onNodeWithText("执行记录").assertIsDisplayed()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("深色主题").assertIsDisplayed()
    }
}

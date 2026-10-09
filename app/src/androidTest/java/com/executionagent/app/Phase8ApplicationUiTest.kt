package com.executionagent.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextContains
import androidx.test.core.app.ApplicationProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import android.content.Context
import com.executionagent.app.domain.model.TaskDraft
import com.executionagent.app.reminder.ReminderDependencies
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class Phase8ApplicationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun realApplicationLoadsSettingsDiagnosticsAndInsights() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("提醒运行状态").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("重新扫描提醒").performScrollTo().performClick()
        compose.onNodeWithText("AI 教练 · 离线 Mock").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("执行洞察").performClick()
        compose.onNodeWithText("最近7天").assertExists()
    }

    @Test fun mockCoachKeepsDraftThroughRecreationAndAppliesOnlyAfterConfirmation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assumeTrue(context.packageName.endsWith(".validation"))
        val operations = EntryPointAccessors.fromApplication(context, ReminderDependencies::class.java).operations()
        val now = operations.now()
        operations.save(null, TaskDraft("Phase8 离线教练", "完成两道题", "验证", now, now + 3_600_000, 10))
        val id = operations.observeTasks().first().single { it.title == "Phase8 离线教练" }.id
        try {
            compose.onNodeWithText("Agent").performClick()
            compose.onNodeWithText("任务教练 · 离线 Mock").assertIsDisplayed()
            compose.onNodeWithText("Phase8 离线教练").performClick()
            compose.onNodeWithText("说说现在卡在哪里").performTextReplacement("缩小")
            compose.runOnIdle {
                WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                    .hide(WindowInsetsCompat.Type.ime())
            }
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("说说现在卡在哪里").assertTextContains("缩小")
            compose.onNodeWithText("发送").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("核对并应用")).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("完成两道题", operations.observeTasks().first().single { it.id == id }.goal)
            compose.onNodeWithText("核对并应用").performClick()
            compose.onNodeWithText("确认应用").performClick()
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("已应用")).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("先用5分钟完成「Phase8 离线教练」的第一个小步骤", operations.observeTasks().first().single { it.id == id }.goal)
        } finally { operations.delete(id) }
    }
}

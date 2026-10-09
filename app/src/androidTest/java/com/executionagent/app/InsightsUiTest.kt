package com.executionagent.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.executionagent.app.domain.profile.*
import com.executionagent.app.ui.*
import com.executionagent.app.ui.theme.ExecutionAgentTheme
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class InsightsUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun emptyDataShowsHonestSamplesAndBothWindowsAreReachable() {
        val now = Instant.parse("2026-10-06T12:00:00Z").toEpochMilli(); val zone = ZoneId.of("Asia/Shanghai")
        val calculator = ExecutionProfileCalculator(); val snapshot = ProfileSnapshot(emptyList(), emptyList())
        val state = mutableStateOf(InsightsState(profile = calculator.calculate(snapshot, 7, now, zone)))
        compose.setContent { ExecutionAgentTheme(false) { InsightsContent(state.value, { days -> state.value = InsightsState(days, calculator.calculate(snapshot, days, now, zone)) }, {}) } }
        compose.onNodeWithText("还没有到最晚完成或预计完成时间的任务记录。完成或延期记录积累后，这里会显示趋势。").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("最近30天").performScrollTo().performClick()
        compose.onNodeWithText("2026-09-07 起 · Asia/Shanghai").assertIsDisplayed()
    }
}

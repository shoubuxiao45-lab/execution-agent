package com.executionagent.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.executionagent.app.domain.ai.*
import com.executionagent.app.domain.model.Task
import com.executionagent.app.ui.*
import com.executionagent.app.ui.theme.ExecutionAgentTheme
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

class CoachUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun cancellationProposalRequiresConcreteConfirmationAndCanBeDismissed() {
        val task = Task("t", "作业", "三题", "学习", 1, 5000000, 30, 1, 1)
        val raw = runBlocking { MockAiProvider().reply(CoachRequest(task, "取消", 1000000)) }
        val proposal = Proposal(AgentActionValidator().parse(raw, task, 1000000), task)
        val message = ChatMessage("p", "t", "proposal", Json.encodeToString(proposal), 1000000)
        var calls = 0
        compose.setContent {
            ExecutionAgentTheme(false) { CoachContent(listOf(task), ZoneId.of("Asia/Shanghai"), CoachState("t", listOf(message)), true, {}, {}, {}, { calls++ }, {}) }
        }
        compose.onNodeWithText("核对并应用").performClick()
        compose.onNodeWithText("确认修改「作业」").assertIsDisplayed()
        assertEquals(0, calls)
        compose.onNodeWithText("保持原计划").performClick()
        assertEquals(0, calls)
        compose.onNodeWithText("核对并应用").performClick()
        compose.onNodeWithText("确认应用").performClick()
        compose.runOnIdle { assertEquals(1, calls) }
    }
}

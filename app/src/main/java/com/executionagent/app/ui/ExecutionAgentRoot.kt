package com.executionagent.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.executionagent.app.domain.model.TaskDraft
import com.executionagent.app.domain.model.TaskEvent
import androidx.compose.runtime.remember
import com.executionagent.app.domain.repository.AgentSettings
import com.executionagent.app.ui.navigation.Destination
import com.executionagent.app.ui.navigation.TASK_DETAIL_ROUTE
import com.executionagent.app.ui.theme.ExecutionAgentTheme
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

@Composable
fun ExecutionAgentRoot(openTaskId: String? = null, onTaskOpened: () -> Unit = {}, viewModel: AppViewModel = hiltViewModel(), tasks: TaskViewModel = hiltViewModel(), openTaskRoute: String? = null) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val state by tasks.state.collectAsStateWithLifecycle()
    val settingsError by viewModel.error.collectAsStateWithLifecycle()
    val settingsBusy by viewModel.busy.collectAsStateWithLifecycle()
    ExecutionAgentTheme(settings.darkTheme) {
        AgentNavigation(state, tasks.zoneId, settings.darkTheme, viewModel::setDarkTheme,
            tasks::save, tasks::act, tasks::retry, tasks::clearError, tasks.returnToToday,
            settings, viewModel::setReminderSettings, settingsBusy, settingsError, openTaskId, onTaskOpened, tasks::events,
            aiSettings = { AiSettingsPanel() }, coachContent = { id -> CoachScreen(state.tasks, tasks.zoneId, id) }, insightsContent = { InsightsScreen() }, reminderStatus = { ReminderStatusPanel() }, openTaskRoute = openTaskRoute)
    }
}

@Composable
fun AgentNavigation(
    state: TaskUiState,
    zoneId: ZoneId,
    darkTheme: Boolean,
    onDarkThemeChange: (Boolean) -> Unit,
    onSave: (String?, TaskDraft) -> Unit,
    onAction: (String, TaskAction, Int) -> Unit,
    onRetry: () -> Unit = {},
    onClearError: () -> Unit = {},
    returnToToday: Flow<Unit> = emptyFlow(),
    settings: AgentSettings = AgentSettings(darkTheme = darkTheme),
    onReminderSettings: (AgentSettings) -> Unit = {},
    settingsBusy: Boolean = false,
    settingsError: String? = null,
    openTaskId: String? = null,
    onTaskOpened: () -> Unit = {},
    observeEvents: (String) -> Flow<List<TaskEvent>> = { flowOf(emptyList()) },
    aiSettings: @Composable () -> Unit = {},
    coachContent: @Composable (String?) -> Unit = { Text("Agent Coach") },
    insightsContent: @Composable () -> Unit = { Text("执行记录") },
    reminderStatus: @Composable () -> Unit = {},
    openTaskRoute: String? = null
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val route = backStackEntry?.destination?.route
    LaunchedEffect(openTaskId, openTaskRoute, state.loaded, backStackEntry != null) {
        if (openTaskId != null && state.loaded && backStackEntry != null) {
            if (state.tasks.any { it.id == openTaskId }) navController.navigate(when (openTaskRoute) { "edit" -> "task-edit/$openTaskId"; "coach" -> "coach/$openTaskId"; else -> "$TASK_DETAIL_ROUTE/$openTaskId" }) { launchSingleTop = true }
            onTaskOpened()
        }
    }
    LaunchedEffect(returnToToday) {
        returnToToday.collect { navController.popBackStack(Destination.TODAY.route, false) }
    }
    Scaffold(bottomBar = {
        if (Destination.entries.any { it.route == route }) NavigationBar {
            Destination.entries.forEach { destination ->
                NavigationBarItem(
                    selected = route == destination.route,
                    onClick = {
                        onClearError()
                        navController.navigate(destination.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = { Text(destination.symbol) },
                    label = { Text(destination.label) }
                )
            }
        }
    }) { padding ->
        NavHost(navController, Destination.TODAY.route, Modifier.padding(padding)) {
            composable(Destination.TODAY.route) {
                TodayScreen(state, zoneId, onNew = {
                    onClearError()
                    navController.navigate("task-edit/new")
                }, onOpen = { id ->
                    onClearError()
                    navController.navigate("$TASK_DETAIL_ROUTE/$id")
                }, onRetry = onRetry)
            }
            composable("$TASK_DETAIL_ROUTE/{taskId}") { entry ->
                val id = requireNotNull(entry.arguments?.getString("taskId"))
                val history = remember(id) { observeEvents(id) }
                TaskDetailScreen(state, id, zoneId, onAction,
                    onEdit = { onClearError(); navController.navigate("task-edit/$id") },
                    onBack = { onClearError(); navController.popBackStack() }, onRetry = onRetry, history = history,
                    onCoach = { navController.navigate("coach/$id") })
            }
            composable("task-edit/{taskId}") { entry ->
                val id = requireNotNull(entry.arguments?.getString("taskId")).takeUnless { it == "new" }
                TaskEditorScreen(state, id, zoneId, onSave,
                    onBack = { onClearError(); navController.popBackStack() }, onRetry = onRetry)
            }
            composable(Destination.CHAT.route) {
                coachContent(null)
            }
            composable("coach/{taskId}") { entry ->
                coachContent(entry.arguments?.getString("taskId"))
            }
            composable(Destination.INSIGHTS.route) {
                insightsContent()
            }
            composable(Destination.SETTINGS.route) {
                SettingsScreen(settings, onDarkThemeChange, onReminderSettings, settingsBusy, settingsError, aiSettings, reminderStatus)
            }
        }
    }
}


package com.executionagent.app

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.executionagent.app.reminder.AndroidReminderScheduler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.executionagent.app.ui.ExecutionAgentRoot
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private var openTaskRoute by mutableStateOf<String?>(null)
    private var openTaskId by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openTaskId = intent.getStringExtra("taskId")
        openTaskRoute = intent.getStringExtra("taskRoute")
        setContent { ExecutionAgentRoot(openTaskId, openTaskRoute = openTaskRoute, onTaskOpened = { openTaskId = null; openTaskRoute = null }) }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openTaskId = intent.getStringExtra("taskId")
        openTaskRoute = intent.getStringExtra("taskRoute")
    }
    override fun onResume() { super.onResume(); AndroidReminderScheduler.enqueueScan(this) }
}

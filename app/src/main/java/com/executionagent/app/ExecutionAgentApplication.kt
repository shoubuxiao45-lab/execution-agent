package com.executionagent.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import com.executionagent.app.reminder.AndroidReminderScheduler

@HiltAndroidApp
class ExecutionAgentApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidReminderScheduler.recover(this)
    }
}

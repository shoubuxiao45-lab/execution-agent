package com.executionagent.app.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.executionagent.app.domain.usecase.TaskOperations
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderDependencies {
    fun engine(): ReminderEngine
    fun operations(): TaskOperations
}
class ReminderWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, ReminderDependencies::class.java)
        return try {
            val id = inputData.getString("taskId")
            if (id != null) {
                val token = inputData.getLong("expectedNext", Long.MIN_VALUE)
                try {
                    when (inputData.getString("action")) {
                        "START" -> dependencies.operations().start(id, token)
                        "SNOOZE" -> dependencies.operations().snooze(id, 10, token)
                        "CONTINUE" -> dependencies.operations().start(id, token, renewGrace = true)
                        "COMPLETE" -> dependencies.operations().complete(id, token)
                        else -> return Result.failure()
                    }
                } catch (_: IllegalArgumentException) { /* Stale/removed/ended task: ignore action. */ }
            }
            dependencies.engine().scan()
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (runAttemptCount < 3) Result.retry() else Result.failure() }
    }
}
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { AndroidReminderScheduler.enqueueScan(context) }
}
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("taskId") ?: return
        val action = intent.getStringExtra("action") ?: return
        if (action !in setOf("START", "SNOOZE", "COMPLETE", "CONTINUE")) return
        if (!intent.hasExtra("expectedNext")) return
        AndroidReminderScheduler.enqueueAction(context, id, action, intent.getLongExtra("expectedNext", Long.MIN_VALUE))
    }
}
class ReminderRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) {
            AndroidReminderScheduler.recover(context)
        }
    }
}

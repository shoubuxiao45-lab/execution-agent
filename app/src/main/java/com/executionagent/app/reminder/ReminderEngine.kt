package com.executionagent.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.executionagent.app.domain.reminder.ReminderPolicy
import com.executionagent.app.domain.reminder.ReminderRefresh
import com.executionagent.app.domain.reminder.ReminderScheduler
import com.executionagent.app.domain.reminder.ReminderRuntime
import com.executionagent.app.domain.reminder.NoReminderRuntime
import com.executionagent.app.domain.reminder.AlarmMode
import com.executionagent.app.domain.repository.SettingsRepository
import com.executionagent.app.domain.repository.TaskRepository
import com.executionagent.app.domain.time.ClockProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class ReminderEngine @Inject constructor(
    private val tasks: TaskRepository,
    private val settings: SettingsRepository,
    private val clock: ClockProvider,
    private val policy: ReminderPolicy,
    private val scheduler: ReminderScheduler,
    private val runtime: ReminderRuntime = NoReminderRuntime
) : ReminderRefresh {
    private val lock = Mutex()
    override suspend fun refresh() {
        try { reconcile(false) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { runCatching { scheduler.retryLater() } }
    }
    suspend fun scan() = reconcile(true)
    private suspend fun reconcile(deliver: Boolean) = lock.withLock {
        try {
        val config = settings.settings.first()
        val now = clock.now().toEpochMilli()
        val zone = clock.zoneId()
        if (deliver || !config.remindersEnabled) tasks.processReminders(config, now, zone)
        val count = tasks.remindersToday(now, zone)
        val earliest = tasks.observeTasks().first().mapNotNull { policy.eligibleAt(it, config, now, zone, count) }.minOrNull()
        scheduler.schedule(earliest?.let { maxOf(it, now + 1_000) })
        if (deliver) runtime.scanned(now)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { runtime.failed(); throw failure }
    }
}

@Singleton
class AndroidReminderScheduler @Inject constructor(@ApplicationContext private val context: Context, private val runtime: ReminderRuntime) : ReminderScheduler {
    override fun retryLater() { enqueueScan(context) }
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private fun alarmIntent() = PendingIntent.getBroadcast(context, 0, Intent(context, ReminderAlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    override fun schedule(at: Long?) {
        val pending = alarmIntent()
        alarms.cancel(pending)
        runtime.scheduled(null, AlarmMode.NONE)
        if (at == null) return
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                runtime.scheduled(at, AlarmMode.EXACT)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                runtime.scheduled(at, AlarmMode.INEXACT)
            }
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            runtime.scheduled(at, AlarmMode.INEXACT)
        }
    }
    companion object {
        fun recover(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork("reminder-recovery", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build())
            enqueueScan(context)
        }
        fun enqueueScan(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("reminder-scan", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ReminderWorker>().setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
        fun enqueueAction(context: Context, id: String, action: String, expected: Long) {
            val data = Data.Builder().putString("taskId", id).putString("action", action).putLong("expectedNext", expected).build()
            WorkManager.getInstance(context).enqueueUniqueWork("task-action-$id-$expected", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ReminderWorker>().setInputData(data).build())
        }
    }
}

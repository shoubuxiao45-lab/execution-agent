package com.executionagent.app.reminder

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.executionagent.app.MainActivity
import com.executionagent.app.R
import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.reminder.TaskNotifications
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidTaskNotifications @Inject constructor(@ApplicationContext private val context: Context) : TaskNotifications {
    private val manager = context.getSystemService(NotificationManager::class.java)
    init { manager.createNotificationChannel(NotificationChannel(CHANNEL, "任务执行提醒", NotificationManager.IMPORTANCE_DEFAULT)) }
    override fun available(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    override fun post(task: Task, message: String): Boolean = post(task, message, com.executionagent.app.domain.engine.NotificationType.START_REMINDER)
    override fun post(task: Task, message: String, type: com.executionagent.app.domain.engine.NotificationType): Boolean {
        if (!available()) return false
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val open = Intent(context, MainActivity::class.java).apply {
            data = "executionagent://task/${task.id}".toUri()
            putExtra("taskId", task.id)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val builder = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(task.title).setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).setOnlyAlertOnce(false)
        val active = task.actualStartAt != null
        val actions = if (active) listOf("COMPLETE" to "完成", "CONTINUE" to "仍在做") else listOf("START" to "开始执行", "SNOOZE" to "推迟10分钟")
        for ((action, label) in actions) {
            val intent = Intent(context, NotificationActionReceiver::class.java).apply {
                data = "executionagent://action/${task.id}/$action/${task.nextCheckAt}".toUri()
                putExtra("taskId", task.id); putExtra("action", action)
                putExtra("expectedNext", requireNotNull(task.nextCheckAt))
            }
            builder.addAction(0, label, PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        fun activityAction(route: String, label: String) {
            val intent = Intent(open).apply { data = "executionagent://task/${task.id}/$route".toUri(); putExtra("taskRoute", route) }
            builder.addAction(0, label, PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        activityAction("edit", if (active) "调整目标" else "重新安排")
        if (active) activityAction("coach", "聊一聊")
        return try { manager.notify(task.id, 1, builder.build()); true } catch (_: SecurityException) { false }
    }
    override fun cancel(id: String) { manager.cancel(id, 1) }
    companion object { const val CHANNEL = "execution_reminders" }
}

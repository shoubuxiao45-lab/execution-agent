package com.executionagent.app.ui

import android.Manifest
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Intent
import androidx.core.net.toUri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.executionagent.app.domain.repository.AgentSettings
import com.executionagent.app.reminder.AndroidReminderScheduler
import com.executionagent.app.reminder.AndroidTaskNotifications

@Composable
fun SettingsScreen(config: AgentSettings, onTheme: (Boolean) -> Unit, onChange: (AgentSettings) -> Unit,
    busy: Boolean, error: String?, aiSettings: @Composable () -> Unit = {}, reminderStatus: @Composable () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notifications by remember { mutableStateOf(false) }
    var exact by remember { mutableStateOf(false) }
    var platformError by remember { mutableStateOf<String?>(null) }
    val refresh = {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(AndroidTaskNotifications.CHANNEL)?.importance != android.app.NotificationManager.IMPORTANCE_NONE
        exact = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        AndroidReminderScheduler.enqueueScan(context)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    fun open(intent: Intent) {
        try { context.startActivity(intent) } catch (_: android.content.ActivityNotFoundException) { platformError = "此设备没有对应的系统设置入口" }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("设置", style = MaterialTheme.typography.headlineSmall)
        Toggle("深色主题", config.darkTheme, !busy, onTheme)
        HorizontalDivider()
        Toggle("任务提醒", config.remindersEnabled, !busy) { onChange(config.copy(remindersEnabled = it)) }
        Toggle("安静时段", config.quietEnabled, !busy) { onChange(config.copy(quietEnabled = it)) }
        if (config.quietEnabled) {
            TimeSetting("开始", config.quietStartMinute, !busy) { onChange(config.copy(quietStartMinute = it)) }
            TimeSetting("结束", config.quietEndMinute, !busy) { onChange(config.copy(quietEndMinute = it)) }
            Text("普通提醒顺延至安静时段结束；任务中明确选择指定时间提醒可照常通知。", style = MaterialTheme.typography.bodySmall)
        }
        Text("每天最多提醒 ${config.dailyLimit} 次（所有任务合计）")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onChange(config.copy(dailyLimit = config.dailyLimit - 1)) }, enabled = !busy && config.dailyLimit > 1) { Text("减少") }
            OutlinedButton(onClick = { onChange(config.copy(dailyLimit = config.dailyLimit + 1)) }, enabled = !busy && config.dailyLimit < 20) { Text("增加") }
        }
        (error ?: platformError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        HorizontalDivider()
        Text(if (notifications) "通知已允许" else "通知未允许或提醒渠道已关闭")
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(context).areNotificationsEnabled()) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
        }) { Text(if (notifications) "通知设置" else "允许通知") }
        Text(if (exact) "精确闹钟触发已允许" else "当前使用普通闹钟，提醒时间可能延迟")
        if (Build.VERSION.SDK_INT >= 31 && !exact) OutlinedButton(onClick = {
            open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
        }) { Text("允许精确闹钟") }
        Text("开始后按任务时长静默检查；预计完成时询问进度。多次推迟后调整干预策略。重启设备后恢复待提醒任务。系统省电、强行停止或厂商限制可能延迟提醒。")
        HorizontalDivider()
        reminderStatus()
        HorizontalDivider()
        aiSettings()
        Text("任务和设置保存在本机。版本 0.9.0 · 动态执行提醒")
    }
}
@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label); Switch(checked, onCheckedChange = onChange, enabled = enabled)
    }
}
@Composable
private fun TimeSetting(label: String, minute: Int, enabled: Boolean, onChange: (Int) -> Unit) {
    val context = LocalContext.current
    OutlinedButton(onClick = { TimePickerDialog(context, { _, hour, part -> onChange(hour * 60 + part) }, minute / 60, minute % 60, true).show() }, enabled = enabled) {
        Text("$label ${"%02d:%02d".format(minute / 60, minute % 60)}")
    }
}




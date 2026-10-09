package com.executionagent.app.ui

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import androidx.work.WorkInfo
import com.executionagent.app.domain.reminder.*
import com.executionagent.app.reminder.AndroidReminderScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@HiltViewModel
class ReminderStatusViewModel @Inject constructor(runtime: ReminderRuntime, @ApplicationContext private val context: Context) : ViewModel() {
    val state = runtime.state
    val recovery = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("reminder-recovery")
        .map { infos -> infos.firstOrNull { !it.state.isFinished }?.state?.label() ?: "尚未登记恢复工作" }
        .catch { emit("恢复工作状态读取失败") }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "读取中")
    fun scan() { AndroidReminderScheduler.enqueueScan(context) }
}
@Composable
fun ReminderStatusPanel(vm: ReminderStatusViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val recovery by vm.recovery.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    var error by remember { mutableStateOf<String?>(null) }
    fun time(at: Long?) = at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")) } ?: "暂无"
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("提醒运行状态", style = MaterialTheme.typography.titleMedium)
        Text("最近成功扫描：${time(state.lastScanAt)}")
        Text("下次已登记闹钟：${time(state.nextAlarmAt)} · ${when (state.alarmMode) { AlarmMode.NONE -> "无待调度"; AlarmMode.EXACT -> "精确"; AlarmMode.INEXACT -> "普通" }}")
        Text("每15分钟恢复工作：$recovery")
        Text(if (power.isIgnoringBatteryOptimizations(context.packageName)) "电池优化：本应用已豁免" else "电池优化：仍受系统优化")
        Text("这里是最近一次登记结果；开机、权限撤回或系统省电可能改变实际触发。普通闹钟和后台工作不能保证准点。强行停止后，需再次打开应用才恢复。", style = MaterialTheme.typography.bodySmall)
        (state.lastError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        OutlinedButton(onClick = vm::scan) { Text("重新扫描提醒") }
        OutlinedButton(onClick = { try { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            catch (_: android.content.ActivityNotFoundException) { error = "此设备没有电池优化设置入口" } }) { Text("查看电池优化设置") }
        Text("部分手机还需在系统应用设置允许自启动或后台运行。关闭这些限制的选择由你决定。", style = MaterialTheme.typography.bodySmall)
    }
}
private fun WorkInfo.State.label(): String = when (this) {
    WorkInfo.State.ENQUEUED -> "等待系统运行"; WorkInfo.State.RUNNING -> "正在扫描"; WorkInfo.State.BLOCKED -> "等待依赖";
    WorkInfo.State.SUCCEEDED -> "已完成"; WorkInfo.State.FAILED -> "失败"; WorkInfo.State.CANCELLED -> "已取消"
}

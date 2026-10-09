package com.executionagent.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.executionagent.app.domain.profile.*
import java.util.Locale

@Composable
fun InsightsScreen(vm: InsightsViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    InsightsContent(state, vm::window, vm::retry)
}
@Composable
fun InsightsContent(state: InsightsState, onWindow: (Int) -> Unit, onRetry: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
        item { Text("执行洞察", style = MaterialTheme.typography.headlineSmall) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.days == 7, onClick = { onWindow(7) }, label = { Text("最近7天") })
            FilterChip(selected = state.days == 30, onClick = { onWindow(30) }, label = { Text("最近30天") })
        } }
        state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error); OutlinedButton(onClick = onRetry) { Text("重读记录") } } }
        val profile = state.profile
        if (profile == null && state.error == null) item { Text("正在读取执行记录…") }
        if (profile != null) {
            item { Text("${profile.start} 起 · ${profile.zone}", style = MaterialTheme.typography.bodySmall)
                Text("统计最晚完成时间已到的任务；未设最晚时间时按预计完成时间归组：${profile.cohort}项。取消${profile.cancelled}项单独列出；剩余${profile.completion.total}项中，未结束${profile.open}项仍计入完成率分母。") }
            if (profile.cohort == 0) item { Text("还没有到最晚完成或预计完成时间的任务记录。完成或延期记录积累后，这里会显示趋势。") }
            item { Metric("完成率", profile.completion.display()) }
            item { Metric("准时率", profile.onTime.display()); Text("仅按设置了最晚完成时间且有有效完成时间的已完成任务计算；缺少完成时间${profile.missingCompletionTime}项。", style = MaterialTheme.typography.bodySmall) }
            item { Metric("平均延期", profile.averageDelayMinutes?.let { "${it.decimal()}分钟 · ${profile.onTime.total}项" } ?: "暂无有效完成时间")
                Text("已完成任务的正延期分钟均值，准时完成按0分钟。未结束任务不参与。", style = MaterialTheme.typography.bodySmall) }
            item { Metric("累计推迟", "${profile.snoozeCount}次"); Text("当前窗口有效任务的累计值；撤销恢复后的次数以当前任务记录为准。", style = MaterialTheme.typography.bodySmall) }
            item { HorizontalDivider(); Text("时段与预计时长", style = MaterialTheme.typography.titleMedium)
                Text("开始时段：${profile.bestTime?.let { "${it.label} · ${it.completion.display()}" } ?: "样本不足"}")
                Text("预计时长：${profile.bestDuration?.let { "${it.label} · ${it.completion.display()}" } ?: "样本不足"}")
                Text("每组至少5项才展示，按完成率选最高，平局优先样本多。时段按实际开始时间；时长按预计值。不是对未来成功率或最佳工作时间的预测。", style = MaterialTheme.typography.bodySmall) }
            item { HorizontalDivider(); Text("分类", style = MaterialTheme.typography.titleMedium) }
            items(profile.categories, key = { "category:${it.name}" }) { category -> Text("${category.name}：${category.completion.display()} · 取消${category.cancelled}项") }
            item { HorizontalDivider(); Text("按计划完成日的当前结果", style = MaterialTheme.typography.titleMedium) }
            items(profile.trends, key = { "day:${it.date}" }) { day ->
                Text("${day.date} · ${day.completion.display()} · 取消${day.cancelled}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { (day.completion.fraction ?: 0.0).toFloat() }, modifier = Modifier.fillMaxWidth())
            }
            item {
                HorizontalDivider(); val effect = profile.intervention
                Text("干预后记录", style = MaterialTheme.typography.titleMedium)
                Text("60分钟内开始或完成：${Rate(effect.responded, effect.observed).display()}；其中完成${effect.completed}次。")
                Text("提醒：${effect.reminders.display()}；确认后的 AI 建议：${effect.coach.display()}")
                effect.strategies.forEach { Text("${it.strategy}：${it.response.display()}${if (it.response.total < 5) " · 样本少" else ""}", style = MaterialTheme.typography.bodySmall) }
                Text("总记录${effect.total}条；观察未满${effect.pending}，重叠剔除${effect.overlapping}，已撤销剔除${effect.undone}。")
                Text("按干预时间筛选，完整观察60分钟；同一任务窗口内有其他干预则剔除，AI动作自身直接生成的开始 / 完成不计回应。只统计原任务后续回应，拆分的子任务另行统计。只描述关联，不能说明干预导致了完成。", style = MaterialTheme.typography.bodySmall)
                if (effect.observed < 5) Text("干预样本少，暂不足以比较策略。")
            }
            item { Text("截止未来、窗口以前的任务不进入任务统计；删除任务及其记录后不再计入。时区使用设备当前设置；历史日期在更改时区后重新划分。", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
@Composable private fun Metric(label: String, value: String) { Text(label, style = MaterialTheme.typography.titleSmall); Text(value, style = MaterialTheme.typography.titleLarge) }
private fun Rate.display(): String = fraction?.let { "${(it * 100).decimal()}%（$count/$total）" } ?: "暂无样本（0/0）"
private fun Double.decimal() = String.format(Locale.ROOT, "%.1f", this)

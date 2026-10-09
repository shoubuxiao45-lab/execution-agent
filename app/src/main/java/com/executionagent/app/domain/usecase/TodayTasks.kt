package com.executionagent.app.domain.usecase

import com.executionagent.app.domain.model.Task
import java.time.Instant
import java.time.ZoneId

data class TodayTasks(val today: List<Task>, val upcoming: List<Task>, val closed: List<Task>, val completedToday: Int)

fun projectToday(tasks: List<Task>, now: Long, zoneId: ZoneId): TodayTasks {
    val date = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
    val start = date.atStartOfDay(zoneId).toInstant().toEpochMilli()
    val end = date.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    val open = tasks.filterNot { it.isTerminal }
    val today = open.filter { (it.plannedStartAt ?: it.createdAt) < end || it.deadlineAt?.let { at -> at < end } == true }
        .sortedWith(compareBy<Task> { (it.deadlineAt ?: Long.MAX_VALUE) > now }.thenBy { it.deadlineAt }.thenBy { it.id })
    val upcoming = open.filter { (it.plannedStartAt ?: it.createdAt) >= end && (it.deadlineAt ?: Long.MAX_VALUE) >= end }
        .sortedWith(compareBy<Task> { it.plannedStartAt }.thenBy { it.id })
    return TodayTasks(today, upcoming, tasks.filter { it.isTerminal }.sortedByDescending { it.updatedAt },
        tasks.count { task -> task.completedAt?.let { it >= start && it < end } == true })
}

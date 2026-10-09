package com.executionagent.app.data.repository

import com.executionagent.app.domain.model.Task
import com.executionagent.app.domain.engine.RiskAssessment
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal fun eventPayload(before: Task?, after: Task, source: String, assessment: RiskAssessment? = null): String = buildJsonObject {
    put("schemaVersion", 1)
    put("source", source)
    if (before != null) put("before", before.snapshot())
    put("after", after.snapshot())
    if (assessment != null) put("policy", buildJsonObject {
        put("score", assessment.score); put("level", assessment.level.name)
        put("strategy", assessment.strategy.name); put("repeatMinutes", assessment.repeatMinutes)
        put("nextStep", assessment.nextStep)
    })
}.toString()

private fun Task.snapshot(): JsonObject = buildJsonObject {
    put("title", title); put("goal", goal); put("category", category)
    put("status", status.name); put("progress", progress); put("snoozeCount", snoozeCount)
    put("plannedStartAt", plannedStartAt); put("deadlineAt", deadlineAt); put("estimatedDurationMinutes", estimatedDurationMinutes)
    nextCheckAt?.let { put("nextCheckAt", it) }
    actualStartAt?.let { put("actualStartAt", it) }
    completedAt?.let { put("completedAt", it) }
}

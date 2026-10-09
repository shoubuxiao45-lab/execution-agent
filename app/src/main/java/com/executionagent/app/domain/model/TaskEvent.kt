package com.executionagent.app.domain.model

data class TaskEvent(val id: String, val taskId: String, val type: TaskEventType, val occurredAt: Long, val payloadJson: String)

package com.executionagent.app.ui.navigation

enum class Destination(val route: String, val label: String, val symbol: String) {
    TODAY("today", "今日", "日"),
    CHAT("chat", "Agent", "聊"),
    INSIGHTS("insights", "执行洞察", "势"),
    SETTINGS("settings", "设置", "设")
}

const val TASK_DETAIL_ROUTE = "task-detail"

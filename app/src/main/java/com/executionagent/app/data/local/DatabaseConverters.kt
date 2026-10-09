package com.executionagent.app.data.local

import androidx.room.TypeConverter
import com.executionagent.app.domain.model.TaskEventType
import com.executionagent.app.domain.model.TaskStatus

class DatabaseConverters {
    @TypeConverter fun modeToString(value: com.executionagent.app.domain.model.ReminderMode): String = value.name
    @TypeConverter fun stringToMode(value: String): com.executionagent.app.domain.model.ReminderMode = com.executionagent.app.domain.model.ReminderMode.valueOf(value)
    @TypeConverter fun statusToString(value: TaskStatus): String = value.name
    @TypeConverter fun stringToStatus(value: String): TaskStatus = TaskStatus.valueOf(value)
    @TypeConverter fun eventToString(value: TaskEventType): String = value.name
    @TypeConverter fun stringToEvent(value: String): TaskEventType = TaskEventType.valueOf(value)
}

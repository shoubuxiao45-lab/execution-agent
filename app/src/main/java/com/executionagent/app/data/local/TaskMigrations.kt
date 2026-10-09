package com.executionagent.app.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object TaskMigrations {
    val FROM_1_TO_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // Dropping a parent with foreign_keys enabled can cascade. Preserve both
            // child tables, including relative row order, inside Room's migration transaction.
            db.execSQL("CREATE TABLE migration_events AS SELECT rowid AS savedOrder, * FROM task_events")
            db.execSQL("CREATE TABLE migration_chats AS SELECT rowid AS savedOrder, * FROM conversations")
            db.execSQL("""CREATE TABLE tasks_v2 (
                id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, goal TEXT NOT NULL, category TEXT NOT NULL,
                plannedStartAt INTEGER, deadlineAt INTEGER, estimatedDurationMinutes INTEGER NOT NULL,
                createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, status TEXT NOT NULL,
                progress INTEGER NOT NULL, snoozeCount INTEGER NOT NULL, nextCheckAt INTEGER,
                actualStartAt INTEGER, completedAt INTEGER, prepareReminderMinutes INTEGER,
                reminderMode TEXT NOT NULL DEFAULT 'NORMAL', prepareReminderSent INTEGER NOT NULL DEFAULT 0,
                startReminderSent INTEGER NOT NULL DEFAULT 0, expectedFinishReached INTEGER NOT NULL DEFAULT 0,
                overdueEnteredAt INTEGER, overdueCheckCount INTEGER NOT NULL DEFAULT 0, lastCheckAt INTEGER,
                snoozedUntil INTEGER, snoozedFromStatus TEXT
            )""")
            db.execSQL("""INSERT INTO tasks_v2 (id,title,goal,category,plannedStartAt,deadlineAt,estimatedDurationMinutes,
                createdAt,updatedAt,status,progress,snoozeCount,nextCheckAt,actualStartAt,completedAt,snoozedUntil,snoozedFromStatus)
                SELECT id,title,goal,category,startAt,deadlineAt,estimatedMinutes,createdAt,updatedAt,status,progress,snoozeCount,
                  CASE WHEN status IN ('COMPLETED','CANCELLED') THEN NULL
                       WHEN status='SNOOZED' THEN nextReminderAt
                       WHEN status='ACTIVE' THEN COALESCE(startedAt,startAt) + estimatedMinutes * 60000
                       WHEN status='OVERDUE' THEN COALESCE(nextReminderAt,deadlineAt)
                       ELSE COALESCE(startAt,deadlineAt) END,
                  startedAt,completedAt,
                  CASE WHEN status='SNOOZED' THEN nextReminderAt ELSE NULL END,
                  CASE WHEN status='SNOOZED' THEN CASE WHEN startedAt IS NULL THEN 'PLANNED' ELSE 'ACTIVE' END ELSE NULL END
                FROM tasks""")
            db.execSQL("DROP TABLE tasks")
            db.execSQL("ALTER TABLE tasks_v2 RENAME TO tasks")
            db.execSQL("CREATE INDEX index_tasks_deadlineAt ON tasks(deadlineAt)")
            db.execSQL("CREATE INDEX index_tasks_status ON tasks(status)")
            db.execSQL("INSERT OR IGNORE INTO task_events(id,taskId,type,occurredAt,payloadJson) SELECT id,taskId,type,occurredAt,payloadJson FROM migration_events ORDER BY savedOrder")
            db.execSQL("INSERT OR IGNORE INTO conversations(id,taskId,role,content,createdAt) SELECT id,taskId,role,content,createdAt FROM migration_chats ORDER BY savedOrder")
            db.execSQL("DROP TABLE migration_events")
            db.execSQL("DROP TABLE migration_chats")
        }
    }
}

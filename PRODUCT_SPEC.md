# Execution Agent v1 产品规格

## 目标与交互原则

形成“制定计划 → 执行 → 发现延期 → 提醒 → 用户反馈 → 受控干预 → 完成 → 行为记录 → 更合理计划”的闭环。
Agent 简洁，通常不超过三句话；不评价人格、不羞辱、不空泛鼓励；优先给出立即可执行的小步骤。
多次延期后考虑缩小、拆分、重新规划，不无限重复催促。提醒尊重安静时间、每日上限和正在执行状态。

## v1 范围

任务创建、编辑、删除、开始、进度、完成、取消、延期、缩小目标、拆分、重新安排。
计划开始、预计时长、计算的预计完成、可选最晚完成、分类、目标。提醒围绕唯一 nextCheckAt：开始、可选准备、静默中途、预计完成询问、硬逾期干预、推迟10分钟及终态取消。
任务内聊天、受控 AI 工具、事件记录、Execution Profile。
主要页面：Today、Task Detail / Edit、Agent Chat、Execution Insights、Settings。

禁止提前实现 QQ、微信、文件识别、邮件、机器学习、云同步、社交、iOS。

## 状态与事件

状态：PLANNED、ACTIVE、SNOOZED、OVERDUE、COMPLETED、CANCELLED。
终态 COMPLETED / CANCELLED 必须取消所有提醒。Phase 1–2 明确实现状态转换规则，并用注入时钟判断时间。
事件：CREATED、EDITED、STARTED、PROGRESS_UPDATED、PROGRESS_CHECK、EXPECTED_FINISH_REACHED、OVERDUE_ENTERED、REMINDER_SENT（含 notificationType）、SNOOZED（含 minutes）、GOAL_REDUCED、TASK_SPLIT、RESCHEDULED、AI_INTERVENTION、COMPLETED、CANCELLED。
Phase 3 实现任务修改与事件写入的事务一致性。

## AI 契约

仅通过 AiProvider，由 MockAiProvider 与 KimiProvider 实现；业务禁止依赖具体 Provider。
配置 AI_BASE_URL、AI_API_KEY、AI_MODEL。无 Key 时 Mock 正常运行，禁止提交真实 Key。

```json
{"reply":"先做第一题，做完再决定下一步。","strategy":"REDUCE_SCOPE","actions":[]}
```

Action 白名单：START_TASK、COMPLETE_TASK、SNOOZE_TASK、CHANGE_GOAL、CHANGE_DEADLINE、SPLIT_TASK、SCHEDULE_REMINDER、CANCEL_TASK。
AgentActionValidator 在所有执行之前校验 JSON、类型、任务归属、状态、时间、数量、长度与参数边界；拒绝未知或无效动作。
合法动作进入 UseCase，AI 不接触 DAO 或 Room。对取消、修改截止时间等明显改变计划的动作展示可核对的内容并支持撤销；具体交互在 Phase 6 实现。

## 执行引擎与洞察

ExecutionEngine：规则 Risk Score、提醒 / 干预策略；第一版不使用机器学习。
ReminderEngine：WorkManager 持久恢复 + AlarmManager 时间调度；安静时段、每日上限、重复 / 去重、正在执行、终态取消。
不承诺 Android 任意电量与厂商限制下准时；Phase 8 展示权限和调度实际能力。

Execution Profile：Completion Rate、On Time Rate、Average Delay、Snooze Count、Best Time Range、Best Task Duration、Category Statistics、Intervention Effectiveness。
Phase 7 明确分母、未结束任务 / 取消任务处理、时区、样本量、7/30 天窗口和干预后观察窗口；统计关联不能当作因果。

## 分阶段验收

| 阶段 | 内容 | 状态 |
|---|---|---|
| 0 | 工程、Compose、Navigation、Room、Hilt、DataStore、Theme、ClockProvider | 已完成，用户报告实机验收 |
| 1 | Task CRUD、Today | 已实现，验证见 PHASE_1_REPORT.md |
| 2 | ReminderEngine、Notification、Snooze、正在执行、终态取消 | 已实现，验证见 PHASE_2_REPORT.md |
| 3 | TaskEvent 与任务操作事务 | 已实现，验证见 PHASE_3_REPORT.md |
| 4 | ExecutionEngine、规则风险等级 | 已实现，验证见 PHASE_4_REPORT.md |
| 5 | AiProvider、Mock、Kimi、无 Key 与 API 失败 | 已实现，验证见 PHASE_5_REPORT.md |
| 6 | Coach、JSON、Action、Validator | 已实现，验证见 PHASE_6_REPORT.md |
| 7 | ExecutionProfile、Insights | 已实现，验证见 PHASE_7_REPORT.md |
| 8 | 重启、后台、无网、错误恢复、集中测试 | 已实现，验证见 PHASE_8_REPORT.md |

每阶段运行 Gradle build 和 test，修复 warning/error，报告变更文件、能力、验证限制；编译失败不能进入下一阶段。
本次按用户最新要求完成至 Phase 8。到期未完成、完成后通知取消、Snooze / 多次 Snooze、风险等级变化、API 失败、非法 JSON / Action、无 Key与Mock分别通过领域、持久化及设备用例验证。后台 / 重启 / 无网的具体平台和证据，以及仍需用户验收的厂商限制，见 Phase 8 报告；不将模拟器结果视为所有手机的保证。






## 动态提醒更新

当前版本0.9.0，Room v2，显式保留数据迁移。deadline 不是主要提醒时间。预计完成时 ACTIVE 继续保留；真正硬截止才 OVERDUE。普通通知遵守安静时段，用户明确选择指定时间可例外，仍受每日合计上限；静默检查不占通知次数。完整规则与验证见 ARCHITECTURE.md 和 DYNAMIC_REMINDERS_REPORT.md。此次仅修改时间模型和提醒链路，不扩大阶段范围或增加新的 AI/聊天平台能力。

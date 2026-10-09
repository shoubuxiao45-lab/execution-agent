# Execution Agent 架构（动态提醒更新）

## 分层

单 app module，Kotlin、Compose、Room、DataStore、Hilt。UI→ViewModel→TaskOperations / ExecutionEngine→Repository。ClockProvider 提供时间和设备时区。
ExecutionEngine 是纯规则：决定阶段、是否通知、策略、状态更新、唯一下一次检查。ReminderEngine 负责可靠调度；通知和接收器仅展示、分发命令。

## 时间和数据库

Task 的 plannedStartAt、deadlineAt 均可为空；estimatedDurationMinutes 为1–1440分钟；expectedFinishAt 从真实计划开始和预计时长计算，不写入独立时间列。actualStartAt 首次开始写入且保留。nextCheckAt 为唯一持久检查槽。
准备分钟数、提醒模式、已发送准备/开始标记、预计完成到达标记、逾期进入时间/干预次数、最后检查、推迟截止/原状态持久化，进程重启不会丢失阶段。
execution_agent.db 从 Room v1 升到 v2；保留四表和原数据库名。TaskMigrations.FROM_1_TO_2 重建 tasks，先保护事件/聊天及插入顺序，再恢复关联；配额表不变。旧 startAt 保留到 plannedStartAt；缺失保持为空。终态检查清空，旧推迟时间保留，进行中先检查预计结束，不生成过去的一串提醒。无 destructive migration。
序列化 Proposal 中的 Task 沿用旧 JSON 字段名，新增字段有默认值；事件界面同时读取旧/新时间键。旧待确认提案若与迁移后的任务不同，原有并发保护要求重新生成，不能覆盖最新状态。

## 核心执行规则

- PLANNED：默认 nextCheckAt=plannedStartAt；配置准备提醒且仍在准备时间之前时，先检查开始前5/10/15分钟。准备不改变状态、不增加逾期干预次数。发送开始提醒后，无回应的下一次检查按剩余工作量和推迟次数动态计算。
- START：状态 ACTIVE，首次记录 actualStartAt，写 STARTED，取消旧通知，重新计算检查。
- ACTIVE：小于30分钟无中途检查；30–60分钟50%一次；61–120分钟40%和75%；更长任务只设预计结束检查并建议拆分。检查以实际开始为进度锚点，但不越过计划预计结束或硬截止。中途默认静默、记录 PROGRESS_CHECK，不占通知配额。
- EXPECTED_FINISH：状态继续 ACTIVE，记录 EXPECTED_FINISH_REACHED，询问进度。没有硬截止时，计划超时与硬逾期明确分开。仍在做按剩余预计工作量的一半加推迟修正安排10–60分钟后的检查，硬截止在未来时取更早边界。
- DEADLINE：只要未结束且达到真正 deadlineAt，进入 OVERDUE、仅首次写 OVERDUE_ENTERED。首次给开始小步骤建议；后续缩小目标；多次推迟或逾期干预后重新安排/拆分。通知失败不增加干预次数。
- SNOOZE：默认操作10分钟，计数+1、保存原状态及 snoozedUntil，事件记录 minutes。时间到恢复 PLANNED/ACTIVE；硬截止已到则 OVERDUE。推迟中遇到硬截止先静默切换，保留原推迟唤醒时间，不提前通知。
- COMPLETED/CANCELLED：nextCheckAt=null，通知撤销，全局无待检查时取消闹钟。100%进度仍需明确完成。

ExecutionDecision 包含 shouldNotify、notificationType、nextCheckAt、suggestedStrategy、statusUpdate 和要保存的 Task。TaskOperations 和已确认的教练动作复用 begin/snooze/calculateNextCheck；没有新增 AI 能力。
文字编辑保留执行阶段和推迟；时间配置改变才重新计划。开始/推迟/调整及撤销沿用提醒槽校验，撞槽时增加1毫秒使排队的旧操作失效。

## 调度和通知约束

ReminderPolicy.notificationAt 是唯一的安静时段/配额调整入口。普通通知延到安静结束；EXPLICIT 表示用户明确选择按指定时间提醒，可经过安静时段；所有模式受每日合计上限约束。
eligibleAt 允许静默进度、推迟恢复和硬截止状态检查准时执行，通知依然经过 notificationAt。受限通知把调整后的时间持久化；权限/渠道失败由执行引擎提供15分钟退避，不虚记发送事件或配额。
RoomTaskRepository 在事务中读取最新任务，执行 Decision 并记录轨迹。REMINDER_SENT 保存 notificationType 和真实采用策略。每日计数只增加成功提交给系统的通知；不能将系统接受等同于用户看到。
AlarmManager 使用一个全局 PendingIntent，仅登记各任务当前下一检查中最早者；有权限时精确，否则普通闹钟。Mutex/Room事务避免重复扫描；WorkManager 的单一周期恢复与一次性工作处理重启、升级、时间/时区改变。受强行停止或厂商电池限制时仍有平台延迟。
通知阶段分别展示准备/开始/延迟开始/预计完成/逾期文字。开始阶段提供开始、推迟10分钟、重新安排；进行中提供完成、仍在做、调整目标及聊一聊入口，标准 Android 通知栏通常仅展示前三个动作，其余可从点开后的详情进入。MainActivity 路由到编辑或任务教练；BroadcastReceiver 无时间策略。

## 其他模块兼容

Coach 的整批校验、确认、事务提交、并发保护、撤销及离线/真实 Provider 边界保持；时间动作调用新引擎，明确安排的提醒不再要求晚于截止。子任务保留可选硬截止、计划开始提醒；兼容旧子任务 estimatedMinutes 参数。
Profile 按硬截止日归组，无硬截止按预计完成日归组；准时率/平均硬延期仅统计具有硬截止的有效已完成任务，避免把计划超时算成 DDL 违约。其他统计、观察窗口和样本要求保留。
DataStore、Keystore、草稿恢复和运行诊断保留。源码包不含本机配置、密钥、签名私钥、数据库或缓存；原签名缓存用于覆盖安装。

## 证据

当前构建、单元测试、Android迁移/动态轨迹/页面测试和后台恢复结果见 DYNAMIC_REMINDERS_REPORT.md。PHASE_*_REPORT.md 为历史交付证据。设备验证使用 com.executionagent.app.validation 隔离包；不会向用户正常包写入样本。真实 Kimi、当前版 OPPO 长时间息屏仍须另行验收。

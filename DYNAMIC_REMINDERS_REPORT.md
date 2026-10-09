# 动态执行提醒重构报告

日期：2026-10-06。版本：0.9.0-dynamic-reminders，versionCode 10。
本次聚焦时间模型和动态提醒，延续 Phase 8；没有开发 QQ、微信或新的 AI 能力。

## 交付

- 上级目录 execution-agent-dynamic-reminders.apk：原包名 com.executionagent.app、原签名，可覆盖安装；请勿卸载旧版或清除数据。
- execution-agent-dynamic-reminders-source.zip：工程、测试、v1/v2 schema、显式迁移及当前文档；排除数据库、本机 SDK 配置、密钥和签名私钥。
- dynamic-reminders-validation.json、dynamic-reminders-build.log、dynamic-reminders-android-tests.log、dynamic-reminders-runtime.json：构建和运行证据。
- APK SHA256：`d8399bf5d0d6b62e42d4dcca414ea024e9c9b78b55116c6ef2e5c157dcaa1fe9`。

## 已修正的行为

主提醒由计划开始驱动，真正 deadline 只作为逾期策略切换点。以20:00开始、预计60分钟、最晚22:00为例：

| 时间或操作 | 行为 |
|---|---|
| 创建任务 | nextCheckAt=20:00，准备提醒默认关闭 |
| 20:00 | START_REMINDER，提供开始、推迟10分钟、重新安排 |
| 点击开始 | ACTIVE，首次写 actualStartAt 和 STARTED；下一检查约20:30 |
| 约20:30 | 静默 PROGRESS_CHECK，不发通知、不占每日配额，下一检查21:00 |
| 21:00未完成 | EXPECTED_FINISH_CHECK，仍为 ACTIVE，可完成、仍在做、调整目标、聊一聊 |
| 22:00未完成 | OVERDUE，首次记录 OVERDUE_ENTERED；根据阶段给最小步骤/缩小/重新安排建议 |
| 完成或取消 | nextCheckAt=null，撤销通知，重新计算全局闹钟；无其他任务时取消闹钟 |

上表通知以安静时段允许投递为前提。默认22:00–08:00安静时段下，22:00仍记录逾期状态，普通通知延至08:00。明确选择“按指定时间提醒”则可在安静时段通知，仍受每日总上限。
无 deadlineAt 的任务在预计完成以后只有计划超时，不伪造硬截止、不标成 OVERDUE。

## 数据结构和迁移

- plannedStartAt：计划开始，可空，保留旧任务真实 startAt。
- estimatedDurationMinutes：预计时长，1–1440分钟。
- expectedFinishAt：由 plannedStartAt+预计时长计算；没有计划开始就为空。
- deadlineAt：可空的真正最晚完成时间。
- nextCheckAt：唯一持久下一检查槽；actualStartAt：首次实际开始。
- 持久化准备分钟数/模式、已发送准备/开始标记、预计完成标记、逾期进入时间/实际干预次数、最后检查、推迟唤醒时间/原状态。

Room v1→v2 的 FROM_1_TO_2 显式迁移保留全部任务和执行信息；重建任务表前保护外键关联事件、聊天及同时间插入顺序，迁移后恢复关联；daily_stats 和 DataStore 不清空。
已完成/取消任务清空下一检查；旧推迟时间继续保留；旧进行中任务先保留合理结束检查。缺失计划开始仍为空，界面提示补充；只有旧 deadline 的任务先保留硬截止检查，不编造开始时间。
旧事件 JSON 时间键和 Proposal 中的旧 Task 字段仍可读取。迁移改变了下一检查时，旧提案若与最新任务不同，原有并发保护会拒绝覆盖，需重新生成；旧历史不删除。
v1 identityHash 保留 `672f5ac6b6a4fcdf6071a2fd016a4411`；v2为 `6e26d49bd826c87ad6989da15755dd0b`。未使用破坏性迁移。

## 状态和统一时间计算

ExecutionEngine 返回 ExecutionDecision：shouldNotify、notificationType、nextCheckAt、suggestedStrategy、statusUpdate 和要持久化的任务。
calculateNextCheck/begin/snooze/scheduledCheckAt 统一决定执行时间；ReminderPolicy 仅统一安静时段/配额调整；Repository 执行决策及事务记录；ReminderEngine 保留可靠扫描、单一全局闹钟和 WorkManager 恢复；通知/接收器没有动态策略。

| 状态或阶段 | 下一检查 |
|---|---|
| PLANNED | 计划开始；启用准备提醒且准备时间尚未过去时先提前5/10/15分钟一次 |
| 已发送开始但未响应 | 剩余预计工作量与推迟次数计算10–60分钟后的检查 |
| ACTIVE，少于30分钟 | 预计完成，无中途检查 |
| ACTIVE，30–60分钟 | 实际开始后约50%进度点静默检查，再检查预计完成 |
| ACTIVE，61–120分钟 | 约40%和75%点静默检查，再检查预计完成 |
| 超过120分钟 | 不增加大量检查点；计划页面提示拆分，执行策略倾向拆分 |
| 预计完成后仍在做 | 按剩余估计工作量一半，加累计推迟修正，限制10–60分钟；未来硬截止取更早边界 |
| OVERDUE | 动态10–60分钟；首次最小步骤，后续缩小目标，多次干预/推迟后重新安排 |
| SNOOZED | 保存 now+10分钟唤醒；恢复原执行阶段，已达硬截止则 OVERDUE |
| COMPLETED/CANCELLED | null，取消提醒 |

检查进度点不晚于计划预计完成/硬截止，不一次登记多个未来闹钟。全局只登记当前最早检查。
推迟期间若硬截止更早，执行引擎选择一次静默边界检查，记录 OVERDUE，但保留用户的推迟唤醒时间，不提前通知。安静时段同样不会吞掉状态转换。
“仍在做”保留首次实际开始；文字编辑保留推迟/阶段，时间配置变化才重新计划。提醒槽撞同一时刻时增加1毫秒，防止旧通知操作重放。

## 通知、页面和执行轨迹

区分 PREPARE_REMINDER、START_REMINDER、START_DELAYED、PROGRESS_CHECK、EXPECTED_FINISH_CHECK、OVERDUE_INTERVENTION。进度阶段默认静默；准备阶段不增加高强度逾期次数。
开始通知有开始、推迟10分钟、重新安排。进行中有完成、仍在做、调整目标和聊天入口；系统通知栏可能只展示部分动作，点开详情仍可进入“讨论下一步”。编辑通知路由已验证；聊天入口沿用任务教练导航。修复了导航图初始化之前跳转的问题。
创建页以计划开始和预计时长为主，自动显示预计完成；硬截止可选，准备关闭/5/10/15分钟，提醒模式明确解释安静时段例外。
详情显示计划、实际开始、预计时长/预计完成、可选最晚完成、下一检查、计划超时及事件轨迹。
REMINDER_SENT 的 payload 保存 notificationType、真实策略和下一间隔；SNOOZED 保存 minutes；另有 PROGRESS_CHECK、EXPECTED_FINISH_REACHED、OVERDUE_ENTERED。旧历史兼容读取。
通知受权限/渠道阻止时15分钟退避，不虚记 REMINDER_SENT、不占配额、不增加实际逾期干预计数。成功提交系统不等于用户已经看到。

## 验证结果

最终 Gradle build/test/验证包构建通过；Kotlin warnings 作为错误；Lint 0 errors、0 warnings（另有一项 Compose 整数状态的性能风格信息，不影响构建）。

| 检查 | 结果 |
|---|---|
| Debug 单元测试 | 124项通过 |
| Release 单元测试 | 124项通过 |
| 隔离 Validation 单元测试 | 124项通过，同一套124个独立用例 |
| Android15/API35 完整设备回归 | 35/35通过，独立包 com.executionagent.app.validation |
| 真实 Room v1→v2 迁移 | 两项通过，核验任务/历史/聊天/配额/外键/删除关联及缺失开始时间 |
| 真实 Repository 动态轨迹 | 开始→静默中途→预计完成→逾期→推迟→完成，及安静/权限/推迟跨硬截止边界通过 |
| 原签名、包名、版本、16KiB APK对齐 | 由交付打包脚本验证 |

新增 DynamicReminderTest 的25项单元测试覆盖用户指定的12项要求，并扩展了90分钟两点、长任务、准备只提醒一次、无硬截止计划超时、推迟恢复ACTIVE、跨截止静默转换、剩余时长/硬边界、“只改标题”保留状态、旧 Proposal JSON、无截止统计等。
新增 Android 用例9项：DynamicMigrationTest 2、DynamicDeliveryTest 4、DynamicTaskUiTest 3；原26项回归保留并调整旧的截止主导预期。
后台真实 WorkManager 恢复测试考虑原有30秒重试退避，等待最多45秒，继续要求实际工作成功，未用假 Worker 替换。

| 额外运行场景（当前构建，项目独立模拟器） | 结果 |
|---|---|
| 后台开始提醒 | 通过 |
| 覆盖安装后恢复 | 通过 |
| 模拟器重启/解锁后恢复 | 通过 |
| 强行停止时不投递 | 通过 |
| 再次打开后恢复 | 通过 |
| 实际深度 Doze 下投递 | 通过 |
| 无精确权限时普通闹钟登记 | 通过 |
| 关闭网络时原有离线流程可用 | 通过 |

后台/重启/升级/Doze 场景使用未来计划开始、至少一小时后的真正硬截止，实测系统通知及 START_REMINDER 事件，从而证明第一通知不再等硬截止。Doze 已确认进入 IDLE；测试结束恢复模拟器网络、电池和原 Doze 设置。

## 尚未覆盖或平台边界

- 当前 ADB 未连接 OPPO 实机，所以本更新未在手机覆盖安装/复测；先前 Phase8 实机验收不能替代本版验收。现在提供原签名更新 APK，保留原数据迁移。
- 没有承诺 OPPO 长时间息屏、极端省电或任意厂商环境准点。无精确权限场景本次证明普通闹钟登记；没有把它当作秒级准时投递证明。强行停止后需再次打开。
- 标准系统通知栏是否完整显示四个操作由系统界面决定；详情保留全部入口。
- 缺少计划开始的旧任务必须由用户补充，才可形成开始/预计完成链路。很晚才点击开始时，计划预计完成时间仍按原计划计算；可点击“仍在做”或编辑计划。
- 任务/事件事务与 Android 通知侧效应无法跨进程原子提交，进程恰好终止时仍依靠持久恢复/去重；这是既有平台边界。
- 真实 Kimi、QQ/微信、云同步及其他新功能未开发或调用。

## 修改文件

另修改 app/build.gradle.kts（versionCode、验证资产）、生成 app/schemas/.../2.json、ARCHITECTURE.md、PRODUCT_SPEC.md、README.md；新增本报告和 tools/package-dynamic-reminders.ps1。保留 schema 1.json 及历史阶段报告。

下面列出本次改动的 Kotlin 文件，测试中的时间字段引用也同步更新：

- `app/src/androidTest/java/com/executionagent/app/DynamicDeliveryTest.kt`
- `app/src/androidTest/java/com/executionagent/app/DynamicMigrationTest.kt`
- `app/src/androidTest/java/com/executionagent/app/DynamicTaskUiTest.kt`
- `app/src/androidTest/java/com/executionagent/app/EventTransactionTest.kt`
- `app/src/androidTest/java/com/executionagent/app/InsightsUiTest.kt`
- `app/src/androidTest/java/com/executionagent/app/Phase8RecoveryFixture.kt`
- `app/src/androidTest/java/com/executionagent/app/Phase8RuntimeTest.kt`
- `app/src/androidTest/java/com/executionagent/app/ProfilePersistenceTest.kt`
- `app/src/androidTest/java/com/executionagent/app/ReminderDeliveryTest.kt`
- `app/src/androidTest/java/com/executionagent/app/SnoozeRecoveryTest.kt`
- `app/src/androidTest/java/com/executionagent/app/TaskUiTest.kt`
- `app/src/main/java/com/executionagent/app/MainActivity.kt`
- `app/src/main/java/com/executionagent/app/data/local/DatabaseConverters.kt`
- `app/src/main/java/com/executionagent/app/data/local/Entities.kt`
- `app/src/main/java/com/executionagent/app/data/local/ExecutionDatabase.kt`
- `app/src/main/java/com/executionagent/app/data/local/TaskMigrations.kt`
- `app/src/main/java/com/executionagent/app/data/repository/EventPayload.kt`
- `app/src/main/java/com/executionagent/app/data/repository/RoomTaskRepository.kt`
- `app/src/main/java/com/executionagent/app/di/AppModule.kt`
- `app/src/main/java/com/executionagent/app/domain/ai/AgentActionValidator.kt`
- `app/src/main/java/com/executionagent/app/domain/ai/AiProvider.kt`
- `app/src/main/java/com/executionagent/app/domain/ai/CoachOperations.kt`
- `app/src/main/java/com/executionagent/app/domain/engine/ExecutionEngine.kt`
- `app/src/main/java/com/executionagent/app/domain/model/Task.kt`
- `app/src/main/java/com/executionagent/app/domain/model/TaskStatus.kt`
- `app/src/main/java/com/executionagent/app/domain/profile/ExecutionProfile.kt`
- `app/src/main/java/com/executionagent/app/domain/reminder/ReminderPolicy.kt`
- `app/src/main/java/com/executionagent/app/domain/usecase/TaskOperations.kt`
- `app/src/main/java/com/executionagent/app/domain/usecase/TodayTasks.kt`
- `app/src/main/java/com/executionagent/app/reminder/AndroidTaskNotifications.kt`
- `app/src/main/java/com/executionagent/app/reminder/ReminderWorker.kt`
- `app/src/main/java/com/executionagent/app/ui/CoachScreen.kt`
- `app/src/main/java/com/executionagent/app/ui/ExecutionAgentRoot.kt`
- `app/src/main/java/com/executionagent/app/ui/InsightsScreen.kt`
- `app/src/main/java/com/executionagent/app/ui/SettingsScreen.kt`
- `app/src/main/java/com/executionagent/app/ui/TaskEventPresentation.kt`
- `app/src/main/java/com/executionagent/app/ui/TaskScreens.kt`
- `app/src/test/java/com/executionagent/app/AgentActionValidatorTest.kt`
- `app/src/test/java/com/executionagent/app/CoachOperationsTest.kt`
- `app/src/test/java/com/executionagent/app/DynamicReminderTest.kt`
- `app/src/test/java/com/executionagent/app/ExecutionEngineTest.kt`
- `app/src/test/java/com/executionagent/app/ExecutionProfileTest.kt`
- `app/src/test/java/com/executionagent/app/MemoryTaskRepository.kt`
- `app/src/test/java/com/executionagent/app/ReminderEngineTest.kt`
- `app/src/test/java/com/executionagent/app/ReminderPolicyTest.kt`
- `app/src/test/java/com/executionagent/app/TaskEventTest.kt`
- `app/src/test/java/com/executionagent/app/TaskOperationsTest.kt`

# Phase 4 交付与验收报告

日期：2026-10-06（Asia/Shanghai）。当前版本 0.4.0-phase4，versionCode 5，完成至 Phase 4。
基于 Phase 1 继续完成 Phase 2、3、4，各阶段检查通过后才进入下一阶段。保留旧 APK 及源码包。

## 本次能力

Phase 2：AlarmManager + WorkManager、到期 / 再次提醒、通知正文任务入口、正在做 / 推迟 / 完成按钮、Snooze次数、45分钟执行暂停、终态 / 删除取消通知、安静时段及全局每日提醒上限、通知 / 精确闹钟权限入口、重启 / 升级 / 时间与时区变化后恢复调度。

Phase 3：CREATED / EDITED / STARTED / PROGRESS_UPDATED / SNOOZED / RESCHEDULED / COMPLETED / CANCELLED 与任务修改同 Room 事务保存；REMINDER_SENT 与下一提醒及每日配额同事务保存；详情展示最近100条历史及安全的摘要。旧数据不清空，旧操作不补造历史。

Phase 4：ExecutionEngine 纯规则 Risk Score，0–100，低 / 中 / 高；Today、详情展示分数、原因和建议。剩余时间、进度、开始状态及Snooze次数共同决定策略与通知文案；低 / 中 / 高风险的重复间隔为60 / 30 / 20分钟，3次以上推迟或重新安排策略使用60分钟。规则决策快照保存在提醒事件中。

策略包括开始小步骤、继续执行、缩小目标、拆分、重新安排。**缩小 / 拆分 / 重新安排是建议，由用户编辑计划；本阶段不自动改写目标、创建子任务或调用 AI。** AI Phase 5–6、Execution Profile Phase 7 保持待开发。
进度100%优先提醒明确标记完成，不再建议缩小或重新安排已经做完的目标。

## 规则说明

| 条件 | 分值 |
|---|---:|
| 已到 / 超过截止 | +30 |
| 延期至少60分钟 | 再 +15 |
| 延期至少180分钟 | 再 +15 |
| 剩余预计耗时已接近 / 超过可用时间 | +20 |
| 计划开始已到，但未开始 | +10 |
| 每次Snooze | +10，最多 +30 |

封顶100。低风险0–29，中风险30–59，高风险60–100；终态0分。
分数是可解释启发式判断，不是完成概率或机器学习结果。时间与风险约30秒刷新，数据库修改通过Flow即时刷新。

## 实际验证

| 检查 | 结果 |
|---|---|
| Phase 2 build / test / 设备测试编译 | 通过，Debug/Release各26项单元测试，52秒 |
| Phase 3 build / test / 设备测试编译 | 通过，Debug/Release各31项单元测试，64秒 |
| Phase 4 最终 build / test / 设备测试编译 | BUILD SUCCESSFUL，44秒 |
| Phase 4 Debug单元测试 | 43项，0失败，0错误 |
| Phase 4 Release单元测试 | 43项，0失败，0错误 |
| Kotlin / Room / Hilt | 编译通过，warning仍按error处理 |
| 最终 Android Lint | No issues found |
| 设备测试 APK | 编译通过，共13个测试案例 |
| APK签名 / 对齐 | apksigner verify及zipalign -c -P 16 4通过 |
| 覆盖兼容检查 | 新旧包名 / 证书一致，versionCode升到5 |
| Room数据库 | v1 schema identityHash与Phase 0源码包相同 |
| 实机 / 模拟器运行 | 未运行，最终adb devices无连接设备 |

单元测试覆盖：原Task CRUD及字段 / 终态边界；Snooze与多个Snooze、旧通知按钮拒绝、正在做暂停、截止边界、安静时段 / 跨午夜 / 夏令时重复小时、每日上限规则；事件类型 / 前后快照 / JSON转义 / 损坏历史回退；风险等级 / 延期升级 / 多次推迟改变策略 / 进度降低风险 / 100%待确认 / 规则上限；页面状态随时间刷新、保存 / 读库失败恢复、快速重复提交；主题及提醒设置持久化。

13个设备测试案例已编译：四表创建、导航、完整CRUD / 执行UI、表单与草稿；数据库重开 / 删除级联 / 并发更新；重复扫描 / 全局配额 / 通知阻止 / 终态取消；SQL故障注入验证任务和事件回滚；真实Room Snooze槽重复动作拒绝；重开数据库保留提醒时间 / 次数 / 配额且不重复消费旧槽。
设备测试没有实际运行，不能把这些案例写为实机通过。

修复记录：严格Lint要求URI改用toUri；全默认参数的@Inject构造函数生成多构造，改为唯一@Inject主构造及未注入的测试构造；100%进度的建议优先级；手动推迟和旧按钮恰好落在同一提醒槽时的去重边界。未新增Lint baseline或关闭代码检查。

## 覆盖安装与数据

APK包名com.executionagent.app。新旧证书SHA-256：
`ac78a41b08bb256c5485506e58a45c957e7f3f296d1488ca2280444f0f5a37be`。

Room v1 schema identityHash：`672f5ac6b6a4fcdf6071a2fd016a4411`。
execution_agent.db与agent_settings名称不变，无破坏性迁移；不需要卸载旧版。
请直接覆盖安装，勿卸载或清除数据；实际手机覆盖安装仍需验收。
源码包不含签名私钥；本地继续构建覆盖测试时沿用原Gradle缓存 / 签名环境，原文件仍保留在work中。

## 实机验收步骤

1. 覆盖安装Phase 4 APK，检查Phase 1任务 / 主题仍在；打开设置允许通知，可选允许精确闹钟。
2. 为短期测试关闭安静时段，创建开始已到、截止在1–2分钟后的任务；退回桌面，记录通知实际到达时间。
3. 点通知正文，确认打开正确任务；点正在做，确认执行状态 / 开始时间及45分钟暂停，检查历史。
4. 测试通知推迟20分钟；连续点击旧通知按钮不能重复增加次数。也测试任务页推迟20分钟 / 1小时，截止时间仍保留。
5. 标记完成 / 放弃 / 删除，确认对应通知取消，不再收到该任务的提醒。完成事件及100%进度正确。
6. 将每日上限设为2，为三个任务设置已过截止，确认当天全局最多提交2次提醒；重启应用后配额不被清空。删除任务也不重置配额。
7. 开启安静时段并设置包含当前时间的窗口，检查提醒顺延。关闭任务提醒，确认已有任务通知取消。
8. 对同一任务推迟3次，检查风险原因 / 缩小目标建议及60分钟重复间隔；将目标 / 截止编辑为现实计划，检查风险变化和历史记录。
9. 在设备重启、应用升级、时间 / 时区改变后，检查未结束任务恢复。覆盖安装保留数据及厂商省电行为需要本机验证。
10. 可用连接的测试设备执行build-windows.ps1的connectedDebugAndroidTest，运行已编译的13个案例。

## 实际限制

AlarmManager精确权限控制触发能力，Receiver再提交WorkManager；调度、Doze、省电、强行停止和厂商后台策略可能使通知延迟。恢复流程已实现，但尚无设备运行证据。参考[Android闹钟调度](https://developer.android.com/develop/background-work/services/alarms)和[精确闹钟权限说明](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)。

系统接受notify不代表用户看见。SQLite与系统通知不能构成跨进程原子事务：在投递后、数据库提交前被终止时可能再次更新同一任务的通知，不承诺任何崩溃下恰好一次。
安静时段 / 配额控制实际最早可提醒时刻；详情的nextReminderAt为内部最早时间，不是准时到达保证。
当前没有云同步 / 备份、完整Insights、AI或真实学习行为预测。删除会永久清除关联事件 / 聊天历史，UI已要求确认。

## 主要文件

- domain/engine/ExecutionEngine.kt：风险分数、原因、策略、间隔。
- domain/reminder/ReminderPolicy.kt：时间 / 安静 / 配额规则及通知内容；TaskOperations.kt：Snooze / 正在做 / 受控操作。
- reminder/ReminderEngine.kt、ReminderWorker.kt、AndroidTaskNotifications.kt：Android调度、恢复、通知动作。
- data/repository/RoomTaskRepository.kt、EventPayload.kt、data/local/ExecutionDatabase.kt：持久化配额和任务事件事务。
- ui/TaskViewModel.kt、TaskScreens.kt、TaskEventPresentation.kt、SettingsScreen.kt：风险、历史、提醒设置 / 权限。
- MainActivity.kt、ExecutionAgentApplication.kt、AndroidManifest.xml、di/AppModule.kt：启动 / 通知入口、恢复 / 权限及绑定。
- 新增规则、事件、提醒、事务和恢复测试；README / 架构 / 分阶段规格更新。

## 交付

execution-agent-phase4.apk、execution-agent-phase4-source.zip、execution-agent-phase4-androidTest.apk。
验证证据：phase2-build.log、phase3-build.log、phase4-build.log、phase4-apk-verification.txt、phase4-validation.json。
旧版APK、源码ZIP、缓存、原签名文件保持原样。

Phase 4 APK SHA-256：`1e364aac58597e7049d877ef383aecf7967a3337e9462054a6820f4fc24f844e`。

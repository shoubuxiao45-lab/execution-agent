# Phase 7 · Execution Profile 与最终交付

本次完成 Phase5–7，版本0.7.0-phase7，versionCode8。旧APK及源码快照保留；最终 APK execution-agent-phase7.apk，源码 execution-agent-phase7-source.zip。

## 统计口径

设备当前时区的本地日历窗口：最近7 / 30天，从今日减6 / 29天的日初，到当前时刻，边界包含。任务 cohort 使用截止时间，不使用创建 / 完成日；截止在未来或窗口外的任务排除，删除的记录不可恢复统计。时区变化后重新划分，窗口按日历日计算以处理夏令时。

- 完成率：COMPLETED任务 / cohort中非CANCELLED任务；未结束仍在分母，取消单列。拆分原任务已取消，子任务替代原计划。
- 准时率：有效completedAt≤deadlineAt / 有有效完成时间的已完成任务。完成时间缺失、早于创建或晚于当前时刻不参加时间统计，并单列数量；完成状态仍参与完成率。
- 平均延期：上述有效完成任务 max(0,completedAt-deadlineAt) 的分钟均值，准时计0；未结束不参加。不是未结束任务的当前拖延均值。
- 推迟次数：cohort中非取消任务的当前累计snoozeCount之和；撤销后当前值为准，非仅窗口内事件次数。
- 分类：同一cohort、同一完成率分母，取消数单列，空分类为未分类。
- 最佳开始时段：有有效实际开始时间的有效任务，按本地00–06 / 06–12 / 12–18 / 18–24分组，每组至少5项，比较当前完成率；平局优先样本多，再按标签排序。
- 最佳预计时长：同一有效任务按1–15 / 16–30 / 31–60 / 61–120 / 超过120分钟分组，同样至少5项。时长是用户预计值，不是测得的专注时长；不提供未来成功预测。
- 趋势：按计划截止的本地日期显示当前完成结果，7 / 30日包括空日，不是每天实际完成量。
- 干预后记录：按干预发生日期进入窗口；REMINDER_SENT或AI_INTERVENTION(APPLY)。观察干预后严格晚于事件时间、60分钟以内同任务的STARTED / COMPLETED。完整窗口尚未结束不判失败；窗口内再次干预剔除早期样本，同时间干预全部剔除；已撤销建议剔除。AI自身直接动作的同proposalId事件不计回应，拆分子任务不归给父任务。分别显示提醒、AI建议及记录中的策略样本，少于5项标记样本少。只表示关联，不能推断因果。

Profile Repository 用 Room @Transaction + @Relation 读取任务 / 全部事件一致快照，纯 Calculator 不写数据。ViewModel 随数据 / 7或30天选项 / 每30秒时间刷新，读取失败可重试。没有重写 daily_stats，原通知配额保留。

## 变更与测试

新增 domain/profile/ExecutionProfile.kt、data/repository/RoomProfileRepository.kt、LocalExecutionRecord查询、ui/InsightsViewModel.kt、InsightsScreen.kt。Root接入真实洞察；AI干预事件增加策略JSON字段，旧无策略事件显示未记录，不需要数据库迁移。README、ARCHITECTURE、PRODUCT_SPEC同步更新。

新增14项统计单元测试（空样本、分母、延期、缺失时间、7/30边界、时区、DST、最低样本、分类、成熟干预、未满窗口、重叠、撤销/自身动作、坏payload/不同任务）及2项ViewModel读取恢复 / 数据时钟测试。新增统计持久 / 配额保持和空状态 / 窗口UI设备测试。全量构建测试和 Lint 的最终结果见 phase7-build.log、phase7-validation.json。

设备测试仅编译，未连接手机运行；Kimi未提供真实密钥，没有真实API调用。覆盖安装、Keystore、Coach实际操作、UI、提醒后台 / 重启 / 厂商省电仍需手机验收。完成本阶段不等于Phase8集中实机验证完成。

## 手机验收步骤

1. 直接覆盖安装Phase7 APK，检查旧任务 / 历史 / 主题仍在；不要卸载或清除数据。
2. 新建未来截止任务，进入“讨论下一步”，保持无密钥，发送“缩小”；核对前后目标，取消弹窗应无变化，确认应用后目标 / 历史更新；撤销恢复旧目标。
3. 发送“取消”和“重新安排”，检查具体计划改动需确认；更改截止或取消可撤销。先手动编辑任务，再应用此前建议，应提示任务变化。
4. 发送“拆分”，确认后原任务取消、出现2个子任务；未修改时撤销恢复原任务并移除子任务；修改子任务后撤销必须拒绝。结束 / 完成后不能再执行动作。
5. 发送“推迟”多次，次数、原截止、风险与提醒检查正常；确认建议状态在关闭并重开应用后保持。重复点击或已应用建议不能重复拆分。
6. 设置中输入个人Kimi密钥，检查模式切换；真实发送可能消耗额度。无网 / 无效密钥应保留输入且可重试，清除密钥恢复Mock。密钥应在重开后可用但不回显。
7. 创建少量不同类别和完成 / 取消 / 未完成任务，查看7/30天统计分母和样本数；少于5项不应显示最佳时段 / 时长。超过观察窗口后检查干预样本，立即标完成不应被包装为AI作用证据。
8. Phase8另行验收设备重启、后台、省电、时间/时区更改、通知权限和精确闹钟权限。

实现参考：[Kimi Chat / JSON Mode](https://platform.kimi.com/docs/api/chat)、[Android Keystore](https://developer.android.com/privacy-and-security/keystore)。

交付前补充：撤销恢复任务时重新生成提醒槽（恢复时刻增加1–2毫秒），防止撤销重新激活之前排队的通知按钮；新增回归测试验证前后旧槽均被拒绝。

最终验证：完整 Gradle build / test / assembleDebugAndroidTest 成功（1分2秒），Debug与Release各86项单元测试，失败 / 错误均0；Lint No issues found。20项androidTest只编译。APK签名、16KB页面对齐、包名 / 版本、原Room schema兼容性、源码归档和SHA256分别见phase7-apk-verification.txt与phase7-validation.json。当前没有连接Android设备，不宣称实机通过。

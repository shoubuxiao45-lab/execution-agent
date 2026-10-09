# Phase 6 · Task Coach 与受控动作

实现任务关联聊天、最近100条本机历史、有限上下文、Mock / Kimi 标识、可恢复发送错误。Today 详情的“讨论下一步”直达该任务；Agent 页可切换任务。离线 Mock 能开始、完成、取消、推迟、缩小、拆分、重新安排和设置提醒。AI 不访问 DAO，不自动应用动作。

AgentActionValidator 拒绝非 JSON、未知字段 / 策略 / 动作、跨任务、错误类型、字符串或小数假冒整数、非法时长 / 时间 / 长度、超过3项、重复类型、终态操作和终态之后的修改。时间限制为未来366天，提醒不早于截止；拆分2–5项，继承分类 / 截止，以子任务替代并取消原任务。整批校验成功才保存提案；应用时再次校验时间。

所有动作先展示改动再弹窗确认，包括目标 / 截止前后值和子任务内容。CoachOperations → CoachRepository：当前任务须精确等于生成建议时快照；整批任务、子任务、领域事件、AI_INTERVENTION 和提案状态同 Room 事务提交。失败全部回滚，不留下部分动作。提案ID和状态保存在原 conversations 表的版本化 JSON 内容中；重复应用不会重复写入，进程重建后仍可判断已应用 / 已撤销。并发落败请求可能提示刷新，重试已提交建议为无操作。

撤销也在事务中恢复此前任务并写 UNDO 事件；只允许任务及子任务与应用快照一致，子任务没有后续事件或聊天。如果后续人工操作或提醒更新已改变任务，撤销会拒绝，保留新数据。撤销拆分删除此次创建且未改变的子任务。恢复提醒之后仍遵守安静时段 / 配额，不回退当天通知次数。确认本身不锁住任务；等待网络或确认期间的改动会使旧建议失效。

无需数据库迁移：复用 Room v1 conversations，其他字段和 schema 未改变。变化文件：domain/ai/{AgentActionValidator,CoachOperations}、data/repository/RoomCoachRepository、DAO查询、Task序列化、ui/{CoachScreen,CoachViewModel}、Root、Task详情、事件展示、Mock扩展、Hilt绑定。

测试：动作校验12项、操作4项、HTTP失败1项；Room设备测试包括事件失败回滚、并发 / 重复拆分、跨重建提案与撤销保护，另有确认UI与密钥加密持久测试。最终 build / test / assembleDebugAndroidTest 结果见 phase6-build.log。设备测试只编译，尚未连接手机运行；真实API同样未调用。

最终完整构建通过，Debug / Release 各69项单元测试通过，Lint无问题；18项设备测试编译完成，未运行。额外验证聊天重复发送 / API失败重试保留输入 / 非法JSON恢复。

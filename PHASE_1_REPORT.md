# Phase 1 验收报告

日期：2026-10-06（Asia/Shanghai）。版本：0.1.0-phase1，versionCode 2。
基于已交付的 Phase 0 工程继续开发；用户在本次请求中报告已实机验收 Phase 0。

## 已实现

- Task CRUD：创建、列表、详情、编辑、删除，支持标题 / 目标 / 分类 / 开始 / 截止 / 预计时长。
- Today：今日、以后、已结束、全部；历史未结束任务留在今日，按截止排序；到期显示已延期。
- 手动执行操作：开始、更新进度、完成、放弃；保留首次开始及完成时间；重复开始 / 结束操作幂等。
- 标题和长度、预计时长、时间关系、进度范围、任务存在性及终态操作校验。
- 新建 UUID；编辑保留 ID / 创建时间 / 执行元数据；事务读取最新行后更新，避免并发丢失。
- 保存错误留在表单；读库错误与空列表区分，并支持重试；保存时禁止重复提交。
- 日期 / 时间选择器、表单滚动、键盘避让、旋转 / 系统恢复时保存草稿字段、未保存退出确认。
- 完成 / 放弃确认；删除明确说明级联删除关联事件及聊天历史，并要求确认。

## 边界

提醒、Snooze、后台任务调度属于 Phase 2，事件写入属于 Phase 3，风险引擎属于 Phase 4，AI 属于 Phase 5–6，洞察属于 Phase 7。本次未提前接入。
OVERDUE 是依据 ClockProvider 的显示投影，最长约 30 秒刷新，不在后台改写数据库状态。
进度 100% 不自动完成；已完成 / 已放弃任务不能编辑或重新打开，但可删除。

## 实际验证

执行项目 build-windows.ps1，任务为 build、test、assembleDebugAndroidTest。
最终日志：上级目录 phase1-build.log（原始记录在 work/phase1-final-build.log）。

| 检查 | 结果 |
|---|---|
| 最终 Gradle build / test | BUILD SUCCESSFUL，23 秒 |
| Debug 单元测试 | 15 项，0 失败，0 错误 |
| Release 单元测试 | 15 项，0 失败，0 错误 |
| Kotlin / Room / Hilt 编译 | 通过，warningsAsErrors 仍启用 |
| Android Lint | 0 errors, 0 warnings |
| Android 设备测试 APK 编译 | 通过，6 个测试案例 |
| APK 签名 | apksigner verify 通过，v2 签名 |
| APK 对齐 | zipalign -c -P 16 4 通过 |
| 更新包身份 | 包名不变、证书一致、versionCode 1 → 2 |
| 数据库 | v1 schema identityHash 与 Phase 0 源码包相同 |
| Phase 1 实机 / 模拟器测试 | 未运行，adb devices 无连接设备 |

单元测试覆盖：创建 / 修改 / 删除、字段边界、UUID 唯一性、执行元数据保留、重复操作、终态拒绝、进度边界、到期投影、跨本地午夜分组、缺失任务、快速重复提交、保存错误恢复、读库失败恢复，以及原有 Clock / enum / DataStore 测试。
设备测试已编译：四表创建、页面导航、关闭 / 重开数据库后任务持久化、删除历史级联、并发更新及事务回滚、完整 CRUD / 执行页面流程、无效表单及草稿退出确认。未将编译作为设备运行证据。
测试数据库使用随机独立文件或内存数据库，不清空正式应用数据库。

测试开发中修复了混合参数数组的 Kotlin 类型推断警告，以及 runTest 结束前未清理周期刷新协程导致的等待；最终检查全部通过，没有降低 warning 策略。

## 覆盖安装与数据保护

新包和旧包都使用证书 SHA-256：
`ac78a41b08bb256c5485506e58a45c957e7f3f296d1488ca2280444f0f5a37be`。

Room v1 schema identityHash：`672f5ac6b6a4fcdf6071a2fd016a4411`，与 Phase 0 一致。
数据库名 execution_agent.db、DataStore 名 agent_settings 不变；不启用破坏性迁移。
直接覆盖安装 Phase 1 APK，不卸载旧版或清除应用数据。签名 / schema 检查支持升级兼容判断，实际手机覆盖安装仍需验收。
旧 Phase 0 APK 和源码 ZIP 均保留。源码包不包含本机 SDK 配置、缓存、密钥或签名私钥。

## 建议实机验收步骤

1. 覆盖安装 execution-agent-phase1.apk，确认原有主题保留；不要卸载 Phase 0。
2. 新建“电路作业 / 完成两题”，填写分类、开始 / 截止和预计 30 分钟，保存并查看详情。
3. 修改目标 / 时长，开始执行并保存 40% 进度；强制停止应用再打开，确认任务 / 字段 / 状态 / 进度保留。
4. 标记完成，确认移至已结束，显示完成时间及 100%。另建一项任务，测试放弃。
5. 新建明日任务，确认在以后；设置一项截止已过的任务，确认今日显示已延期。
6. 尝试空标题、0 分钟、截止早于开始，确认保存被拒绝；填写草稿后返回，确认退出提示。
7. 删除任务先取消确认，检查仍在；再确认删除，检查从列表消失。
8. 切换深色主题，试用日期 / 时间选择器、弹出键盘、滚动长表单；旋转屏幕后检查草稿字段。

## 交付

- execution-agent-phase1.apk：可安装的 Phase 1 调试签名 APK。
- execution-agent-phase1-source.zip：完整源码、测试、schema、文档及 Gradle wrapper。
- execution-agent-phase1-androidTest.apk：设备测试 APK，仅供连接设备时运行测试。
- phase1-build.log、phase1-apk-verification.txt、phase1-validation.json：构建和验证证据。

APK SHA-256：`17ed83fc5a62dbdbc5fbd17b14a9ae4547bdf2e82b5980413b68720f4ee70b86`。

## 主要变更文件

领域：domain/model/Task.kt、domain/repository/TaskRepository.kt、domain/usecase/TaskOperations.kt、domain/usecase/TodayTasks.kt。
数据：data/local/ExecutionDatabase.kt、data/repository/RoomTaskRepository.kt（实体 / schema 保持不变）。
页面：ui/ExecutionAgentRoot.kt、ui/TaskViewModel.kt、ui/TaskScreens.kt。
构建：app/build.gradle.kts（版本升级）。
测试：FoundationTest.kt、MemoryTaskRepository.kt、TaskOperationsTest.kt、TaskViewModelTest.kt、NavigationSmokeTest.kt、TaskPersistenceTest.kt、TaskUiTest.kt。
文档：README.md、PRODUCT_SPEC.md、ARCHITECTURE.md、PHASE_0_REPORT.md（追加用户验收记录）、本报告。

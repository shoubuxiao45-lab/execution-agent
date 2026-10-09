# Execution Agent

Android 原生个人执行力智能体，已完成 Phase 8；当前更新为动态执行提醒，版本 `0.9.0-dynamic-reminders`。

## 当前可用

- 任务创建、编辑、删除、开始、进度、完成、取消、Today 分组和任务历史。
- 计划开始提醒、可选准备提醒、静默进度检查、预计完成询问、硬截止逾期干预、推迟10分钟、终态取消、通知按钮、安静时段及每日上限；AlarmManager + WorkManager 恢复。
- 本地规则风险分数、原因及下一步建议；高风险改变提醒策略，多次推迟后降低催促频率。
- AiProvider：无密钥为离线 Mock，设置个人 Kimi 密钥后可发送任务上下文；API 失败可重试。
- 任务教练：讨论下一步、缩小 / 拆分 / 重新安排等提案。所有动作先校验整批，再展示改动并确认；任务 / 子任务 / 事件 / 提案状态同事务提交，支持受保护的撤销。
- 本地执行洞察：7 / 30 天完成率、准时率、延期、累计推迟、分类、时段 / 时长样本、每日趋势、提醒与教练干预后记录。
- Room 持久化、DataStore 设置、明暗主题。无云同步或备份。
- 提醒运行状态：最近成功扫描、已登记闹钟及精确 / 普通模式、恢复工作、电池优化状态；支持手动重新扫描。
- 聊天草稿与待重试用户消息恢复；AI 配置解密失败提示、密钥保存失败后保留输入。

## 安装与使用

上级目录 `execution-agent-dynamic-reminders.apk` 可覆盖旧版。包名 `com.executionagent.app`、原签名、数据库名称和 DataStore 名称保留；versionCode 升至10。Room v1→v2 使用显式迁移，保留任务、事件、聊天和每日配额。不要卸载旧版或清除数据。

新建任务先选计划开始、预计时长；预计完成自动计算，最晚完成可不设置。默认在计划开始提醒；提前5/10/15分钟准备提醒默认关闭。开始后按时长安排静默检查，预计完成时询问进度；没有最晚完成时间的任务只显示计划超时。普通提醒顺延到安静时段结束；明确选择指定时间提醒可在安静时段通知，但仍受每日上限控制。

详情显示各个时间及下一次检查；下一次检查可能只记录进度、不发通知。独立验证环境与完整迁移证据见 DYNAMIC_REMINDERS_REPORT.md。之前 OPPO Phase7→8 的实机结果仍保存在历史报告，不表示当前更新已在手机复测。

在任务详情点“讨论下一步”，或从 Agent 页选择任务。离线 Mock 可用“开始 / 完成 / 取消 / 推迟 / 缩小 / 拆分 / 重新安排 / 提醒”等文字体验。建议不会自动执行；“核对并应用”展示修改，确认后执行。拆分取消原任务并创建2–5个子任务，避免重复计划 / 提醒。任务变化使旧建议失效，重新发送生成新建议。撤销只恢复未被后续操作或提醒改变的任务；子任务有变化或聊天时，不能撤销拆分。

Kimi 配置在手机设置中输入 AI_BASE_URL、AI_API_KEY、AI_MODEL。默认地址 `https://api.moonshot.cn/v1/`，默认模型 `kimi-k2.6`；可切换官方国际地址 `https://api.moonshot.ai/v1/`。无密钥不联网。点击发送才发送当前任务、最近10条事件类型 / 时间和8条聊天消息，可能消耗 API 额度。密钥以 AndroidKeyStore AES/GCM 加密保存，既不回显，也不放入 APK / 源码 / 日志；清除密钥恢复 Mock。ai.local.properties.example 只说明字段，不由构建读取。真实 API 未调用验证。

洞察按设备时区、最近7或30个日历日计算，最晚完成时间已到的任务进入统计；没有最晚时间时按预计完成时间归组；取消单列，未结束任务计入完成率分母。准时率 / 延期只统计具有硬截止且有有效完成时间的任务；平均延期含准时的0分钟。每组至少5项才展示开始时段 / 预计时长的最高完成率样本。干预观察60分钟，重叠和撤销剔除，AI动作自身直接产生的开始 / 完成不作为回应；只描述关联。删除记录后不再统计，改变时区会重新划分历史日期。

## 构建与验证

Android Studio 打开本目录。JDK17、SDK platform35 / build-tools35.0.0；最低 Android8.0/API26。Gradle8.11.1、AGP8.9.2、Kotlin2.1.20固定版本。
复制 local.properties.example 为 local.properties，配置 SDK 路径。

```powershell
.\build-windows.ps1 -JdkHome '完整 JDK 路径' -GradleCache '原 work/gradle-home 路径'
# 可选代理，仅影响本次进程
.\build-windows.ps1 -JdkHome '完整 JDK 路径' -GradleCache '原缓存路径' -Proxy 'http://127.0.0.1:端口'
# 使用独立包名连接设备测试，避免测试写入原应用
.\build-windows.ps1 -JdkHome '完整 JDK 路径' -GradleCache '原缓存路径' -Tasks @('-PdeviceTestBuildType=validation','assembleValidation','assembleValidationAndroidTest')
# adb -s 设备序列号 install -r app/build/outputs/apk/validation/app-validation.apk
# adb -s 设备序列号 install -r app/build/outputs/apk/androidTest/validation/app-validation-androidTest.apk
# 手机需允许验证应用通知。运行完整套件时排除主机控制的恢复夹具：
# adb -s 设备序列号 shell am instrument -w -e notClass com.executionagent.app.Phase8RecoveryFixture com.executionagent.app.validation.test/androidx.test.runner.AndroidJUnitRunner
```

默认任务 build、test、assembleDebugAndroidTest。脚本为中文路径创建临时 ASCII 别名，不改变系统持久环境。APK 在 app/build/outputs/apk/debug/app-debug.apk。
继续覆盖安装需沿用原 work/gradle-home 的签名环境；源码包不包含原签名私钥，独立重建会产生另一调试签名。源码 ZIP 排除缓存、SDK 本机配置、密钥、签名私钥及数据库。

Kotlin 和 Lint warning 按错误处理，GradleDependency 更新提示沿用原例外；SecureAiConfigStore 的 UseKtx 风格提示仅局部抑制，保留 commit 布尔结果以检测密钥保存失败。
数据库已升级到 Room v2，保留 v1/v2 schema 和 FROM_1_TO_2 显式迁移，禁止破坏性迁移。daily_stats.reminderCount 继续用于配额，洞察只读原始记录，不覆盖配额。

阶段报告 PHASE_5_REPORT.md 至 PHASE_8_REPORT.md 列出功能和证据；旧阶段报告保留。Phase8 已连接 OPPO Find X8 Ultra（Android16）及独立 Android15 模拟器进行设备验证，最终项目、设备和恢复场景结果见 PHASE_8_REPORT.md 与上级目录 phase8-validation.json。真实 Kimi API、OPPO 长时间息屏及手机重启仍须另行验收。

# Phase 0 验收报告

日期：2026-10-05（Asia/Shanghai）。当前仅完成 Phase 0，未进入 Phase 1。
初始目录只有 work / outputs，没有已有 Android 源码；本次新建工程，无覆盖用户代码。

## 已实现

- Kotlin 原生 Android，单 app 模块，Compose + Material 3 基础主题。
- 五个页面入口与底部导航；任务、聊天、洞察为明确标注的占位页面。
- Room 四张表、状态 / 事件枚举、DAO、导出 v1 schema。
- Repository + UseCase + Hilt ViewModel + Flow 到 Today 任务数量的只读链路。
- DataStore 保存深色主题，系统栏图标随应用主题调整。
- ClockProvider / SystemClockProvider，测试源集 FakeClock。
- Gradle Wrapper（官方分发包 SHA-256 校验）、Windows 中文路径构建脚本。
- README.md、PRODUCT_SPEC.md、ARCHITECTURE.md，AI 配置模板和 Git 忽略规则。

## 实际验证

执行 `build-windows.ps1`，任务为 `build test assembleDebugAndroidTest`。

| 检查 | 结果 |
|---|---|
| Gradle build | BUILD SUCCESSFUL，最终运行 38 秒 |
| Debug / Release Kotlin、Java、Room / Hilt 生成代码 | 通过 |
| Gradle test | 通过 |
| Debug 单元测试 | 5 项，0 失败，0 错误 |
| Release 单元测试 | 同样 5 项，0 失败，0 错误 |
| Android Lint | No issues found |
| Android 设备测试 APK 编译 | 通过 |
| 调试 APK 签名验证 | apksigner verify 通过，v2 签名，1 个签名者 |
| 实机 / 模拟器测试 | 未运行，adb devices 无连接设备 |

五个独立测试案例：FakeClock 跨本地午夜；Repository 的 Flow 数据变化传递；全部状态 / 事件枚举存储往返；损坏状态拒绝；DataStore 实例销毁并重建后保留主题。
设备测试已编译：四张 Room 表创建 / 空库查询；各页面导航和任务详情返回。不能把编译通过当作运行通过。

最终构建日志未发现编译、弃用或原生路径警告。仅关闭 Lint 的 GradleDependency 更新提示，原因是固定已验证的 SDK 35 / Kotlin 2.1 依赖组合；代码安全检查仍启用，warningsAsErrors=true，没有 Lint baseline。

## 修复与工具链

- Google Maven 短暂超时：保留已下载缓存后重试。
- 现有 JetBrains Java 缺 jlink：在 work/toolchain 下载完整 JDK 17，不修改系统环境变量。
- 中文工程 / SDK / JDK / 缓存路径影响 AGP、AAPT 和 Java 测试进程：使用临时 ASCII Junction 别名；源文件和缓存仍在原目录。
- 依赖原生库原样保留，通过明确 keepDebugSymbols 配置避免无 NDK 的 strip 提示。
- 补齐 Android 12+ dataExtractionRules 和旧版 fullBackupContent，排除本阶段的数据库 / 设置 / 文件传输与备份。

构建环境：Gradle 8.11.1、AGP 8.9.2、Kotlin 2.1.20、完整 JDK 17.0.20.1；compile / target SDK 35、min SDK 26。
SDK 复用本机已存在的 Android 35 工具，当前 local.properties 是本机配置，不包含在源码压缩包中。
工具下载与缓存留在 work，系统 PATH、持久 JAVA_HOME 未修改。
Windows 脚本仅临时设置进程环境变量，并在结束时恢复；目录别名保留以支持后续构建。

## 当前能做什么

安装后进入 Today，切换各导航页面，打开任务详情占位页并返回，在 Settings 切换明暗主题并保存。
当前尚不能创建 / 执行任务、发送任务提醒、调用 AI 或生成执行洞察。没有 API Key 也不影响 Phase 0 运行，因为尚未接入 AI。
MockAiProvider / KimiProvider 在 Phase 5 实现，不能宣称本阶段已有 Mock 功能。

## 后续测试安排

Phase 2：到期未完成、完成取消、Snooze、正在执行、静默时段和通知上限。
Phase 4：多次 Snooze 与风险等级变化。
Phase 5–6：无 Key、Mock、API 失败、非法 JSON、非法 Action。
Phase 8：重启恢复、后台稳定性、无网络与集中错误恢复 / 设备验证。

## 交付文件

- ExecutionAgent/：可继续开发的完整工程（含测试与 schema）。
- execution-agent-phase0.apk：调试签名的页面骨架 APK，未实机验证。
- execution-agent-phase0-source.zip：源码与文档，排除构建缓存、本机 SDK 配置、密钥与签名文件。
- PHASE_0_FILES.txt：本次新增文件完整清单。

APK SHA-256：`1639c27e5e582a81f641040ed8a30f9d376562e255c365221af3309e5c609dd3`。

## 后续用户验收记录

2026-10-06：用户在继续 Phase 1 的开发请求中报告实机验收完成，当前工程阶段为 Phase 0，因此按 Phase 0 实机验收已完成推进。此为用户报告，并非本机运行 connectedDebugAndroidTest 的记录；以上首次交付证据保持原样。

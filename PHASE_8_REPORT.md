# Phase 8：恢复能力与集中设备验证

版本 `0.8.0-phase8`，versionCode 9；原包名、签名环境、Room v1 与 DataStore 保持一致。任务数据没有迁移或清库。

## 本阶段开发

- 设置增加提醒运行状态：最近成功扫描、已登记下次闹钟、精确 / 普通模式、周期恢复工作、电池优化状态、手动扫描与系统设置入口。展示的是最近登记快照，不承诺省电或厂商限制下准时。
- ReminderEngine 使用可测试的调度与运行状态接口。任务已提交后调度失败不会显示保存失败；错误登记并尝试恢复工作，扫描失败交给 WorkManager 重试，成功后清除诊断错误。取消协程不误报失败。
- AI 加密配置无法读取时明确提示离线模式，保留原文件以便处理。密钥保存失败不清空尚未保存的输入；清除密钥不被未保存的无效地址 / 模型阻塞。真实 Key 不进入日志或归档。
- Coach 保存任务选择、草稿及待重试用户行 ID；恢复后重新观察聊天，重试核对已有用户行，防止重复记录。密钥仍不参与状态恢复。
- Retrofit / OkHttp 契约测试验证序列化、Authorization、固定官方地址、禁用重定向 / 自动重试、超时配置及坏响应 / 网络错误处理。使用拦截器夹具，没有真实 API 请求。
- 按需构建独立 validation 包；通知、完整 Hilt 应用页面、Room、Keystore、WorkManager 和恢复夹具都在独立包中测试，不把测试样本写入手机原应用。

## 验证证据

单元测试：Debug / Release 各99项。新增6项调度 / 取消 / 并发 / 错误恢复、4项 Retrofit 契约、2项 AI 设置恢复、1项 Coach 恢复；原状态、事件、提醒策略、风险、统计和 AI 校验用例继续保留。

设备完整套件26项，覆盖原20项，以及真实应用导航、离线教练确认应用与界面重建草稿、损坏密文恢复、诊断持久、生产引擎真实通知及取消、真实 Worker 运行。另有主机控制的 seed / probe / cleanup 夹具，不能直接加入完整套件计数。

连接设备：OPPO Find X8 Ultra / PKJ110，Android16 / API36；模拟器为项目专属 ExecutionAgentPhase8，Android15 / API35、x86_64。手机通知授权由用户完成；OPPO 拒绝 shell 直接授予权限，权限未授权时未伪报发送成功。

最终构建、设备套件、覆盖安装和后台场景结果分别记录在上级目录 `phase8-build.log`、`phase8-phone-instrumentation.log`、`phase8-emulator-instrumentation.log`、`phase8-runtime-matrix.json`、`phase8-upgrade-validation.json`、`phase8-validation.json`。

最终结果：Gradle build / test / assembleDebugAndroidTest 成功；Debug / Release 各99项，失败 / 错误均0，Lint No issues found。OPPO Android16 完整设备套件26/26，Android15 模拟器完整套件26/26。

| 主机控制场景（独立 Android15 模拟器） | 结果与证据 |
|---|---|
| 应用退到桌面，到期提醒 | 通过；系统 NotificationRecord 与唯一 REMINDER_SENT 持久事件 |
| validation 包覆盖安装后提醒恢复 | 通过；任务 / 主题 / 提醒设置保留，随后观察到通知 |
| 模拟器重启并解锁，未手动启动应用 | 通过；开机恢复后观察到通知，任务 / 设置 / 事件保留 |
| 强行停止 | 符合系统限制；观察窗口内未发提醒 |
| 强行停止后重开 | 通过；重新扫描并发提醒，事件未重复 |
| 强制深度 Doze、息屏 | 通过；先核实 IDLE，随后观察精确闹钟触发的通知和持久事件 |
| 精确闹钟权限关闭 | 通过；实际能力与诊断均为 INEXACT，登记普通闹钟；未承诺准点 |
| Wi-Fi 与移动数据关闭 | 通过；真实 Mock 教练页面可发送、确认应用，界面重建保留草稿 |

上述8项场景单列，不加入完整套件26项或单元测试数。测试结束恢复模拟器网络、电池和原 Doze 启用状态。

手机原应用覆盖升级实测：versionCode 8 → 9，安装成功并打开 MainActivity；原有1项任务、1条历史事件、0条聊天均原样保留，DataStore 设置字节一致，Room schema 一致。升级前后私有快照仅留在本地 work 目录，未加入交付归档。手机临时 validation 应用及测试包已移除。

## 已发现并处理的验证问题

原表单设备测试用 performScrollTo 寻找尚未组合的 LazyColumn 保存按钮，在手机和模拟器均失败。已改为列表 performScrollToNode 后定位按钮；真实创建、编辑、开始、进度、完成、删除路径仍完整执行。通知权限未授权的实机失败通过用户授权后重跑，不把系统限制当作实现通过。

后台验证先从主机捕获系统 NotificationRecord，再检查持久任务 / 设置 / 提醒事件，避免 instrumentation 重启目标进程干扰观察。每个恢复样本在验证包中清理；模拟器电量 / Doze / 网络变更恢复后结束。

首轮主机脚本发现镜像默认未启用 Doze，且跨进程诊断快照可能未在 instrumentation 立即退出前写盘。已增加真实 IDLE 核验、夹具诊断写盘屏障及实际 AlarmManager 权限断言，再重跑省电和普通闹钟场景通过；首轮普通后台通知未作为 Doze 通过证据。离线教练用例显式选择自己的样本，避免被已有恢复任务影响。

## 验证限制与手机操作

真实 Kimi API 未调用；限流、鉴权、格式错误、SocketTimeoutException / 网络异常和客户端超时配置用可控夹具验证，未测量真实网络超时等待，也不能证明当前账号、额度或服务可用。无 Key 的 Mock 无需网络。

OPPO 实机的短时设备套件通过，不代表长时间息屏、后台清理、厂商自启动策略、手机重启或任意省电状态均通过。手机重启未自动执行；后台 / 重启 / Doze 的模拟器结果分别报告。

精确闹钟未授权时使用普通闹钟；Android 后台工作 / 普通闹钟允许延迟。强行停止会阻止提醒，需要重开。需要更准的提醒，可在设置授权精确闹钟，并根据个人选择允许后台运行；不要承诺所有 Android / 厂商环境下的准点提醒。

参考：[Android alarms](https://developer.android.com/develop/background-work/services/alarms)、[WorkManager persistent work](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started)、[Android Keystore](https://developer.android.com/privacy-and-security/keystore)。

## 复现设备验证

README 列出 validation 包构建、安装、通知授权与完整套件命令。完整套件必须排除 Phase8RecoveryFixture；该夹具修改验证包的设置，且各方法依赖主机操作顺序。

在专属 Android 模拟器中创建名为 ExecutionAgentPhase8 的 AVD，安装 validation 应用 / 测试 APK 并授权通知后，可运行：

```powershell
python tools/runtime-scenarios.py --adb 'adb.exe 完整路径' --serial emulator-5554 --apk 'app-validation.apk 完整路径' --out '证据输出目录'
```

脚本验证模拟器标志及 AVD 名称，拒绝在手机执行重启 / Doze / 网络操作。各场景保存系统通知和持久数据探测结果，最后恢复模拟器网络、电池与 Doze 状态。等待是有限观察窗口，未观察到触发会记录失败；不能据此将普通闹钟未准点判为系统违约。

# Phase 5 · AI Provider

实现 AiProvider、确定性离线 Mock、Kimi Retrofit / OkHttp 客户端、设备端设置。统一字段 AI_BASE_URL / AI_API_KEY / AI_MODEL。默认无密钥，因此不会联网；不在构建参数或 APK 中植入密钥。仅允许两个 Kimi 官方 HTTPS 地址，关闭重定向、自动连接重试和网络日志。

手机端 API Key 使用 AndroidKeyStore AES/GCM 加密保存于私有 SharedPreferences。密钥输入不进入 saved instance state，不回显已有密钥。个人本地工具直接使用用户自行输入的密钥；多人分发版本应使用后端代理。清除密钥恢复 Mock。Keystore 失效时回退 Mock，需要重新输入。

只有明确点击发送才向 Kimi 传输当前任务、最近10条事件类型与最近8条聊天消息。网络失败、认证 / 限流 / 服务异常、空响应、截断响应均给出可重试错误；不把错误响应或密钥写入任务日志。不暗中将失败回复换成 Mock 建议。请求超时75秒，取消传递至 Retrofit 协程。

变更：domain/ai/AiProvider.kt、data/ai/{KimiProvider,SecureAiConfigStore}.kt、ui/AiSettingsPanel.kt、SettingsScreen、ExecutionAgentRoot、Hilt 绑定、INTERNET 权限、版本0.5.0。

验证：完整 Gradle build / test / assembleDebugAndroidTest 的最终结果见同目录 phase5-build.log。新增7项 Provider 单元测试，覆盖无Key不联网、JSON请求及上下文截断、网络错误脱敏及重试、取消、空 / 截断输出、主机限制和密钥脱敏、多次延期策略。真实 Kimi API 与手机 Keystore 暂未实机验证，未使用任何真实密钥或调用额度。

参考：[Kimi Chat API / JSON Mode](https://platform.kimi.com/docs/api/chat)、[Android Keystore](https://developer.android.com/privacy-and-security/keystore)。

最终完整构建通过（1分44秒），Debug / Release 各50项单元测试通过，Lint无问题。UseKtx 风格提示仅在保存密钥的方法上说明并局部抑制，因为 KTX edit(commit=true) 丢弃 commit 的成功结果，无法报告磁盘提交失败。其余严格检查保持启用。

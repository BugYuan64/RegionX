# RegionX Android 审核报告

> **2026-10-30 修复更新：** 用户随后已授权修复。下文保留初次审核快照，行号可能随修复变化；当前状态以本节为准，原始“不宜验收”的结论不代表修复后的测试结果。

## 修复状态与验证更新

| 原问题 | 本轮处理 |
| --- | --- |
| 1 连续截图授权 | 一次授权持有单个 VirtualDisplay；每次请求新建 ImageReader/Surface，避免读取悬浮窗隐藏前的旧帧 |
| 2 网络取消/超时 | HTTP Call 的响应头和整个响应体生命周期绑定协程取消；模型列表同样处理 |
| 3 低版本 Gemini | 改用 API 29 可用的 URLEncoder 字符串编码名重载；Lint NewApi 错误消除 |
| 4 取消持久化 | 排队、立即取消、执行中取消统一完成落盘；NonCancellable 保存部分内容；重试/删除等待旧 Job 收尾 |
| 5 授权失效恢复 | 录屏停止状态传到主界面；重新授权恢复现有服务与会话 |
| 6 悬浮队列 | 使用 LazyColumn 滚动并定位最新任务；展开显示受限高度内可滚动的完整答案 |
| 7 详情旧答案 | 初次答案以稳定身份原位替换，保留追问；流式/完成事件刷新详情 |
| 8 中断追问丢失 | 停止/超时/失败保存部分内容与标志；最后一块到达后取消也完成保存 |
| 9 会话未订阅 | 主界面直接订阅合并后的 visibleSessions，搜索、重命名和任务变化驱动刷新 |
| 10 旧模型缓存 | 恢复悬浮窗前重新读取模型列表、当前模型与主题设置 |
| 11 最后增量丢失 | 先发送结束事件中的文字，再结束流 |
| 12 半尺寸裁剪 | 使用 bitmap/screen 比例，向外取整并收敛边界 |

悬浮队列的追问采用「打开对话并追问」入口进入主界面，队列自身保持不抢焦点；这项 Android 交互取舍已记录到 README，不声称实现了悬浮队列内的软键盘输入。

独立复核又发现并修复了 3 个关联边界：初次答案时间戳可能使发往模型的消息顺序颠倒、最后输出到达后取消会跳过保存、初次任务在追问期间完成时详情状态未刷新。三项均先以新增回归用例观察到失败，再修复通过。

当前自动化验证：**47 tests / 0 failures / 0 errors**；`lintDebug` 为 **0 errors / 17 warnings**；Debug 与未签名 Release 构建成功。Robolectric 集成测试使用真实 SQLite 行为（模拟 API 29），没有将内存替身结果当成实际数据库验收。初始化只执行一次，避免 Activity 重建时恢复逻辑误取消仍在运行的任务。

原生验证：隔离 Android 16（API 36）模拟器完成启动、悬浮窗授权、整屏授权，以及**同一次授权连续 10 次截图**，10 条均经仅本机监听的假模型服务完成。初次环境连通性探测有 1 条失败任务（假服务先启动在网络隔离沙箱内），改为仅 localhost 的可达服务后完成上述连续回归，未计作全部任务均成功。

系统主动停止投屏后，`dumpsys media_projection` 显示 null；原会话及 11 条测试任务保留，主界面实际显示「屏幕授权已失效 · 重新授权」。该模拟器未配置锁屏凭据，熄屏没有触发 keyguard 自动停止，不将熄屏动作描述为已覆盖真实锁屏策略。

随后从该入口重新授权，再次截图并经假模型处理完成；数据库仍为 1 个会话，共 12 条测试任务（11 完成，1 为前述环境探测失败）。未发现 AndroidRuntime 或 RegionX 错误日志。完整队列已显示末尾新任务；完整的多手势/多尺寸滚动验收仍留给设备测试。

尚未完成：物理手机/OEM 后台策略、真实上游模型、签名发布、多版本完整 UI/输入法/旋转兼容性。Release 包仍未签名。交付路径为 `publish/RegionX-android-debug.apk` 和 `publish/RegionX-android-release-unsigned.apk`。

---

审核日期：2026-10-30（北京时间）。范围：当前 `android/` 源码、功能需求、Gradle 配置、原有测试、Debug/Release 构建与 Lint。Windows 端仅用于核对需求，没有修改。

结论：Android 版已有完整工程骨架，但目前不宜按“完成版”验收。连续截图、取消/超时和低版本 Gemini 存在优先修复项，另有历史、详情和悬浮队列问题。

本次仅新增本报告；未修改产品实现、项目测试或构建配置。独立审核用例及结果保存在[审核证据目录](C:/Users/BaiUo/.codex/visualizations/2026/10/30/01a18c85-7630-7f73-825c-457574ad554b/android-review)。没有提交、推送、安装或调用真实模型服务。

## 实际验证结果

| 检查 | 结果 | 边界 |
| --- | --- | --- |
| 原有单元测试 | 19 项通过：适配器 8、并发闸门 3、标题规则 8 | 本轮强制重新执行过；未覆盖 Android 运行时、UI、TaskQueue/TaskRunner 的取消持久化 |
| Debug 构建 | `assembleDebug` 成功生成 APK | 未安装验收 |
| Release 构建 | `assembleRelease` 成功生成压缩 APK | 产物是 `app-release-unsigned.apk`，不代表签名发布完成 |
| Lint | 失败：2 errors、17 warnings | Gemini 的 NewApi；本机 local.properties 的 PropertyEscape |
| 独立审核回归用例 | 4 项执行、4 项失败、0 项测试错误 | 分别复现两种取消持久化缺陷、阻塞读取不能及时取消、最后增量被丢弃 |
| ADB | 设备列表为空 | 未做真机/模拟器截图、旋转、系统授权、输入法或触摸验收 |

构建使用 `C:\Tools\gradle\bin\gradle.bat`、`C:\Tools\jdk21-real` 和本机 SDK 36。先离线运行发现缺少 Lint 依赖，联网补齐后完成检查。整体 Gradle 命令因 `lintDebug` 失败返回非零；使用 `--continue` 后 Debug 和 Release 打包任务均实际完成。

原有测试结果保存在[baseline-results](C:/Users/BaiUo/.codex/visualizations/2026/10/30/01a18c85-7630-7f73-825c-457574ad554b/android-review/baseline-results)，Lint 完整输出在[lint-results-debug.txt](C:/Users/BaiUo/.codex/visualizations/2026/10/30/01a18c85-7630-7f73-825c-457574ad554b/android-review/lint-results-debug.txt)。

## 优先修复（P1）

### 1. 同次授权下第二次截图违反 Android 14+ 的 MediaProjection 规则

位置：[ScreenCapture.kt:128](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/platform/ScreenCapture.kt:128)。

`captureRegion()` 每次使用保存在字段中的同一个 `MediaProjection`，`captureFullScreen()` 每次重新调用 `createVirtualDisplay()`，截图后又释放显示器。targetSdk 为 36，Android 14+ 不允许同一 projection 重复创建 VirtualDisplay。因此第一次截图成功后，再点“问答/翻译/提取”会抛 `SecurityException`；取帧失败后的半尺寸重试同样触发这一限制。

证据级别：源码与[Android 官方 MediaProjection 规则](https://developer.android.com/media/grow/media-projection#user_consent)核对，尚未设备复现。应在一次授权会话中持有一个 VirtualDisplay，截图时取帧，尺寸变化通过 resize/setSurface 处理，在会话结束时统一释放。

### 2. 阻塞网络读取不能随取消或业务超时结束

位置：[ChatModelClientBase.kt:76](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/core/llm/ChatModelClientBase.kt:76)、[Llm.kt:84](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/core/llm/Llm.kt:84)、[Llm.kt:141](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/core/llm/Llm.kt:141)。

使用同步 `Call.execute()` 和 `readUtf8Line()`，未把协程取消连接到 `Call.cancel()`，且 readTimeout/callTimeout 均为 0。上游保持连接但不发送 SSE 行时，取消与 `withTimeout` 无法中断正在阻塞的读取，任务继续占用并发名额，所有名额被占满后新任务只能排队。

已由 `cancellationMustInterruptBlockedHttpRead` 复现：响应体延迟 1600ms，发出取消后仍等待 **1451ms** 才结束，超过测试的 500ms 上限。服务器永久无响应时风险会持续。应把 HTTP Call 生命周期与协程取消绑定，并验证静默连接、读取中断及业务超时。

### 3. Gemini 在 Android 10–12L 调用不存在的 API

位置：[GeminiClient.kt:33](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/core/llm/GeminiClient.kt:33)。

`URLEncoder.encode(model, Charsets.UTF_8)` 选中 Charset 重载，该重载从 API 33 才存在；当前 minSdk 是 29，没有启用 core library desugaring。API 29–32 使用 Gemini 时会发生 `NoSuchMethodError`，现有 `catch(Exception)` 不能捕获该 Error，可能直接终止进程。

证据级别：本轮 Lint 报出 `NewApi`，并核对了[官方 API 文档](https://developer.android.com/reference/java/net/URLEncoder#encode(java.lang.String,%20java.nio.charset.Charset))。可使用旧系统已有的字符串编码名重载，并补最低支持版本验证。

## 功能与数据一致性（P2）

### 4. 取消后的状态与部分内容不能可靠持久化

位置：[TaskQueue.kt:36](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/core/tasks/TaskQueue.kt:36)、[TaskRunner.kt:128](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/core/tasks/TaskRunner.kt:128)。

有两条独立路径：排队时取消只调用 `onChanged` 发事件，没有数据库写入；执行中取消则在已取消的协程中调用持久化，数据库的 `withContext(Dispatchers.IO)` 会再次抛取消异常，又被 `runCatching` 吞掉。前者数据库仍为 Queued，后者仍为 Running，部分回复也可能丢失。悬浮窗收到事件后重新查询数据库，反而会显示旧状态。

已分别由 `queuedCancellationMustPersist` 和 `runningCancellationMustPersist` 复现：期望 Cancelled，实际分别是 Queued、Running。测试中的内存持久化替身模拟生产数据库切换协程上下文的行为，未直接运行 Android SQLite。应为取消收尾提供可完成的持久化上下文，再发出与落盘一致的事件。

### 5. 锁屏或系统停止投屏后，没有可达的重新授权流程

位置：[ScreenCapture.kt:36](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/platform/ScreenCapture.kt:36)、[MainActivity.kt:222](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/MainActivity.kt:222)。

`onStop()` 只清空截图对象，服务和 `OverlayController.running` 仍为运行状态。用户返回主界面再点“查看任务”，代码只显示旧悬浮窗并把 Activity 放到后台，不发起授权。随后点截图始终提示授权失效。虽然有 `ACTION_RESTORE_PROJECTION`，当前没有入口发送它。

证据级别：源码调用链；[官方文档](https://developer.android.com/media/grow/media-projection#status_bar_chip_and_auto_stop)明确 Android 15 QPR1+ 锁屏会停止投屏。当前绕行方式是先退出识别框，再重新开始。应把投屏失效状态传回主界面，并提供保留会话的重新授权入口。

### 6. 悬浮队列达到高度上限后，新任务不可见且不能滚动

位置：[OverlayTaskQueue.kt:54](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/overlay/OverlayTaskQueue.kt:54)、[OverlayTaskQueue.kt:86](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/overlay/OverlayTaskQueue.kt:86)。

面板使用普通 Column 和 `tasks.forEach`，没有 LazyColumn 或 verticalScroll。服务把窗口高度限制为屏幕的 45%，又按旧到新排列；内容超过上限后，底部新任务及其取消/重试操作被窗口截断。README 的“最新任务始终可见”无法成立。

证据级别：布局与窗口尺寸代码；待设备确认不同密度下的触发任务数。应使用受限高度的滚动列表，并跟随最新任务。另有需求缺口：展开区目前只展示 firstLine/summary 的最多三行，没有直接显示完整回答和追问输入，需跳到主界面。

### 7. 任务详情不刷新新回答，“重新回答”仍选中旧答案

位置：[MainViewModel.kt:364](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/ui/MainViewModel.kt:364)、[MainViewModel.kt:246](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/ui/MainViewModel.kt:246)。

任务执行中打开详情，`mainAnswer` 在打开时取一次；完成事件仅替换 `task/error`，流式更新也只更新会话卡片，详情仍是旧内容或“暂无内容”。点击重新回答时，新回复追加到 messages，但重新打开详情仍取第一条 Assistant，重试后的答案会被归入后面的追问区。

证据级别：源码与消息写入调用链；未做 UI 复现。应明确首次回答/重试版本与追问的归属，并让完成事件刷新详情正文。

### 8. 追问中途停止、超时或失败时，已生成回复只留在内存

位置：[AppContainer.kt:287](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/AppContainer.kt:287)、[AppContainer.kt:302](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/AppContainer.kt:302)。

用户消息在请求前已经入库，Assistant 消息仅在 collect 正常结束后入库。停止、超时、网络异常会直接跳过保存；ViewModel 的 `markInterrupted()` 只改 UI 状态。退出详情再进入，刚才看见的部分回复消失，历史只剩用户问题，也会影响下一轮上下文。

证据级别：源码；应在失败/取消收尾时保留部分内容及中断标志，写入完成后再更新界面。

### 9. 历史会话 StateFlow 未被 Compose 订阅

位置：[MainActivity.kt:124](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/MainActivity.kt:124)、[MainActivity.kt:178](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/MainActivity.kt:178)。

`AppRoot` 订阅了详情、展开项、任务映射等，但没有订阅 `mainViewModel.sessions`，只在组合时调用 `filteredSessions()` 读取 `_sessions.value`。仅会话列表发生变化时不会触发重组，首次异步加载、重命名或删除可能一直显示旧数据，直到其他被订阅的状态发生变化。

证据级别：Compose 状态读取代码；是否被其他刷新偶然掩盖取决于运行时序。应显式收集会话流，或暴露组合好的过滤结果 StateFlow。

### 10. 从设置返回悬浮窗后仍使用缓存的旧模型配置

位置：[OverlayService.kt:559](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/overlay/OverlayService.kt:559)、[OverlayService.kt:468](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/overlay/OverlayService.kt:468)。

`activeModel/modelsList` 仅在打开会话或悬浮菜单选择时刷新。识别框运行中进入主界面设置，修改 Key/URL、新增/删除模型或设为当前，再返回识别框时，`showWindows()` 仅改可见性，下一次任务继续使用旧 ModelConfig，菜单列表也不更新。

证据级别：模型缓存读写调用链。应在恢复显示前刷新配置，或订阅统一配置状态。

### 11. OpenAI 兼容流的结束事件会丢弃同一事件中的文字

位置：[OpenAiCompatibleClient.kt:45](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/core/llm/OpenAiCompatibleClient.kt:45)。

解析器读到了 `delta.content`，但一旦同一 chunk 的 finish_reason 为 stop/length/content_filter，就直接返回 Done，不输出该段文字。兼容网关如果把最后文字和结束原因放在同一事件，回答会截尾；单事件回复甚至会被任务执行器视为无内容。

已由 `finalChunkContentMustNotBeDiscarded` 复现：输入携带 `content="last"` 和 `finish_reason="stop"`，期望 `last`，实际为空串。应先发送该事件中的有效文本，再处理结束状态。

### 12. 半尺寸截图的裁剪坐标缩放方向相反

位置：[ScreenCapture.kt:95](D:/Days/Days-项目/RegionX/android/app/src/main/java/com/regionx/app/platform/ScreenCapture.kt:95)。

代码使用 `screenWidth / bitmapWidth` 后乘输入坐标。若降级到半尺寸，倍率变成 2，实际应是 0.5。例如 1080px 屏幕、540px 图片，left=400 应映射到 200，当前会算成 800 再夹到图片右缘，截图位置和宽度都会错误。

证据级别：公式核对。正常全尺寸时倍率为 1，不触发；当前在 Android 14+ 会先被问题 1 阻断，旧系统或重构截图后仍需修正并测试缩放裁剪。

## Lint 与发布补充

- 第二个 Lint error 是 [local.properties:1](D:/Days/Days-项目/RegionX/android/local.properties:1) 中 `sdk.dir=C:/AndroidSdk` 的属性转义检查。它在本机没有阻止 AGP 找到 SDK，但会阻止 `lintDebug` 通过；与 Gemini 运行时问题分别处理。
- 17 个 warning 中多项是依赖版本更新提示，不应仅为清空警告批量升级依赖。明文 HTTP 是当前网络配置明确支持的局域网网关场景；本次没有将该设计本身认定为新增漏洞。
- 工程没有 Gradle Wrapper，目前依赖机器上预装的 Gradle；README 的默认 APK 路径也未反映中文路径时 `%TEMP%/regionx-gradle-build/app` 的实际输出位置。建议交付时补可复现构建入口与准确产物说明。
- Release 尚未配置签名；产物生成不等于安装、真实模型业务或商店发布通过。

## 回归用例与下一轮验收

审核用例：[AuditRegressionTest.kt](C:/Users/BaiUo/.codex/visualizations/2026/10/30/01a18c85-7630-7f73-825c-457574ad554b/android-review/AuditRegressionTest.kt)。结果：[JUnit XML](C:/Users/BaiUo/.codex/visualizations/2026/10/30/01a18c85-7630-7f73-825c-457574ad554b/android-review/results/TEST-com.regionx.app.audit.AuditRegressionTest.xml)。临时源集通过 init script 注入；因跨盘源码触发 Kotlin 增量缓存限制，最终使用仅本次命令生效的 `-Pkotlin.incremental=false` 成功编译并执行用例，4 个失败均为行为断言失败。

复现命令（PowerShell，运行目录为本项目 `android/`）：

```powershell
$env:JAVA_HOME = 'C:\Tools\jdk21-real'
& C:\Tools\gradle\bin\gradle.bat :app:testDebugUnitTest `
  --tests 'com.regionx.app.audit.AuditRegressionTest' `
  --init-script 'C:\Users\BaiUo\.codex\visualizations\2026\10\30\01a18c85-7630-7f73-825c-457574ad554b\android-review\audit.init.gradle' `
  '-Pkotlin.incremental=false' --offline --console=plain
```

审核结束后已不带 init script 恢复项目正常测试源集并验证构建成功；该恢复命令的测试任务命中本轮原有 19 项测试的缓存，不把它描述为再次实时执行。

建议先修 P1 和取消持久化，再安排以下设备验收：Android 10/12 的 Gemini；Android 14/16 同一授权连续截图至少 10 次；旋转及重复拖动/缩放；锁屏和系统停止录屏后的重新授权；8 个以上任务的队列滚动；静默 SSE 的取消和超时；重新回答/中断追问后的重进详情；设置改模型后返回悬浮窗；手势导航和三键导航、软键盘、系统返回键。所有这些设备验收当前均未完成。

# 桌面区域识别助手 · 技术栈要求

> 本文档与《需求文档》配套使用。标注"建议"的内容是我补充的推荐项，可按需调整。

## 1. 总体原则

- **无后端**：所有逻辑在客户端完成，数据全部存在本地
- **双端不同架构**：Windows 与 Android 各用原生方案，不追求代码共享，追求每端最轻便
- **Windows 先行**，Android 后做
- **两端共用同一套设计**：数据表结构、模型配置格式、LLM 调用协议保持一致，方便以后做历史导入导出
- 模型接口可自定义，通过**自研的轻量 LLM 网关层**统一调用 OpenAI 兼容接口、Claude、Gemini

---

## 2. Windows 端

### 2.1 核心技术

| 项目 | 选型 |
|---|---|
| 语言 / 运行时 | C# 12 / .NET 8 |
| UI 框架 | WPF |
| 架构模式 | MVVM（建议 CommunityToolkit.Mvvm） |
| 依赖注入 | Microsoft.Extensions.DependencyInjection（建议） |
| 本地数据库 | SQLite，通过 Microsoft.Data.Sqlite 访问（建议搭配 Dapper 简化查询） |
| 网络 | HttpClient（全局单例）+ 手写 SSE 流式解析 |
| JSON | System.Text.Json |
| 最低系统 | Windows 10 2004（19041）及以上（建议，截图排除自身功能的要求） |

### 2.2 关键功能的实现方案

| 功能 | 方案 |
|---|---|
| 透明识别框 | `AllowsTransparency=True`、`WindowStyle=None`、`Background=Transparent`；框内区域不设背景，使鼠标自动穿透，只给边框和缩放手柄着色 |
| 可移动 / 可缩放 | 自定义边框和 8 个缩放手柄，处理鼠标拖拽（或 `WindowChrome`） |
| 工具栏、任务队列跟随识别框 | 三个独立无边框窗口，监听识别框的位置和大小变化事件，同步更新；靠近屏幕边缘时自动翻转到另一侧 |
| 识别框作为主程序子窗口 | 设置 `Owner = MainWindow`，主程序退出时一并关闭 |
| 区域截图 | `Windows.Graphics.Capture`（首选）或 `Graphics.CopyFromScreen`（简单方案）；需处理多显示器和 DPI 缩放 |
| 截图时排除自身 | 对识别框、工具栏、任务队列三个窗口调用 `SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)`（P/Invoke） |
| 置顶 | `Topmost` 属性，由工具栏的"置顶窗口"按钮切换 |
| 系统托盘 | NotifyIcon（建议 Hardcodet.NotifyIcon.Wpf） |
| 全局快捷键（可选） | `RegisterHotKey`（P/Invoke） |
| Markdown 渲染 | 建议 Markdig 解析 + WPF 渲染（如 Markdig.Wpf），流式更新时做节流，避免频繁重绘 |
| 提取文字 | 默认调用视觉模型，提示词限制为"只输出图中文字"；可选 `Windows.Media.Ocr` 做离线识别 |

### 2.3 并发与任务调度

- 每个任务独立持有 `CancellationTokenSource`，支持取消和重试
- 用 `SemaphoreSlim(3)` 限制同时运行的任务数（2 到 3 个）
- 流式结果通过 `await foreach` 逐块追加；在 UI 线程发起，属性变更自动刷新界面
- 界面更新节流：建议 50 到 100ms 合并一次刷新

### 2.4 建议的项目结构

```
ScreenAssistant.sln
├─ ScreenAssistant.App        # WPF 界面（主窗口、识别框、工具栏、任务队列）
├─ ScreenAssistant.Core       # 领域模型、任务调度、会话管理
├─ ScreenAssistant.Llm        # LLM 网关层（统一接口 + 三种适配器）
├─ ScreenAssistant.Data       # SQLite 访问、迁移、图片文件管理
└─ ScreenAssistant.Platform   # 截图、窗口排除、热键等 Windows 原生调用
```

### 2.5 打包与发布（建议）

- 发布方式：`dotnet publish`，self-contained（用户无需安装运行时），可选单文件
- 安装包：Inno Setup 或 MSIX，二选一
- 暂不需要代码签名；正式分发前再考虑

---

## 3. Android 端

### 3.1 核心技术

| 项目 | 选型 |
|---|---|
| 语言 | Kotlin |
| UI 框架 | Jetpack Compose（Material 3） |
| 架构模式 | MVVM：ViewModel + StateFlow |
| 依赖注入 | Hilt（建议） |
| 本地数据库 | Room（底层 SQLite） |
| 设置存储 | DataStore |
| 网络 | OkHttp（含 SSE 支持） |
| JSON | kotlinx.serialization |
| 图片加载 | Coil |
| 异步 | Kotlin Coroutines + Flow |
| 最低版本 | minSdk 26（Android 8.0）（建议，因为悬浮窗类型 `TYPE_APPLICATION_OVERLAY` 从该版本开始） |

### 3.2 关键功能的实现方案

| 功能 | 方案 |
|---|---|
| 悬浮识别框 | `SYSTEM_ALERT_WINDOW` 权限 + `WindowManager` 添加悬浮视图（`TYPE_APPLICATION_OVERLAY`） |
| 屏幕截取 | `MediaProjection` + `ImageReader` / `VirtualDisplay`，再按识别框坐标裁剪 |
| 后台常驻 | 前台服务（类型 `mediaProjection`），带常驻通知 |
| 工具栏 / 任务队列 | 同样作为悬浮视图，跟随识别框 |
| 提取文字 | 默认调用视觉模型；可选 ML Kit 离线文字识别 |
| 限制 | 屏幕录制授权需用户确认（新版系统每次会话都可能要重新授权）；设置了 `FLAG_SECURE` 的界面无法截取 |

### 3.3 建议的模块结构

```
app/                    # 入口、导航
feature-overlay/        # 悬浮窗、截屏服务
feature-history/        # 历史会话、任务详情、追问
feature-settings/       # 模型配置
core-llm/               # LLM 网关层（与 Windows 端保持相同协议）
core-data/              # Room 数据库、图片文件管理
```

---

## 4. LLM 网关层（两端共用设计）

### 4.1 统一接口

- 上层只依赖一个"流式对话"接口：输入消息列表（可带图片），输出文字流
- 支持取消
- 具体实现由模型配置里的"类型"决定

### 4.2 三种适配器

| | OpenAI 兼容 | Claude | Gemini |
|---|---|---|---|
| 请求地址 | `{baseUrl}/chat/completions` | `https://api.anthropic.com/v1/messages` | `.../v1beta/models/{model}:streamGenerateContent?alt=sse` |
| 认证 | `Authorization: Bearer key` | `x-api-key` + `anthropic-version` | `x-goog-api-key` |
| 图片 | `image_url` + base64 data URL | `image` 块 + base64 | `inline_data` + base64 |
| 流式文字位置 | `choices[0].delta.content` | `content_block_delta` 事件的 `delta.text` | `candidates[0].content.parts[0].text` |
| 特殊要求 | 无 | 必须带 `max_tokens` | 无 |

### 4.3 模型配置项

每个模型一条配置：名称、接口类型、baseUrl、API Key、模型名、是否支持图片、可选的温度 / max_tokens、可选的自定义请求头。工具栏的"模型切换"从这份列表中选择。

### 4.4 网络与异常

- 支持自定义 HTTP 代理（设置页配置）
- 超时、网络错误、401 / 429 / 5xx 要给出明确的错误提示，并支持一键重试
- 流式中途断开时，保留已收到的内容并标记为"未完成"，可重试

### 4.5 提示词

- 回答 / 翻译 / 提取图文各有一份系统提示词，可在设置中修改
- 翻译的目标语言可在设置中配置

---

## 5. 数据存储（两端共用设计）

### 5.1 原则

- 全部本地存储，不上传到任何自建服务
- 截图作为文件存放在应用数据目录，数据库只存路径
- 会话和任务永久保留，支持删除单个任务、删除整个会话

### 5.2 表结构（建议）

| 表 | 主要字段 |
|---|---|
| `sessions` | id、名称、创建时间、更新时间 |
| `tasks` | id、session_id、类型（回答 / 翻译 / 提取）、标题、简介、截图路径、截取区域、状态、模型 id、创建时间 |
| `messages` | id、task_id、角色（user / assistant）、内容、创建时间 |
| `model_configs` | id、名称、类型、baseUrl、加密后的 Key、模型名、是否支持图片、其他参数 |
| `settings` | 键值对（提示词、翻译目标语言、代理、并发数等） |

### 5.3 存储位置

- Windows：`%LOCALAPPDATA%\ScreenAssistant\`（数据库 + `images/` 目录）
- Android：应用私有目录

### 5.4 历史导出 / 导入（可选）

- 导出为 zip（JSON + 图片），用于换设备或在 Windows 与 Android 之间迁移
- 两端表结构一致，所以导入导出格式也可以通用

---

## 6. 安全与隐私

- **API Key 加密保存**：Windows 使用 DPAPI（`ProtectedData`），Android 使用 Keystore 加密；不得明文存储，不得写入日志
- 截图只发送给用户当前选择的模型接口，不发给其他任何地址
- 软件不自带任何远程服务、统计或遥测
- 在设置页提示用户：截图内容会发送到所选模型服务商

---

## 7. 非功能要求

| 项目 | 要求 |
|---|---|
| 并发 | 同时运行任务不超过 2 到 3 个，其余排队 |
| 响应 | 点击按钮后立即截图并显示任务卡片，不等待模型返回 |
| 流式 | 结果逐字显示 |
| 内存 | 任务完成后及时释放内存中的截图，仅保留文件路径 |
| 稳定性 | 单个任务失败不影响其他任务和识别框 |
| 日志 | 本地日志，便于排查问题，不记录 Key 和截图内容 |

---

## 8. 开发与测试

- 版本管理：Git
- Windows：xUnit 测试 LLM 适配器的流式解析、任务调度、数据库读写（用录制的 SSE 响应样例做测试数据）
- Android：JUnit 测试同样的逻辑，Compose UI 测试可选
- 建议先单独验证三个适配器（命令行或测试项目），再接入界面

---

## 9. 待确认事项

1. **"LLM Gateway"的含义**：是指软件内自带的适配层（本文档的设计），还是某个需要联网的网关服务？如果是后者，它只是"OpenAI 兼容接口"的一个配置项
2. **提取图文的识别方式**：默认用视觉模型，还是默认用本地 OCR？
3. **Windows 界面风格**：使用 WPF 原生控件，还是引入第三方控件库（如 WPF-UI）做 Win11 风格？
4. **安装包形式**：Inno Setup 安装程序，还是免安装的绿色版？
5. **Android 最低版本**：是否接受 Android 8.0（API 26）作为最低版本？

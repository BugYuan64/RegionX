# RegionX

区域识别工具：屏幕上出现一个可移动、可缩放的透明识别框，框内内容交给已配置的 AI 模型处理
（问答 / 翻译 / 提取图文），任务、截图与追问历史全部保存在本地。

- **Windows 版**：`windows/`（C# / .NET 8 + WPF，已定稿）
- **Android 版**：`android/`（Kotlin + Jetpack Compose，适配 Android 16 / API 36；默认全屏截图，可切换区域模式，悬浮球问答与任务队列）

两端架构各自独立、互不共享代码，但**数据表结构与模型配置格式保持一致**，
模型接口统一走各自内置的轻量网关层（OpenAI 兼容 / Claude / Gemini）。

| | Windows | Android |
|---|---|---|
| 入口 | `windows/README.md` | `android/README.md` |
| 源码 | `windows/src` | `android/app/src` |
| 测试 | `windows/tests` | `android/app/src/test` |
| 参考文档 | `windows/screen-assistant-requirements.md`、`windows/screen-assistant-tech-stack.md`、`windows/区域识别助手 UI 方案.html` | `android/README.md`（Android 独立交互及平台差异） |

Windows 需求与 UI 方案保留在 `windows/`；Android 当前交互以 `android/README.md` 为准，
采用单一启用上游、问答/退出悬浮球和历史会话任务队列。

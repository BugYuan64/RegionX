# RegionX · Windows 版

> 根目录 `README.md` 是项目总览；Android 版在 `../android/`。
> 本文档中的所有命令都在 `windows/` 目录下执行。

## 直接运行（不需要安装）

```
dotnet run --project src/ScreenAssistant.App
```

首次启动会自动打开设置页，需要先填一条模型配置才能用。

## 当前目录与运行入口

- `src/`：正式源码与程序资源。
- `tests/`：自动化回归测试。
- `tools/`：可复用的 UI 调试、模拟接口和图标校验工具；`tools/icons/svg/` 保留图标来源，许可证由项目构建引用。
- `publish/ScreenAssistant.exe`：当前最新完整版本，已包含高 DPI 截图修复。
- `publish/RegionX-win-x64-20261029-capturefix.zip`：对应便携发行包及同名 `.sha256` 校验文件。
- `publish/data/`：配置、任务历史、实际任务截图和日志。

调试截图、过期发行包、重复可执行文件、图标探测下载缓存及 `bin/obj` 构建中间文件已清理；下次编译会重新生成需要的构建缓存。需求文档、技术方案与 HTML UI 设计稿作为项目参考保留。

## 生成绿色版（单文件，免安装）

```
dotnet publish src/ScreenAssistant.App -c Release -o publish
```

参数已写在 `ScreenAssistant.App.csproj` 里（`win-x64` / 自带运行时 / 单文件），
所以上面一条命令即可。产物是 `publish/ScreenAssistant.exe`，约 160 MB
（内含 .NET 运行时），复制到任意 Windows 10 2004+ 机器双击就能运行。

如果目标机器已经装了 .NET 8 桌面运行时，想要更小的包，可改用框架依赖模式：

```
dotnet publish src/ScreenAssistant.App -c Release -o publish-fd --no-self-contained
```

## 首次使用步骤

1. 双击 `ScreenAssistant.exe`，自动弹出「设置」→「模型」。
2. 点击「新增」，填写：
   - 配置名称（随便起）
   - 接口类型（OpenAI 兼容 / Claude / Gemini）
   - Base URL（OpenAI 兼容需填，如 `https://api.openai.com/v1`）
   - API Key（DPAPI 加密后保存在本机，不写日志）
   - 点「拉取模型列表」→ 模型名会变成下拉框，从上游列表里选
   - 点击「保存此模型」或底部「保存设置」，两者都会保存正在编辑的模型
   - 保存后编辑区清空，左侧保留已保存配置；重新选择该配置可继续编辑或设为当前模型
   - 第一条保存的模型自动设为当前模型；拉取的模型列表和已保存配置均按模型名排序
3. 回到主界面点「开始识别」，出现识别框、工具栏、任务队列。
4. 把识别框拖到要识别的区域，点「回答 / 翻译 / 提取图文」。
5. 队列每张任务卡片右侧的小号时间为任务创建时间（时:分:秒），悬停可查看完整日期；完成、重试或追问不会把它改成更新时间。
6. 「置顶」同时控制识别框、工具栏和任务队列；取消置顶后它们仍作为一组窗口使用。

## 数据存放位置

随程序附带 `regionx.portable.json`，默认使用 **程序所在目录的 `data\`**（例如本项目发布版为 `publish\data\`）：

```
data\
├─ models.json            模型配置（API Key 和附加请求头使用当前 Windows 用户的 DPAPI 加密）
├─ settings.json          通用设置、当前模型、主题、提示词等
├─ screenassistant.db     会话、任务、消息；旧版模型和设置仅用于首次迁移
├─ images\                每次任务的截图
└─ logs\error.log         运行错误（不含 Key 和截图内容）
```

配置文件采用临时文件写入后原子替换，并以跨进程互斥锁保护模型列表写入。保存失败时保留表单输入并显示错误。
没有新版配置文件时，自动读取同一数据目录中的旧数据库；旧版仅带掩码的 `models.json` 会从数据库迁移为完整配置。
移除便携标记后使用 `%LOCALAPPDATA%\ScreenAssistant\`，不会自动搬迁其他目录里的历史数据。
DPAPI 配置复制到其他 Windows 用户后需重新填写 API Key。删除整个数据目录会清空配置和历史。

## 常见问题

- **窗口在截图/录屏里看不到**：这是刻意设计，识别框、工具栏、任务队列、主窗口都设置了
  `WDA_EXCLUDEFROMCAPTURE`，避免截图时把自身拍进去。调试时可设环境变量
  `SA_NO_CAPTURE_EXCLUDE=1` 临时关闭。
- **识别框点了没反应**：识别框内区域是鼠标穿透的，要拖边框或四角手柄；
  点按钮请用下方的工具栏。
- **点击「回答」后没有结果**：看任务队列里的状态和错误文案；401 是 Key 不对，
  429 是额度/频率限制，超时可调大「高级」里的请求超时。
- **画面清晰但提示没有题目**：截图区域与屏幕边界统一使用物理像素，已修复高 DPI 缩放下截图右侧被错误裁掉的问题。更新前已生成的残缺截图无法靠重试补全，应重新截图创建任务。
- **重启后历史还在**：会话和任务永久保存在本地 SQLite 里。
- **退出程序**：通过托盘「退出」关闭全部窗口并异步释放任务队列；关闭主窗口仍是隐藏到托盘。
- **模型图标**：DeepSeek 使用鲸鱼标识；MiMo 使用 M 字标；Gemini 使用四角星；GLM 使用 Z.ai 标识。图标内置并随主题变色，无法识别的模型或缺少品牌资源时使用 GPT 图标。MiMo、Gemini、Z.ai 的来源见 `tools/icons/README.md`，发行包包含许可证。

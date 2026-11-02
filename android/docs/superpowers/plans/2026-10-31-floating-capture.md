# 截图模式与悬浮球实施计划

目标：按用户 2026-10-31 的具体交互要求更新 Android，沿用 Kotlin、Compose、MediaProjection 和现有队列。

- [x] 设置：新增 FullScreen（默认）/ Region 截图模式；上游下拉框单选，末项添加，每项可编辑，复用编辑页。
- [x] 模型：activeModelId 唯一标识启用上游，activeUpstreamModelName 保存该上游的当前模型；切换上游重置为配置默认模型。现有配置不删除。
- [x] 悬浮窗：问答球默认靠右，内侧三角打开固定高度的上游模型菜单，可滚动、选中打勾；问答球和退出球长按拖动。退出二次确认。
- [x] 截图：全屏模式不显示识别框；区域模式保留拖动缩放；截图时隐藏所有悬浮 UI。去除旧工具栏和外侧队列，任务创建短提示、历史队列保留。
- [x] 通知：任务完成用独立通知通道，正文只用答案第一行，点击进入详情。通知授权沿用系统机制。
- [x] 回归：覆盖默认与旧设置兼容、上游/模型切换、菜单布局边界及通知正文；运行全部单测、lint、debug/release 构建，更新 APK、校验和、README，尝试模拟器交互。

接口边界：Settings 负责 AppSettings.captureMode / activeUpstreamModelName；AppContainer 负责 activeModel()、selectUpstream(id)、selectUpstreamModel(name)；悬浮组件仅回调，不读配置/数据库；OverlayService 负责窗口、截图与模型列表请求。菜单正常固定 280dp，极小可用屏幕限制到屏内，方向按球上/下可用空间决定。

验证重点：旧设置默认全屏；删除启用配置后仅一个后继；模型拉取失败可重试并保留当前模型；拖动不触发问答/退出；横竖屏窗口边界；退出不取消已提交任务，完成通知在服务关闭后仍工作。

验证完成：75 项测试通过，Lint 0 errors / 16 warnings，Debug + Release 构建通过；Android 16 模拟器核心交互通过，物理设备与真实上游待验收。清理受自动审批策略限制，缓存和本计划暂时保留。

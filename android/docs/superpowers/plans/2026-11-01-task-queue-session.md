# 任务队列与会话修正

按本次用户明确要求直接实施，沿用 Android 现有 Compose、悬浮服务与 SQLite，不改 Windows。

- [x] 移除任务完成/失败通知、通知权限申请；保留录屏前台服务必需通知。
- [x] 任务队列与模型列表共用 173×187dp 尺寸，提升字体和行距。
- [x] 任务详情去掉同会话其他任务及其加载和回调。
- [x] 每次开始识别新建会话；现有录屏授权可用时复用授权，新旧任务按会话隔离。
- [x] 用户关闭队列时批量标记当前完成结果已读；新完成结果仍为未读。内部隐藏、旋转和截图不自动消费未读。
- [x] 先用任务完成通知与启动入口回归复现旧行为，再运行单元测试、Lint、Debug/Release 构建，核对 APK 与说明。

根因：MainActivity 根据 overlaySessionId 自动选择 resumeIntent，运行时走 showAgain；TaskDetailScreen 主动渲染 siblings；关闭队列只移除窗口；AppContainer 的持久化回调主动发结果通知。

验证：103 项测试，0 失败；Lint 0 错误/17 警告；Debug 与未签名 Release 构建成功，publish 下 APK 已更新并核对摘要。独立静态复核未发现问题。未提交或推送，未安装至手机，本轮未进行设备 UI 验收。

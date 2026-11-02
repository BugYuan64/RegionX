# 模型图标来源

MiMo、Gemini、GLM/Z.ai 的 SVG 来源：[Lobe Icons](https://github.com/lobehub/lobe-icons/tree/8b7bbc433d38437fa2b0cca58ae896917162b812/packages/static-svg/icons)，固定提交 `8b7bbc433d38437fa2b0cca58ae896917162b812`，获取日期 2026-10-29。

- `svg/gemini.svg`：四角星，对应 `BrandGemini`。
- `svg/zai.svg`：Z.ai 的 Z 标识，用于 GLM，对应 `BrandGlm`。
- `svg/xiaomimimo.svg`：小米 MiMo 字标；在 15–20 px 的图标位置采用下行第一个 M 的两个子路径，保留原始曲线，对应 `BrandMimo`，避免完整字标在小尺寸下无法辨认。

运行时资源转换为 `src/ScreenAssistant.App/Styles/BrandIcons.xaml` 的 WPF Geometry；使用填充绘制，去掉额外描边，颜色跟随主题。

许可证保存在 `LICENSE-lobe-icons`（MIT），构建和发布时复制为 `THIRD-PARTY-NOTICES.txt`，发行 ZIP 同样包含该文件。

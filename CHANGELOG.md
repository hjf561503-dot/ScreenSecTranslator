# Changelog

## 2.0.0

- 将默认流程改为设备端实时 OCR、离线中英翻译与自动覆盖，不再依赖云端 API。
- 增加 0.7–3.0 秒刷新间隔、画面指纹跳帧、600 条 LRU 翻译缓存和覆盖层自隐藏。
- 增加网络安全本地术语表，并保护 URL、IP、CVE、哈希、路径、命令和常见缩写。
- 增加 ML Kit 离线模型预下载、运行状态和 Google Translate 归属标识。
- 将在线 AI 改为默认关闭的手动长按精译，增加 Gemini 免费层优先与 OpenAI 后备。
- 增加 Android 16 使用说明、隐私说明和免费层数据使用提示。
- 增加推送/PR/每周构建监控、CodeQL、依赖审查、Dependabot、CODEOWNERS 和 CI 密钥扫描。

## 1.0.0

- 首次实现 Android 悬浮球与单帧屏幕翻译。
- 增加 Android 14+ MediaProjection 前台服务支持。
- 增加自然中文与网络安全术语提示词。
- 增加 OpenAI 视觉输入、原始细节和严格结构化输出。
- 增加译文坐标覆盖、旋转重设、代理鉴权和速率限制。
- 增加自定义术语表、无存储请求与提示注入防护。
- 增加 Node 单元测试和 GitHub Actions APK 构建工作流。

# Bubble Translate 4.2.8 APK 静态审查

## 范围与样本

- 样本：用户提供的 `Bubble Translate(气泡翻译) v4.2.8 .apk`
- SHA-256：`db0aa4dbec9715c446041cfad05b2b533d4827c76b43e04458b7147fe7b98417`
- 应用包名：`com.niven.translator`
- 方法：只读解包、字符串与反编译代码静态分析；没有安装、启动或调用样本中的网络接口。
- 本文不记录 APK 中可能出现的令牌、项目标识或其他凭据。

## 翻译能力结论

| 能力 | 静态证据 | 结论 |
| --- | --- | --- |
| OCR | 打包 Google ML Kit 中文、日文、韩文和拉丁 OCR 模型 | 真实的设备端 OCR |
| 本地翻译 | `MLTranslator` 使用 ML Kit `Translator` | 真实的设备端机器翻译，但不是大模型 AI |
| “AITranslation” | 数据对象由 ML Kit 翻译结果构造 | 名称含 AI，不代表调用生成式 AI |
| Google 翻译 | 调用 `translate.google.com/translate_a/single` | 非官方网页接口，不适合作为稳定产品 API |
| DeepL | WebView 打开 `deepl.com/.../translator#...` 并读取页面结果 | 网页流程，不是 DeepL 官方付费 API |
| 微软翻译 | 从 `edge.microsoft.com/translate/auth` 取得 Bearer 令牌，再访问 Translator 地址 | Edge 浏览器公开服务链路，不是样本内置的朋友付费订阅密钥 |
| 大模型 | 未发现 OpenAI、Gemini、Claude、Anthropic 等 SDK、端点或请求结构 | 无法证明存在已充值的大模型额度 |

因此，本项目没有直接调用或转移该 APK 的在线服务。若未来取得 API 账户所有者明确授权的官方密钥，应只配置在现有 Node 代理服务端，不能写进 APK、日志或公开仓库。

## 隐私与供应链观察

APK 同时包含 AdMob、AppLovin、Facebook Audience Network、Firebase Analytics/Crashlytics、Google Play Billing 和广告标识权限。直接复用整个 APK 或其二进制会把与本项目目标无关的广告、追踪、计费和较大的依赖面一起带入。

它还包含网页抓取和公开令牌链路，这些接口的可用性、条款、速率限制和隐私行为不由本项目控制。为避免服务突然失效、账户争议与屏幕内容外传，本项目拒绝移植这些实现。

## 独立吸收的工程思想

- 不只维护一个“正在下载”布尔值，而是区分下载状态与实际运行状态。
- 对 OCR 候选做位置去重，避免同一文字重复绘制。
- 使用字符/文字框的空间关系恢复拉丁文字的自然空格。
- 云端翻译必须保持用户主动触发，并与设备端翻译分开。

这些改进在 ScreenSecTranslator 中重新设计和独立实现；仓库不包含该 APK、反编译源码、签名文件、广告标识或任何第三方凭据。

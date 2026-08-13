# 🎓 水课王

安卓 App：**本地离线**实时转写课堂语音，自动识别老师的提问，并调用 **DeepSeek API** 在页面底部生成可折叠答案卡片——水课提问秒答。

```
麦克风(16kHz) → sherpa-onnx Paraformer 离线识别(实时转写, 中文优化+中英混说)
  → 端点检测断句 → 规则预过滤(问号/疑问词/祈使提问) → DeepSeek 确认是否提问
  → DeepSeek 生成答案(≤120字, 无称呼无客套) → 底部折叠卡片 + 通知提醒
```

## ✨ 特性

- 🎙️ **中文优化识别**：阿里达摩院 **Paraformer 双语流式模型**（sherpa-onnx 本地推理），准确率远超 Kaldi/Vosk 系中文模型，支持中英混说
- 🧠 **DeepSeek 智能判定**：规则预过滤 + LLM 确认，只对真问题出卡，成本约几分钱/节课
- ⚡ **3~5 秒出答案**：老师说完停顿约 1.4 秒即触发，答案简明扼要（直接答案 + 一句话理由，无称呼无客套）
- 🔋 **息屏常驻**：前台服务持续监听，通知栏常驻，小米澎湃OS「超级岛」胶囊显示
- 🛡️ **隐私友好**：音频只进本地识别器；API Key 加密存储，仅用于调用 DeepSeek
- 📥 **国内可达下载**：模型（约 226MB）首次启动从 hf-mirror 国内镜像下载，1~2 分钟就绪，无需翻墙

## 🚀 快速开始

1. Android Studio（Ladybug+）打开本项目，等待 Gradle 同步，真机 Run；
2. 授权麦克风/通知权限 → 设置页填入 DeepSeek API Key → 「测试连接」；
3. 设置页点「下载识别模型」（约 226MB，国内镜像）或直接点主页「开始监听」自动下载；
4. 说"为什么天空是蓝色的？"停顿 1 秒，答案卡片自动弹出。

> 转写在本地进行，问题判定与答案由 DeepSeek 生成，仅供课堂学习辅助。

## 环境要求

| 项 | 要求 |
|---|---|
| Android Studio | Ladybug 2024.2.1 或更新（内置 JDK 17+） |
| Android SDK | compileSdk 35 / minSdk 26（Android 8.0+） |
| 测试机 | 任意安卓真机（推荐 4GB 内存以上；模拟器无麦克风场景） |

## 构建运行

1. 打开 Android Studio → Open → 选择本项目目录，等待 Gradle 同步（sherpa-onnx 依赖已内置在 `app/libs/`）；
2. 连接手机（开启 USB 调试）→ Run `app`；
3. 首次启动：授权麦克风 + 通知权限；
4. 设置页填入 DeepSeek API Key → 点「测试连接」确认；
5. 下载识别模型（设置页或首次点「开始监听」时自动触发，hf-mirror 国内镜像，约 226MB）；
6. 回到主页点「开始监听」，对手机说话，转写区应实时出字。

> 命令行构建：`gradlew.bat assembleDebug`（国内网络慢可在 `gradle/wrapper/gradle-wrapper.properties` 把
> `distributionUrl` 换成 `https://mirrors.cloud.tencent.com/gradle/gradle-8.11.1-bin.zip`）。

## 模型下载（可选的手动方式）

App 默认从 hf-mirror 国内镜像自动下载三个文件（encoder.int8.onnx 158MB / decoder.int8.onnx 68MB / tokens.txt）。
如手机网络不便，可在电脑下载后用 adb 推送到手机：

```powershell
.\scripts\download_models.ps1   # 下载到 .\large-model\paraformer-zh\
adb push .\large-model\paraformer-zh /sdcard/Android/data/com.lecturelistener/files/models/paraformer-zh/
```

## 首次使用清单

- [ ] 授权麦克风、通知权限
- [ ] 设置页填 API Key 并测试连接
- [ ] 下载识别模型（约 226MB，建议 WiFi）
- [ ] （可选）小米/华为等按设置页「后台保活」指引开启自启动/无限制
- [ ] 主页点「开始监听」→ 息屏测试：锁屏后问一句「为什么天空是蓝色的？」

## 使用建议

- 手机放桌面、离讲台越近识别越准；教室噪音大时把「断句灵敏度」调大（1.5~2s）；
- 识别到问题后：页面底部自动出新卡（自动展开）、通知栏/小米超级岛同步提醒（可开震动）；
- 卡片可展开/折叠（点标题）、长按答案可复制、失败可重试、可删除；「清空会话」一键清空。

## DeepSeek 成本估算

- 判定调用约 300 tokens/次，答题约 200 tokens/次；一节 90 分钟课总消耗约 2 万 tokens，
  按[官方定价](https://api-docs.deepseek.com/zh-cn/quick_start/pricing/)估算**远低于 1 元/节课**。

## 后台保活（厂商设置路径）

| 厂商 | 设置 |
|---|---|
| 小米/Redmi | 设置→应用设置→应用管理→本应用→自启动；省电策略→无限制 |
| 华为/荣耀 | 设置→应用→应用启动管理→手动管理→自启动/关联启动/后台活动全开 |
| OPPO/一加 | 设置→电池→应用耗电管理→允许后台运行；权限→自启动 |
| vivo/iQOO | i管家→应用管理→权限→自启动；后台高耗电→允许 |

> 小米「灵动岛/超级岛」是系统级胶囊 UI，会自动把 App 的常驻通知胶囊化展示，无需额外适配；
> 个别机型不胶囊化时通知栏常驻兜底，功能不受影响。

## 常见问题

**转写不出字？**
- 检查麦克风权限与模型是否已下载（设置页显示「✓ 当前模型已就绪」）；
- 对手机说话试 3 秒以上并停顿（断句需 ≥3 字）；
- 日志查看：`adb logcat | findstr lecturelistener`（异常会在 App 顶部横幅显示）。

**卡片不出现？**
- 说完整问句（如「为什么天空是蓝色的？」）并停顿 1 秒以上；
- 确认 API Key 已填且测试连接成功；无问号的提问（"谁来说说"）默认规则已覆盖，可开「严格判定模式」。

**模型下载失败？**
- 设置页「重试下载」；App 会按 hf-mirror 国内镜像 → Hugging Face 官方源的顺序自动切换；
- 仍失败就用电脑下载 + adb 推送（见上文「模型下载」章节）。

**API Key 安全？**
- Key 存于 EncryptedSharedPreferences（AES256 加密）；个别国产 ROM 密钥库异常时自动回退普通存储并在设置页可见。

## 已知限制（v0.2）

- 嘈杂教室、方言、远场拾音仍会影响识别精度（Paraformer 已比 Vosk 显著改善）；
- 学生回答声/讨论声可能被误判为提问（可一键删除卡片）；
- Android 15/16 对 microphone 前台服务有时长限制，超长课（>6h）系统可能回收服务；
- 答案生成延迟约 3~5 秒（断句 1.4s + DeepSeek 1~3s）。

## 项目结构

```
app/src/main/java/com/lecturelistener/
├── LectureApp.kt            全局 Context
├── asr/                     SherpaEngine(识别器)、ModelManager(模型下载/加载)
├── detect/                  QuestionRules(规则预过滤)、TextSimilarity(去重)
├── llm/                     DeepSeekClient、AnswerEngine(问题→答案管线)
├── service/                 MicListenerService(前台服务)、NotificationHelper
├── data/                    Models、SessionRepository、SettingsStore
└── ui/                      MainActivity、MainScreen、QuestionCards、SettingsScreen、ViewModel
```

## 免责声明

本工具仅供课堂学习辅助，请遵守学校课堂纪律与相关法律法规；识别结果与 AI 答案仅供参考。

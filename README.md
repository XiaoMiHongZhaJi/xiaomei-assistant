# 小美助手

小美助手通过 LSPosed HOOK为小米手环接入 AI 大模型，替换小爱同学的回复。
基于LSP2.0开发，请使用最新框架
适配小米运动健康 3.55.0

English documentation: [README.en.md](README.en.md)

## 它能做什么

小美助手会在小米运动健康 "我的"页面增加一个“小美助手”入口，你可以在这里配置模型、查看接管状态、管理会话、维护长期记忆，并调整语音接管规则。

语音侧会接入 AIVS 的 final ASR 和文本回复链路：当识别到手环发送过来的语音时，模块会把当前会话上下文和长期记忆一起交给已配置的 LLM，然后用模型回复替换官方小爱同学回复。天气、设备控制、系统指令等结构化场景默认放行官方逻辑。

会话管理用于隔离短期上下文。不同话题可以切到不同会话，避免上一轮聊天内容污染下一次请求；长期记忆则独立保存，用来记录稳定偏好和长期事实。

LLM 侧目前覆盖 OpenAI Completions、OpenAI Responses 和 Xiaomi MiMo Completions。黑名单可按关键词或正则配置，命中后直接放行官方回复，不请求模型。

## 项目结构

```text
app/                         模块 App、设置页、同步 Service 与 Xposed 入口声明
runtime/host/                宿主进程共享运行时、Hook、仓库、LLM 与状态逻辑
libs/stub/                   编译期 Android / libxposed stub
libs/libxposed/service/      libxposed service 接口依赖
gradle/                      Gradle wrapper 配置
```

## 构建

准备 Android SDK 后执行：

```powershell
.\gradlew.bat :app:assembleDebug
```

构建产物位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 配置说明

LLM Provider、Base URL、API Key、模型、会话、记忆和接管规则均在运行时通过设置页配置。

## 免责声明

本项目面向授权设备和个人研究环境。使用前请确认你对目标设备、系统环境和宿主应用具备相应控制权，并自行承担兼容性维护成本。

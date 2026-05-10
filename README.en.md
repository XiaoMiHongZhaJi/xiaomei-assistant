# XiaoMei Assistant

XiaoMei Assistant connects Xiaomi Band devices to AI large language models through LSPosed hooks and replaces XiaoAi's replies.
It is developed for LSP 2.0, so please use the latest framework.
Target host version: Xiaomi Fitness 3.55.0.

Chinese documentation: [README.md](README.md)

## What It Does

XiaoMei Assistant adds a "XiaoMei Assistant" entry to the "Mine" page in Xiaomi Fitness. From there you can configure the model, inspect interception status, manage conversations, maintain long-term memories, and adjust voice interception rules.

On the voice side, the module connects to the AIVS final ASR and text reply flow. When it recognizes voice input sent from the band, it sends the current conversation context and long-term memories to the configured LLM, then replaces the official XiaoAi reply with the model response. Structured scenarios such as weather, device control, and system instructions are left to the official flow by default.

Conversation management isolates short-term context. You can switch between conversations for different topics so previous messages do not pollute the next request. Long-term memory is stored separately for stable preferences and durable facts.

The current LLM layer supports OpenAI Completions, OpenAI Responses, and Xiaomi MiMo Completions. Blacklist rules can be configured with keywords or regular expressions; matched requests bypass the model and keep the official reply.

## Project Layout

```text
app/                         Module app, settings UI, sync service, and Xposed metadata
runtime/host/                Host runtime, hooks, repositories, LLM layer, and status stores
libs/stub/                   Compile-time Android / libxposed stubs
libs/libxposed/service/      libxposed service interface dependency
gradle/                      Gradle wrapper files
```

## Build

After preparing the Android SDK, run:

```powershell
.\gradlew.bat :app:assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Configuration

LLM provider, Base URL, API key, model, conversations, memories, and interception rules are configured at runtime from the settings UI.

## Disclaimer

This project is intended for authorized devices and personal research environments. Make sure you control the target device, system environment, and host application before use, and expect compatibility maintenance across host app updates.

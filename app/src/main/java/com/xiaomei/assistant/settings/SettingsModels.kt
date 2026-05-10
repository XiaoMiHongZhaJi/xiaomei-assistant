package com.xiaomei.assistant.settings

import com.xiaomei.assistant.model.LlmConfig
import com.xiaomei.assistant.model.ConversationState
import com.xiaomei.assistant.model.MemoryRecord
import com.xiaomei.assistant.model.SessionRecord

data class SettingsDiagnostics(
  val architecture: String = "modern LSPosed / qa-style settings",
  val hostPackage: String = "com.mi.health",
  val hostVersion: String = "未知",
  val hookStatus: String = "等待宿主注入",
  val replyPipeline: String = "复用官方 AIVS/ASR，仅替换官方 reply",
  val hostEntry: String = "Mine RN + AboutActivity",
  val officialAsr: String = "由小米运动健康提供",
  val aivsStatus: String = "等待 :device 链路验证",
  val mainProcessSummary: String = "等待主进程注入",
  val deviceProcessSummary: String = "等待 :device 注入",
  val ignoredProcessSummary: String = "",
  val mainLastHookTimeText: String = "从未",
  val deviceLastHookTimeText: String = "从未",
  val aivsEvent: String = "等待语音链路验证",
  val finalAsr: String = "",
  val llmStatus: String = "未开始",
  val llmAnswerPreview: String = "",
  val fallbackReason: String = "",
  val lastHistoryPersistText: String = "未落库",
  val aivsLastUpdatedText: String = "从未",
  val llmTestStatus: String = "未测试",
  val llmTestInProgress: Boolean = false,
  val lastError: String = "",
  val lastHookTimeText: String = "从未",
  val memoryState: String = "idle",
  val memoryActionCount: Int = 0,
  val memoryError: String = "",
  val lastMemoryUpdateText: String = "从未",
  val aivsHookPoint: String = "",
  val aivsHookHits: String = "",
  val aivsHookError: String = "",
  val aivsLastSendInstruction: String = "",
  val activeConversationTitle: String = "默认会话",
  val activeConversationMessageCount: Int = 0,
  val activeConversationUpdatedText: String = "从未"
)

data class SettingsUiState(
  val config: LlmConfig = LlmConfig(),
  val history: List<SessionRecord> = emptyList(),
  val conversationState: ConversationState = ConversationState(),
  val memories: List<MemoryRecord> = emptyList(),
  val diagnostics: SettingsDiagnostics = SettingsDiagnostics()
)

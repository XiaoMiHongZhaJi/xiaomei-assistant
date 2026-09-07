package com.xiaomei.assistant.model

import android.annotation.SuppressLint
import com.xiaomei.assistant.xposed.HookLog
import kotlinx.serialization.Serializable

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class LlmConfig(
  val baseUrl: String = "https://api.openai.com",
  val apiKey: String = "",
  val model: String = "gpt-4.1-mini",
  val provider: String = LlmProvider.OPENAI,
  val apiMode: String = LlmApiMode.OPENAI_CHAT_COMPLETIONS,
  val systemPrompt: String = "你是小美，一名简洁、直接、可靠的中文 AI 助手。回答要自然，尽量像手环上的小爱同学。",
  val temperature: Float = 0.7f,
  val maxTokens: Int = 1024,
  val asrBlacklistEnabled: Boolean = false,
  val asrBlacklistRules: List<AivsAsrBlacklistRule> = emptyList(),
  val customCommandEnabled: Boolean = true,
  val customCommandRules: List<CustomCommandRule> = emptyList()
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class AivsAsrBlacklistRule(
  val id: String,
  val pattern: String,
  val enabled: Boolean = true,
  val regex: Boolean = false,
  val contains: Boolean = true,
  val createdAt: Long,
  val updatedAt: Long
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class CustomCommandRule(
  val id: String,
  val name: String,
  val pattern: String,
  val command: String,
  val enabled: Boolean = true,
  val regex: Boolean = false,
  val contains: Boolean = true,
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = System.currentTimeMillis()
)

data class CustomCommandMatch(
  val rule: CustomCommandRule,
  val input: String,
  val groups: List<String>
)

object CustomCommandMatcher {
  fun match(
    input: String,
    config: LlmConfig
  ): CustomCommandMatch? {
    if (!config.customCommandEnabled || input.isBlank()) {
      return null
    }
    var rules = config.customCommandRules
    for (rule in rules) {
      if (!rule.enabled) continue

      val result = if (rule.regex) {
        matchRegex(input, rule)
      } else {
        matchKeyword(input, rule)
      }

      if (result != null) {
        return CustomCommandMatch(
          rule = rule,
          input = input,
          groups = result
        )
      }
    }

    return null
  }

  private fun matchKeyword(
    input: String,
    rule: CustomCommandRule
  ): List<String>? {
    return if (rule.contains) {
      if (input.contains(rule.pattern)) {
        emptyList()
      } else {
        null
      }
    } else {
      if (input == rule.pattern) {
        emptyList()
      } else {
        null
      }
    }
  }

  private fun matchRegex(
    input: String,
    rule: CustomCommandRule
  ): List<String>? {
    val regex = try {
      Regex(rule.pattern)
    } catch (_: Exception) {
      return null
    }

    val match = if (rule.contains) {
      regex.find(input)
    } else {
      regex.matchEntire(input)
    } ?: return null

    return match.groupValues.drop(1)
  }

  fun validate(pattern: String, regex: Boolean): String? {
    val trimmed = pattern.trim()
    if (trimmed.isBlank()) {
      return "规则内容不能为空"
    }
    if (regex) {
      val error = runCatching { Regex(trimmed) }.exceptionOrNull()
      if (error != null) {
        return "正则表达式无效：${error.message ?: error.javaClass.simpleName}"
      }
    }
    return null
  }
}

object CustomCommandExecutor {

  fun executeAsync(
    match: CustomCommandMatch
  ) {
    Thread {
      try {
        val command = expandCommand(match)

        HookLog.i(
          "AIVS custom command executing " +
                  "rule=${match.rule.id} " +
                  "command=${command.take(300)}"
        )

        val process = ProcessBuilder(
          "/system/bin/sh",
          "-c",
          command
        )
          .redirectErrorStream(true)
          .start()

        val exitCode = process.waitFor()

        HookLog.i(
          "AIVS custom command finished " +
                  "rule=${match.rule.id} " +
                  "exitCode=$exitCode"
        )

      } catch (e: Throwable) {
        HookLog.e(
          "AIVS custom command failed " +
                  "rule=${match.rule.id}",
          e
        )
      }
    }.start()
  }

  private fun expandCommand(
    match: CustomCommandMatch
  ): String {
    var command = match.rule.command

    command = command.replace(
      "{input}",
      match.input
    )

    match.groups.forEachIndexed { index, value ->
      command = command.replace(
        "$${index + 1}",
        value
      )
    }

    return command
  }
}

object AivsAsrBlacklistMatcher {
  fun firstMatch(text: String, config: LlmConfig): AivsAsrBlacklistRule? {
    if (!config.asrBlacklistEnabled || text.isBlank()) {
      return null
    }
    return config.asrBlacklistRules.firstOrNull { rule ->
      rule.enabled && matches(text, rule)
    }
  }

  fun matches(text: String, rule: AivsAsrBlacklistRule): Boolean {
    val pattern = rule.pattern.trim()
    if (pattern.isBlank()) {
      return false
    }
    return if (rule.regex) {
      runCatching {
        val regex = Regex(pattern)
        if (rule.contains) regex.containsMatchIn(text) else regex.matches(text.trim())
      }.getOrDefault(false)
    } else if (rule.contains) {
      text.contains(pattern)
    } else {
      text.trim() == pattern
    }
  }

  fun validate(pattern: String, regex: Boolean): String? {
    val trimmed = pattern.trim()
    if (trimmed.isBlank()) {
      return "规则内容不能为空"
    }
    if (regex) {
      val error = runCatching { Regex(trimmed) }.exceptionOrNull()
      if (error != null) {
        return "正则表达式无效：${error.message ?: error.javaClass.simpleName}"
      }
    }
    return null
  }
}

object LlmProvider {
  const val OPENAI = "openai"
  const val XIAOMI_MIMO = "xiaomi_mimo"
  const val GEMINI = "gemini"

  fun normalize(value: String): String {
    return when (value.trim().lowercase()) {
      XIAOMI_MIMO -> XIAOMI_MIMO
      GEMINI -> GEMINI
      else -> OPENAI
    }
  }
}

object LlmApiMode {
  const val OPENAI_CHAT_COMPLETIONS = "openai_chat_completions"
  const val OPENAI_RESPONSES = "openai_responses"
  const val MIMO_CHAT_COMPLETIONS = "mimo_chat_completions"
  const val GEMINI_GENERATE_CONTENT = "gemini_generate_content"

  fun normalize(provider: String, value: String): String {
    return when (LlmProvider.normalize(provider)) {
      LlmProvider.XIAOMI_MIMO -> MIMO_CHAT_COMPLETIONS
      LlmProvider.GEMINI -> GEMINI_GENERATE_CONTENT
      else -> when (value.trim().lowercase()) {
        OPENAI_RESPONSES -> OPENAI_RESPONSES
        else -> OPENAI_CHAT_COMPLETIONS
      }
    }
  }
}

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class SessionRecord(
  val sessionId: String,
  val question: String,
  val answer: String,
  val summary: String,
  val favorite: Boolean = false,
  val createdAt: Long,
  val updatedAt: Long
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ConversationRecord(
  val id: String,
  val title: String,
  val createdAt: Long,
  val updatedAt: Long,
  val messageCount: Int = 0,
  val lastMessagePreview: String = ""
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ConversationMessageRecord(
  val id: String,
  val conversationId: String,
  val role: String,
  val content: String,
  val createdAt: Long
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class ConversationState(
  val activeConversationId: String = "",
  val activeConversationUpdatedAt: Long = 0L,
  val conversations: List<ConversationRecord> = emptyList(),
  val messages: List<ConversationMessageRecord> = emptyList(),
  val migratedLegacyHistory: Boolean = false,
  val deletedConversationIds: List<String> = emptyList()
)

object ConversationMessageRole {
  const val USER = "user"
  const val ASSISTANT = "assistant"
}

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MemoryRecord(
  val id: String,
  val content: String,
  val enabled: Boolean = true,
  val source: String = MemorySource.MANUAL,
  val sourceSessionId: String = "",
  val createdAt: Long,
  val updatedAt: Long
)

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class MemoryToolAction(
  val action: String,
  val id: String? = null,
  val content: String? = null
)

object MemorySource {
  const val MANUAL = "manual"
  const val AUTO = "auto"
}

object MemoryToolActionType {
  const val CREATE = "create"
  const val EDIT = "edit"
  const val DELETE = "delete"
}

data class ModuleDiagnostics(
  val architecture: String = "纯 LSPosed 模块",
  val hostPackage: String = "com.mi.health",
  val hostVersion: String = "3.55.0 (355000)",
  val officialAsr: String = "由小米运动健康提供",
  val replyPipeline: String = "复用官方 AIVS/ASR，仅接管官方 reply 并替换为自定义 LLM",
  val hostEntry: String = "我的页面 -> 我的路线库上方",
  val hookStatus: String = "等待 3.55.0 宿主注入与 AIVS 链路验证",
  val lastError: String = "",
  val llmTestStatus: String = "未测试",
  val llmTestInProgress: Boolean = false
)

data class MainUiState(
  val config: LlmConfig = LlmConfig(),
  val history: List<SessionRecord> = emptyList(),
  val diagnostics: ModuleDiagnostics = ModuleDiagnostics()
)

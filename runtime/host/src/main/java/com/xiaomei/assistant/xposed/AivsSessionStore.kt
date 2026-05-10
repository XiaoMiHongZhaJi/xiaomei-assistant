package com.xiaomei.assistant.xposed

data class AivsSessionState(
  val did: String,
  val sessionId: Int = -1,
  val finalAsr: String? = null,
  val llmStarted: Boolean = false,
  val llmFinished: Boolean = false,
  val llmAnswer: String? = null,
  val llmSummary: String? = null,
  val replacementConsumed: Boolean = false,
  val asrBlacklisted: Boolean = false,
  val asrBlacklistRuleId: String = "",
  val asrBlacklistRulePreview: String = "",
  val createdAt: Long = System.currentTimeMillis(),
  val updatedAt: Long = createdAt
) {
  val historySessionId: String
    get() = "$did:$sessionId:$createdAt"
}

object AivsSessionStore {
  private val sessions = mutableMapOf<String, AivsSessionState>()

  @Synchronized
  fun beginSession(did: String, sessionId: Int? = null): AivsSessionState {
    val now = System.currentTimeMillis()
    val previous = sessions[did]
    val state = AivsSessionState(
      did = did,
      sessionId = sessionId ?: previous?.sessionId ?: -1,
      createdAt = now,
      updatedAt = now
    )
    sessions[did] = state
    return state
  }

  @Synchronized
  fun updateFinalAsr(did: String, text: String): AivsSessionState {
    val now = System.currentTimeMillis()
    val current = sessions[did] ?: AivsSessionState(did = did, createdAt = now, updatedAt = now)
    val updated = current.copy(
      finalAsr = text,
      updatedAt = now
    )
    sessions[did] = updated
    return updated
  }

  @Synchronized
  fun markAsrBlacklisted(did: String, ruleId: String, rulePreview: String): AivsSessionState? {
    val current = sessions[did] ?: return null
    val updated = current.copy(
      asrBlacklisted = true,
      asrBlacklistRuleId = ruleId,
      asrBlacklistRulePreview = rulePreview,
      llmStarted = false,
      llmFinished = false,
      llmAnswer = null,
      llmSummary = null,
      updatedAt = System.currentTimeMillis()
    )
    sessions[did] = updated
    return updated
  }

  @Synchronized
  fun markLlmStarted(did: String): AivsSessionState? {
    val current = sessions[did] ?: return null
    if (current.llmStarted || current.asrBlacklisted) {
      return null
    }
    val updated = current.copy(
      llmStarted = true,
      llmFinished = false,
      updatedAt = System.currentTimeMillis()
    )
    sessions[did] = updated
    return updated
  }

  @Synchronized
  fun completeLlm(did: String, answer: String, summary: String): AivsSessionState? {
    val current = sessions[did] ?: return null
    val updated = current.copy(
      llmFinished = true,
      llmAnswer = answer,
      llmSummary = summary,
      updatedAt = System.currentTimeMillis()
    )
    sessions[did] = updated
    return updated
  }

  @Synchronized
  fun failLlm(did: String): AivsSessionState? {
    val current = sessions[did] ?: return null
    val updated = current.copy(
      llmFinished = true,
      llmAnswer = null,
      llmSummary = null,
      updatedAt = System.currentTimeMillis()
    )
    sessions[did] = updated
    return updated
  }

  @Synchronized
  fun markReplacementConsumed(did: String): AivsSessionState? {
    val current = sessions[did] ?: return null
    if (current.replacementConsumed) {
      return current
    }
    val updated = current.copy(
      replacementConsumed = true,
      updatedAt = System.currentTimeMillis()
    )
    sessions[did] = updated
    return updated
  }

  @Synchronized
  fun get(did: String): AivsSessionState? = sessions[did]

  @Synchronized
  fun endSession(did: String): AivsSessionState? = sessions.remove(did)
}

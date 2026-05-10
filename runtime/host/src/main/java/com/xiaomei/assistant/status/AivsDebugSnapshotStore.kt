package com.xiaomei.assistant.status

import android.content.Context
import android.util.Log
import com.xiaomei.assistant.bridge.ModuleSyncServiceClient
import kotlinx.serialization.Serializable

@Serializable
data class AivsDebugSnapshot(
  val did: String = "",
  val sessionId: Int = -1,
  val finalAsr: String = "",
  val llmState: String = "idle",
  val llmAnswerPreview: String = "",
  val replacementConsumed: Boolean = false,
  val lastFallbackReason: String = "",
  val lastInstructionSummary: String = "",
  val lastEvent: String = "等待语音链路验证",
  val lastError: String = "",
  val lastSuccessTimeMillis: Long = 0L,
  val lastFailureTimeMillis: Long = 0L,
  val lastHistoryPersistTimeMillis: Long = 0L,
  val memoryState: String = "idle",
  val memoryActionCount: Int = 0,
  val memoryError: String = "",
  val lastMemoryUpdateTimeMillis: Long = 0L,
  val lastHookPoint: String = "",
  val hookHitSummary: String = "",
  val hookLastError: String = "",
  val lastSendInstructionSummary: String = "",
  val lastUpdatedTimeMillis: Long = 0L
)

object AivsDebugSnapshotStore {
  private const val tag = "XiaoMeiHook"
  private const val preferencesName = "xiaomei_aivs_debug"
  private val hookSnapshotLock = Any()
  @Volatile private var hookSnapshot = AivsDebugSnapshot()
  private const val keyDid = "did"
  private const val keySessionId = "session_id"
  private const val keyFinalAsr = "final_asr"
  private const val keyLlmState = "llm_state"
  private const val keyLlmAnswerPreview = "llm_answer_preview"
  private const val keyReplacementConsumed = "replacement_consumed"
  private const val keyLastFallbackReason = "last_fallback_reason"
  private const val keyLastInstructionSummary = "last_instruction_summary"
  private const val keyLastEvent = "last_event"
  private const val keyLastError = "last_error"
  private const val keyLastSuccessTimeMillis = "last_success_time_millis"
  private const val keyLastFailureTimeMillis = "last_failure_time_millis"
  private const val keyLastHistoryPersistTimeMillis = "last_history_persist_time_millis"
  private const val keyMemoryState = "memory_state"
  private const val keyMemoryActionCount = "memory_action_count"
  private const val keyMemoryError = "memory_error"
  private const val keyLastMemoryUpdateTimeMillis = "last_memory_update_time_millis"
  private const val keyLastHookPoint = "last_hook_point"
  private const val keyHookHitSummary = "hook_hit_summary"
  private const val keyHookLastError = "hook_last_error"
  private const val keyLastSendInstructionSummary = "last_send_instruction_summary"
  private const val keyLastUpdatedTimeMillis = "last_updated_time_millis"

  fun read(context: Context): AivsDebugSnapshot {
    val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    return AivsDebugSnapshot(
      did = prefs.getString(keyDid, "").orEmpty(),
      sessionId = prefs.getInt(keySessionId, -1),
      finalAsr = prefs.getString(keyFinalAsr, "").orEmpty(),
      llmState = prefs.getString(keyLlmState, "idle").orEmpty(),
      llmAnswerPreview = prefs.getString(keyLlmAnswerPreview, "").orEmpty(),
      replacementConsumed = prefs.getBoolean(keyReplacementConsumed, false),
      lastFallbackReason = prefs.getString(keyLastFallbackReason, "").orEmpty(),
      lastInstructionSummary = prefs.getString(keyLastInstructionSummary, "").orEmpty(),
      lastEvent = prefs.getString(keyLastEvent, "等待语音链路验证").orEmpty(),
      lastError = prefs.getString(keyLastError, "").orEmpty(),
      lastSuccessTimeMillis = prefs.getLong(keyLastSuccessTimeMillis, 0L),
      lastFailureTimeMillis = prefs.getLong(keyLastFailureTimeMillis, 0L),
      lastHistoryPersistTimeMillis = prefs.getLong(keyLastHistoryPersistTimeMillis, 0L),
      memoryState = prefs.getString(keyMemoryState, "idle").orEmpty(),
      memoryActionCount = prefs.getInt(keyMemoryActionCount, 0),
      memoryError = prefs.getString(keyMemoryError, "").orEmpty(),
      lastMemoryUpdateTimeMillis = prefs.getLong(keyLastMemoryUpdateTimeMillis, 0L),
      lastHookPoint = prefs.getString(keyLastHookPoint, "").orEmpty(),
      hookHitSummary = prefs.getString(keyHookHitSummary, "").orEmpty(),
      hookLastError = prefs.getString(keyHookLastError, "").orEmpty(),
      lastSendInstructionSummary = prefs.getString(keyLastSendInstructionSummary, "").orEmpty(),
      lastUpdatedTimeMillis = prefs.getLong(keyLastUpdatedTimeMillis, 0L)
    )
  }

  fun reportHookInstalled(point: String, detail: String = "") {
    updateFromHookProcess { current ->
      current.copy(
        lastHookPoint = abbreviate("installed:$point", 80),
        hookHitSummary = mergeHookSummary(current.hookHitSummary, point, hit = false),
        hookLastError = "",
        lastEvent = abbreviate("Hook 已安装 $point ${detail.take(80)}".trim()),
        lastUpdatedTimeMillis = System.currentTimeMillis()
      )
    }
  }

  fun reportHookHit(point: String, did: String = "", sessionId: Int = -1, detail: String = "") {
    updateFromHookProcess { current ->
      current.copy(
        did = did.ifBlank { current.did },
        sessionId = if (sessionId >= 0) sessionId else current.sessionId,
        lastHookPoint = abbreviate(point, 80),
        hookHitSummary = mergeHookSummary(current.hookHitSummary, point, hit = true),
        hookLastError = "",
        lastSendInstructionSummary = if (point == "sendAivsInstructions" || point == "sendAivsInstruction") {
          abbreviate(detail, 240)
        } else {
          current.lastSendInstructionSummary
        },
        lastEvent = abbreviate("Hook 命中 $point ${detail.take(80)}".trim()),
        lastUpdatedTimeMillis = System.currentTimeMillis()
      )
    }
  }

  fun reportHookError(point: String, error: String) {
    updateFromHookProcess { current ->
      current.copy(
        lastHookPoint = abbreviate(point, 80),
        hookLastError = abbreviate(error, 160),
        lastError = abbreviate(error, 160),
        lastEvent = abbreviate("Hook 异常 $point"),
        lastFailureTimeMillis = System.currentTimeMillis(),
        lastUpdatedTimeMillis = System.currentTimeMillis()
      )
    }
  }

  fun reportSessionStart(did: String, sessionId: Int, detail: String) {
    updateFromHookProcess { current ->
      current.copy(
        did = did,
        sessionId = sessionId,
        finalAsr = "",
        llmState = "idle",
        llmAnswerPreview = "",
        replacementConsumed = false,
        lastFallbackReason = "",
        lastInstructionSummary = "",
        memoryState = "idle",
        memoryActionCount = 0,
        memoryError = "",
        lastMemoryUpdateTimeMillis = 0L,
        lastEvent = abbreviate("会话开始 $detail"),
        lastError = "",
        lastUpdatedTimeMillis = System.currentTimeMillis()
      )
    }
  }

  fun reportEvent(did: String, sessionId: Int, event: String) {
    updateFromHookProcess { current ->
      current.copy(did = did, sessionId = sessionId, lastEvent = abbreviate(event), lastUpdatedTimeMillis = System.currentTimeMillis())
    }
  }

  fun reportFinalAsr(did: String, sessionId: Int, finalAsr: String) {
    updateFromHookProcess { current ->
      current.copy(did = did, sessionId = sessionId, finalAsr = abbreviate(finalAsr, 160), lastEvent = "收到 final ASR", lastUpdatedTimeMillis = System.currentTimeMillis())
    }
  }

  fun reportLlmStart(did: String, sessionId: Int, question: String) {
    updateFromHookProcess { current ->
      current.copy(did = did, sessionId = sessionId, llmState = "started", lastEvent = abbreviate("LLM 开始 ${question.take(80)}"), lastError = "", lastUpdatedTimeMillis = System.currentTimeMillis())
    }
  }

  fun reportLlmSuccess(did: String, sessionId: Int, answer: String) {
    updateFromHookProcess { current ->
      current.copy(did = did, sessionId = sessionId, llmState = "success", llmAnswerPreview = abbreviate(answer, 160), lastEvent = "LLM 成功", lastError = "", lastSuccessTimeMillis = System.currentTimeMillis(), lastUpdatedTimeMillis = System.currentTimeMillis())
    }
  }

  fun reportLlmFailure(did: String, sessionId: Int, reason: String, error: String = "") {
    updateFromHookProcess { current ->
      current.copy(did = did, sessionId = sessionId, llmState = reason, lastEvent = "LLM 失败", lastError = abbreviate(error, 160), lastFailureTimeMillis = System.currentTimeMillis(), lastUpdatedTimeMillis = System.currentTimeMillis())
    }
  }

  fun reportAsrBlacklisted(did: String, sessionId: Int, finalAsr: String, rulePreview: String) {
    updateFromHookProcess { current ->
      current.copy(
        did = did,
        sessionId = sessionId,
        finalAsr = abbreviate(finalAsr, 160),
        llmState = "asr_blacklist",
        replacementConsumed = false,
        lastFallbackReason = "asr_blacklist",
        lastInstructionSummary = abbreviate(rulePreview, 240),
        lastEvent = "ASR 黑名单命中",
        lastError = "",
        lastFailureTimeMillis = System.currentTimeMillis(),
        lastUpdatedTimeMillis = System.currentTimeMillis()
      )
    }
  }

  fun reportOfficialReply(did: String, sessionId: Int, reason: String, instructionSummary: String) {
    updateFromHookProcess { current ->
      current.copy(did = did, sessionId = sessionId, replacementConsumed = false, lastFallbackReason = abbreviate(reason, 120), lastInstructionSummary = abbreviate(instructionSummary, 240), lastEvent = "官方 reply 放行", lastFailureTimeMillis = System.currentTimeMillis(), lastUpdatedTimeMillis = System.currentTimeMillis())
    }
  }

  fun reportReplacementPrepared(did: String, sessionId: Int, instructionSummary: String, answerPreview: String) {
    updateFromHookProcess { current ->
      current.copy(did = did, sessionId = sessionId, lastInstructionSummary = abbreviate(instructionSummary, 240), llmAnswerPreview = abbreviate(answerPreview, 160), lastEvent = "reply 替换已准备", lastUpdatedTimeMillis = System.currentTimeMillis())
    }
  }

  fun reportReplacementCommitted(did: String, sessionId: Int) {
    updateFromHookProcess { current ->
      current.copy(did = did, sessionId = sessionId, replacementConsumed = true, lastFallbackReason = "", lastEvent = "reply 替换已提交", lastSuccessTimeMillis = System.currentTimeMillis(), lastUpdatedTimeMillis = System.currentTimeMillis())
    }
  }

  fun reportHistoryPersisted(did: String, sessionId: Int) {
    updateFromHookProcess { current ->
      current.copy(did = did, sessionId = sessionId, lastEvent = "历史已落库", lastHistoryPersistTimeMillis = System.currentTimeMillis(), lastUpdatedTimeMillis = System.currentTimeMillis())
    }
  }

  fun reportHistoryPersistFailed(did: String, sessionId: Int, error: String) {
    updateFromHookProcess { current ->
      current.copy(
        did = did,
        sessionId = sessionId,
        lastEvent = "历史落库失败",
        lastError = abbreviate(error, 160),
        lastFailureTimeMillis = System.currentTimeMillis(),
        lastUpdatedTimeMillis = System.currentTimeMillis()
      )
    }
  }

  fun reportMemoryMaintenance(did: String, sessionId: Int, state: String, actionCount: Int = 0, error: String = "") {
    updateFromHookProcess { current ->
      current.copy(
        did = did,
        sessionId = sessionId,
        memoryState = state,
        memoryActionCount = actionCount,
        memoryError = abbreviate(error, 160),
        lastEvent = when (state) {
          "started" -> "记忆维护开始"
          "success" -> "记忆维护完成"
          "skipped" -> "记忆维护跳过"
          else -> "记忆维护失败"
        },
        lastMemoryUpdateTimeMillis = System.currentTimeMillis(),
        lastUpdatedTimeMillis = System.currentTimeMillis()
      )
    }
  }

  fun write(context: Context, snapshot: AivsDebugSnapshot) {
    context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
      .edit()
      .putString(keyDid, snapshot.did)
      .putInt(keySessionId, snapshot.sessionId)
      .putString(keyFinalAsr, snapshot.finalAsr)
      .putString(keyLlmState, snapshot.llmState)
      .putString(keyLlmAnswerPreview, snapshot.llmAnswerPreview)
      .putBoolean(keyReplacementConsumed, snapshot.replacementConsumed)
      .putString(keyLastFallbackReason, snapshot.lastFallbackReason)
      .putString(keyLastInstructionSummary, snapshot.lastInstructionSummary)
      .putString(keyLastEvent, snapshot.lastEvent)
      .putString(keyLastError, snapshot.lastError)
      .putLong(keyLastSuccessTimeMillis, snapshot.lastSuccessTimeMillis)
      .putLong(keyLastFailureTimeMillis, snapshot.lastFailureTimeMillis)
      .putLong(keyLastHistoryPersistTimeMillis, snapshot.lastHistoryPersistTimeMillis)
      .putString(keyMemoryState, snapshot.memoryState)
      .putInt(keyMemoryActionCount, snapshot.memoryActionCount)
      .putString(keyMemoryError, snapshot.memoryError)
      .putLong(keyLastMemoryUpdateTimeMillis, snapshot.lastMemoryUpdateTimeMillis)
      .putString(keyLastHookPoint, snapshot.lastHookPoint)
      .putString(keyHookHitSummary, snapshot.hookHitSummary)
      .putString(keyHookLastError, snapshot.hookLastError)
      .putString(keyLastSendInstructionSummary, snapshot.lastSendInstructionSummary)
      .putLong(keyLastUpdatedTimeMillis, snapshot.lastUpdatedTimeMillis)
      .commit()
  }

  private fun updateFromHookProcess(transform: (AivsDebugSnapshot) -> AivsDebugSnapshot) {
    val hostContext = ModuleStoreContexts.currentHostContext() ?: run {
      Log.w(tag, "Skip AIVS debug report because no process context")
      return
    }
    val updated = synchronized(hookSnapshotLock) {
      transform(hookSnapshot).also { hookSnapshot = it }
    }
    write(hostContext, updated)
    val success = ModuleSyncServiceClient.syncAivsDebugSnapshot(hostContext, updated)
    if (!success) {
      Log.w(tag, "AIVS debug service write failed; kept in host local store")
    }
  }

  private fun abbreviate(value: String, maxLength: Int = 120): String {
    return value.trim().take(maxLength)
  }

  private fun mergeHookSummary(summary: String, point: String, hit: Boolean): String {
    val key = point.trim().takeIf { it.isNotBlank() } ?: return summary
    val entries = summary.split(" ")
      .filter { it.contains("=") }
      .associate {
        val name = it.substringBefore("=")
        val count = it.substringAfter("=").toIntOrNull() ?: 0
        name to count
      }
      .toMutableMap()
    entries[key] = if (hit) (entries[key] ?: 0) + 1 else entries[key] ?: 0
    return entries.entries.joinToString(" ") { "${it.key}=${it.value}" }.take(240)
  }
}

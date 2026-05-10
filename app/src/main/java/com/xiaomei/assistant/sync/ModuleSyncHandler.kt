package com.xiaomei.assistant.sync

import android.content.Context
import android.util.Log
import com.xiaomei.assistant.XiaoMeiApplication
import com.xiaomei.assistant.bridge.ConversationIdPayload
import com.xiaomei.assistant.bridge.ConversationMessagesPayload
import com.xiaomei.assistant.bridge.MemoryToolActionsPayload
import com.xiaomei.assistant.bridge.ModuleSyncContract
import com.xiaomei.assistant.model.ConversationRecord
import com.xiaomei.assistant.model.LlmConfig
import com.xiaomei.assistant.model.MemoryRecord
import com.xiaomei.assistant.model.SessionRecord
import com.xiaomei.assistant.runtime.RuntimeContainer
import com.xiaomei.assistant.status.AivsDebugSnapshot
import com.xiaomei.assistant.status.AivsDebugSnapshotStore
import com.xiaomei.assistant.status.ModuleProcessStatus
import com.xiaomei.assistant.status.ModuleRuntimeStatusStore
import kotlinx.serialization.json.Json

internal object ModuleSyncHandler {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  suspend fun handle(context: Context, action: String, payload: String) {
    val appContext = context.applicationContext ?: context
    Log.i("XiaoMeiHook", "ModuleSyncHandler received action=$action payloadBytes=${payload.length}")
    when (action) {
      ModuleSyncContract.actionSyncRuntimeProcessStatus -> {
        val status = json.decodeFromString(ModuleProcessStatus.serializer(), payload)
        ModuleRuntimeStatusStore.applyProcessStatus(appContext, status.hostPackage, status)
        Log.i("XiaoMeiHook", "ModuleSyncHandler stored runtime status process=${status.processName}")
      }

      ModuleSyncContract.actionSyncAivsDebugSnapshot -> {
        val snapshot = json.decodeFromString(AivsDebugSnapshot.serializer(), payload)
        AivsDebugSnapshotStore.write(appContext, snapshot)
        Log.i(
          "XiaoMeiHook",
          "ModuleSyncHandler stored AIVS snapshot did=${snapshot.did} session=${snapshot.sessionId} " +
            "event=${snapshot.lastEvent} hook=${snapshot.lastHookPoint}"
        )
      }

      ModuleSyncContract.actionUpsertSessionRecord -> {
        val record = json.decodeFromString(SessionRecord.serializer(), payload)
        runtimeContainer(appContext).historyRepository.upsert(record)
      }

      ModuleSyncContract.actionUpsertMemoryRecord -> {
        val record = json.decodeFromString(MemoryRecord.serializer(), payload)
        runtimeContainer(appContext).memoryRepository.upsert(record)
      }

      ModuleSyncContract.actionApplyMemoryToolActions -> {
        val actions = json.decodeFromString(MemoryToolActionsPayload.serializer(), payload)
        runtimeContainer(appContext).memoryRepository.applyToolActions(actions.actions, actions.sourceSessionId)
      }

      ModuleSyncContract.actionSaveLlmConfig -> {
        val config = json.decodeFromString(LlmConfig.serializer(), payload)
        runtimeContainer(appContext).configRepository.save(config)
      }

      ModuleSyncContract.actionSetActiveConversation -> {
        val data = json.decodeFromString(ConversationIdPayload.serializer(), payload)
        runtimeContainer(appContext).conversationRepository.setActiveConversation(data.conversationId)
      }

      ModuleSyncContract.actionUpsertConversation -> {
        val conversation = json.decodeFromString(ConversationRecord.serializer(), payload)
        runtimeContainer(appContext).conversationRepository.upsertConversation(conversation)
      }

      ModuleSyncContract.actionAppendConversationMessages -> {
        val data = json.decodeFromString(ConversationMessagesPayload.serializer(), payload)
        runtimeContainer(appContext).conversationRepository.appendMessages(data.messages)
      }

      ModuleSyncContract.actionDeleteConversation -> {
        val data = json.decodeFromString(ConversationIdPayload.serializer(), payload)
        runtimeContainer(appContext).conversationRepository.deleteConversation(data.conversationId)
      }

      ModuleSyncContract.actionClearConversationMessages -> {
        val data = json.decodeFromString(ConversationIdPayload.serializer(), payload)
        runtimeContainer(appContext).conversationRepository.clearMessages(data.conversationId)
      }

      else -> Log.w("XiaoMeiHook", "Unknown module sync action=$action")
    }
  }

  private fun runtimeContainer(context: Context): RuntimeContainer {
    return (context.applicationContext as? XiaoMeiApplication)?.container ?: RuntimeContainer(context)
  }
}

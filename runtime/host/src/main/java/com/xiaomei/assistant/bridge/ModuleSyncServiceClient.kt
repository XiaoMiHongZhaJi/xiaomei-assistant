package com.xiaomei.assistant.bridge

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.xiaomei.assistant.host.HostSettingsNavigation
import com.xiaomei.assistant.model.ConversationMessageRecord
import com.xiaomei.assistant.model.ConversationRecord
import com.xiaomei.assistant.model.LlmConfig
import com.xiaomei.assistant.model.MemoryRecord
import com.xiaomei.assistant.model.MemoryToolAction
import com.xiaomei.assistant.model.SessionRecord
import com.xiaomei.assistant.runtime.StartupInfo
import com.xiaomei.assistant.status.AivsDebugSnapshot
import com.xiaomei.assistant.status.ModuleProcessStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object ModuleSyncContract {
  const val serviceClassName = "com.xiaomei.assistant.sync.ModuleSyncService"
  const val receiverClassName = "com.xiaomei.assistant.sync.ModuleSyncReceiver"
  const val extraAction = "com.xiaomei.assistant.sync.ACTION"
  const val extraPayload = "com.xiaomei.assistant.sync.PAYLOAD"
  const val messageWhatSync = 1

  const val actionSyncRuntimeProcessStatus = "sync_runtime_process_status"
  const val actionSyncAivsDebugSnapshot = "sync_aivs_debug_snapshot"
  const val actionUpsertSessionRecord = "upsert_session_record"
  const val actionUpsertMemoryRecord = "upsert_memory_record"
  const val actionApplyMemoryToolActions = "apply_memory_tool_actions"
  const val actionSaveLlmConfig = "save_llm_config"
  const val actionSetActiveConversation = "set_active_conversation"
  const val actionUpsertConversation = "upsert_conversation"
  const val actionAppendConversationMessages = "append_conversation_messages"
  const val actionDeleteConversation = "delete_conversation"
  const val actionClearConversationMessages = "clear_conversation_messages"
}

@Serializable
data class MemoryToolActionsPayload(
  val actions: List<MemoryToolAction>,
  val sourceSessionId: String = ""
)

@Serializable
data class ConversationIdPayload(
  val conversationId: String
)

@Serializable
data class ConversationMessagesPayload(
  val messages: List<ConversationMessageRecord>
)

object ModuleSyncServiceClient {
  private const val tag = "XiaoMeiHook"

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  fun syncRuntimeProcessStatus(context: Context, status: ModuleProcessStatus): Boolean {
    return sendPayload(
      context = context,
      action = ModuleSyncContract.actionSyncRuntimeProcessStatus,
      payload = json.encodeToString(ModuleProcessStatus.serializer(), status)
    )
  }

  fun syncAivsDebugSnapshot(context: Context, snapshot: AivsDebugSnapshot): Boolean {
    return sendPayload(
      context = context,
      action = ModuleSyncContract.actionSyncAivsDebugSnapshot,
      payload = json.encodeToString(AivsDebugSnapshot.serializer(), snapshot)
    )
  }

  fun upsertSessionRecord(context: Context, record: SessionRecord): Boolean {
    return sendPayload(
      context = context,
      action = ModuleSyncContract.actionUpsertSessionRecord,
      payload = json.encodeToString(SessionRecord.serializer(), record)
    )
  }

  fun upsertMemoryRecord(context: Context, record: MemoryRecord): Boolean {
    return sendPayload(
      context = context,
      action = ModuleSyncContract.actionUpsertMemoryRecord,
      payload = json.encodeToString(MemoryRecord.serializer(), record)
    )
  }

  fun applyMemoryToolActions(context: Context, actions: List<MemoryToolAction>, sourceSessionId: String): Boolean {
    return sendPayload(
      context = context,
      action = ModuleSyncContract.actionApplyMemoryToolActions,
      payload = json.encodeToString(MemoryToolActionsPayload(actions, sourceSessionId))
    )
  }

  fun saveLlmConfig(context: Context, config: LlmConfig): Boolean {
    return sendPayload(
      context = context,
      action = ModuleSyncContract.actionSaveLlmConfig,
      payload = json.encodeToString(LlmConfig.serializer(), config)
    )
  }

  fun setActiveConversation(context: Context, conversationId: String): Boolean {
    return sendPayload(
      context = context,
      action = ModuleSyncContract.actionSetActiveConversation,
      payload = json.encodeToString(ConversationIdPayload(conversationId))
    )
  }

  fun upsertConversation(context: Context, conversation: ConversationRecord): Boolean {
    return sendPayload(
      context = context,
      action = ModuleSyncContract.actionUpsertConversation,
      payload = json.encodeToString(ConversationRecord.serializer(), conversation)
    )
  }

  fun appendConversationMessages(context: Context, messages: List<ConversationMessageRecord>): Boolean {
    return sendPayload(
      context = context,
      action = ModuleSyncContract.actionAppendConversationMessages,
      payload = json.encodeToString(ConversationMessagesPayload(messages))
    )
  }

  fun deleteConversation(context: Context, conversationId: String): Boolean {
    return sendPayload(
      context = context,
      action = ModuleSyncContract.actionDeleteConversation,
      payload = json.encodeToString(ConversationIdPayload(conversationId))
    )
  }

  fun clearConversationMessages(context: Context, conversationId: String): Boolean {
    return sendPayload(
      context = context,
      action = ModuleSyncContract.actionClearConversationMessages,
      payload = json.encodeToString(ConversationIdPayload(conversationId))
    )
  }

  private fun sendPayload(context: Context, action: String, payload: String): Boolean {
    return runCatching {
      val appContext = context.applicationContext ?: context
      StartupInfo.log(
        "Module sync send action=$action ctx=${context.javaClass.name} pkg=${context.packageName} " +
          "appCtx=${context.applicationContext?.javaClass?.name ?: "null"}"
      )
      val intent = createSyncIntent(action, payload)
      val connection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
          if (service == null) {
            StartupInfo.log("Module sync binder missing action=$action")
            startServiceFallback(appContext, intent, action, "binder_missing")
            runCatching { appContext.unbindService(this) }
            return
          }
          val message = Message.obtain(null, ModuleSyncContract.messageWhatSync).apply {
            data = Bundle().apply {
              putString(ModuleSyncContract.extraAction, action)
              putString(ModuleSyncContract.extraPayload, payload)
            }
          }
          runCatching {
            Messenger(service).send(message)
            StartupInfo.log("Module sync messenger sent action=$action")
            if (shouldBroadcastAfterMessenger(action)) {
              sendBroadcastFallback(appContext, action, payload, "mirror_after_messenger")
            }
          }.onFailure { error ->
            Log.w(tag, "Module sync messenger send failed action=$action", error)
            StartupInfo.log(error)
            startServiceFallback(appContext, intent, action, "messenger_failed")
          }
          runCatching { appContext.unbindService(this) }.onFailure { error ->
            Log.w(tag, "Module sync unbind failed action=$action", error)
          }
        }

        override fun onNullBinding(name: android.content.ComponentName?) {
          StartupInfo.log("Module sync null binding action=$action")
          startServiceFallback(appContext, intent, action, "null_binding")
        }

        override fun onServiceDisconnected(name: android.content.ComponentName?) = Unit

        override fun onBindingDied(name: android.content.ComponentName?) {
          StartupInfo.log("Module sync binding died action=$action")
          startServiceFallback(appContext, intent, action, "binding_died")
        }
      }
      val bound = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
      if (!bound) {
        StartupInfo.log("Module sync bindService returned false action=$action")
        return@runCatching startServiceFallback(appContext, intent, action, "bind_returned_false")
      }
      bound
    }.onFailure { error ->
      Log.w(tag, "Module sync service call failed action=$action caller=${context.packageName}", error)
      StartupInfo.log(error)
    }.getOrDefault(false)
  }

  private fun createSyncIntent(action: String, payload: String): Intent {
    return Intent()
      .setClassName(HostSettingsNavigation.modulePackageName, ModuleSyncContract.serviceClassName)
      .putExtra(ModuleSyncContract.extraAction, action)
      .putExtra(ModuleSyncContract.extraPayload, payload)
  }

  private fun shouldBroadcastAfterMessenger(action: String): Boolean {
    return action == ModuleSyncContract.actionSyncAivsDebugSnapshot ||
      action == ModuleSyncContract.actionSyncRuntimeProcessStatus
  }

  private fun startServiceFallback(
    context: Context,
    intent: Intent,
    action: String,
    reason: String
  ): Boolean {
    val started = runCatching {
      StartupInfo.log("Module sync startService fallback action=$action reason=$reason")
      context.startService(intent) != null
    }.onFailure { error ->
      Log.w(tag, "Module sync startService fallback failed action=$action reason=$reason", error)
      StartupInfo.log(error)
    }.getOrDefault(false)
    val broadcasted = sendBroadcastFallback(
      context = context,
      action = action,
      payload = intent.getStringExtra(ModuleSyncContract.extraPayload).orEmpty(),
      reason = reason
    )
    return started || broadcasted
  }

  private fun sendBroadcastFallback(
    context: Context,
    action: String,
    payload: String,
    reason: String
  ): Boolean {
    return runCatching {
      StartupInfo.log("Module sync broadcast fallback action=$action reason=$reason")
      context.sendBroadcast(
        Intent()
          .setClassName(HostSettingsNavigation.modulePackageName, ModuleSyncContract.receiverClassName)
          .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
          .putExtra(ModuleSyncContract.extraAction, action)
          .putExtra(ModuleSyncContract.extraPayload, payload)
      )
      true
    }.onFailure { error ->
      Log.w(tag, "Module sync broadcast fallback failed action=$action reason=$reason", error)
      StartupInfo.log(error)
    }.getOrDefault(false)
  }
}

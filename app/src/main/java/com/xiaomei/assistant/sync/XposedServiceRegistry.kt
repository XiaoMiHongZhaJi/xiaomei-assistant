package com.xiaomei.assistant.sync

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import com.xiaomei.assistant.bridge.ModuleRemoteStoreBridge
import com.xiaomei.assistant.data.AppConfigRepository
import com.xiaomei.assistant.data.ConversationRepository
import com.xiaomei.assistant.data.MemoryRepository
import com.xiaomei.assistant.data.SessionHistoryRepository
import com.xiaomei.assistant.model.LlmConfig
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

object XposedServiceRegistry {
  private const val tag = "XiaoMeiHook"

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val serviceState = MutableStateFlow<XposedService?>(null)

  @Volatile
  private var initialized = false

  @Volatile
  private var appContext: Context? = null

  private val syncDelegate = object : ModuleRemoteStoreBridge.SyncDelegate {
    override fun writeLlmConfig(config: LlmConfig): Boolean {
      val service = serviceState.value ?: return false
      return runCatching {
        val prefs = service.getRemotePreferences(AppConfigRepository.REMOTE_PREFERENCES_GROUP)
        AppConfigRepository.writeRemotePreferences(prefs.edit(), config)
      }.onFailure {
        Log.w(tag, "Remote config sync failed", it)
      }.getOrDefault(false)
    }

    override fun writeSessionHistory(snapshotJson: String): Boolean {
      val service = serviceState.value ?: return false
      return runCatching {
        val descriptor = service.openRemoteFile(SessionHistoryRepository.REMOTE_HISTORY_FILE)
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
          output.channel.truncate(0)
          output.channel.position(0)
          output.writer(Charsets.UTF_8).use { writer ->
            writer.write(snapshotJson)
            writer.flush()
          }
        }
        true
      }.onFailure {
        Log.w(tag, "Remote history sync failed", it)
      }.getOrDefault(false)
    }

    override fun writeConversations(snapshotJson: String): Boolean {
      val service = serviceState.value ?: return false
      return runCatching {
        val descriptor = service.openRemoteFile(ConversationRepository.REMOTE_CONVERSATIONS_FILE)
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
          output.channel.truncate(0)
          output.channel.position(0)
          output.writer(Charsets.UTF_8).use { writer ->
            writer.write(snapshotJson)
            writer.flush()
          }
        }
        true
      }.onFailure {
        Log.w(tag, "Remote conversations sync failed", it)
      }.getOrDefault(false)
    }

    override fun writeMemory(snapshotJson: String): Boolean {
      val service = serviceState.value ?: return false
      return runCatching {
        val descriptor = service.openRemoteFile(MemoryRepository.REMOTE_MEMORY_FILE)
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
          output.channel.truncate(0)
          output.channel.position(0)
          output.writer(Charsets.UTF_8).use { writer ->
            writer.write(snapshotJson)
            writer.flush()
          }
        }
        true
      }.onFailure {
        Log.w(tag, "Remote memory sync failed", it)
      }.getOrDefault(false)
    }
  }

  private val listener = object : XposedServiceHelper.OnServiceListener {
    override fun onServiceBind(service: XposedService) {
      serviceState.value = service
      Log.i(
        tag,
        "XposedService bound framework=${service.frameworkName} api=${service.apiVersion} props=${service.frameworkProperties}"
      )
      val context = appContext ?: return
      scope.launch {
        AppConfigRepository.syncLocalToRemote(context)
        SessionHistoryRepository.syncLocalToRemote(context)
        ConversationRepository.syncLocalToRemote(context)
        MemoryRepository.syncLocalToRemote(context)
      }
    }

    override fun onServiceDied(service: XposedService) {
      if (serviceState.value == service) {
        serviceState.value = null
      }
      Log.w(tag, "XposedService died")
    }
  }

  fun init(context: Context) {
    if (initialized) {
      return
    }
    synchronized(this) {
      if (initialized) {
        return
      }
      appContext = context.applicationContext
      ModuleRemoteStoreBridge.install(syncDelegate)
      XposedServiceHelper.registerListener(listener)
      initialized = true
    }
  }

  fun currentService(): XposedService? = serviceState.value

  fun isBound(): Boolean = serviceState.value != null

  fun serviceFlow(): StateFlow<XposedService?> = serviceState
}


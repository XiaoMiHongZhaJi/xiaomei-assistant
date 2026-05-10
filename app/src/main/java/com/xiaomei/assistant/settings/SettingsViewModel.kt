package com.xiaomei.assistant.settings

import android.content.Context
import android.content.pm.PackageManager
import android.text.format.DateFormat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xiaomei.assistant.model.AivsAsrBlacklistRule
import com.xiaomei.assistant.model.ConversationMessageRole
import com.xiaomei.assistant.model.ConversationState
import com.xiaomei.assistant.model.LlmConfig
import com.xiaomei.assistant.model.MemorySource
import com.xiaomei.assistant.runtime.RuntimeContainer
import com.xiaomei.assistant.status.AivsDebugSnapshot
import com.xiaomei.assistant.status.AivsDebugSnapshotStore
import com.xiaomei.assistant.status.ModuleProcessStatus
import com.xiaomei.assistant.status.ModuleRuntimeSnapshot
import com.xiaomei.assistant.status.ModuleRuntimeStatusStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date

class SettingsViewModel(
  private val moduleContext: Context
) : ViewModel() {
  private val container = RuntimeContainer(moduleContext)
  private val diagnostics = MutableStateFlow(buildDiagnostics())
  private val initialConfig = container.configRepository.config.value

  val uiState: StateFlow<SettingsUiState> = combine(
    container.configRepository.config,
    container.historyRepository.records,
    container.conversationRepository.state,
    container.memoryRepository.records,
    diagnostics
  ) { config, history, conversationState, memories, diagnostics ->
    SettingsUiState(
      config = config,
      history = history,
      conversationState = conversationState,
      memories = memories,
      diagnostics = diagnostics
    )
  }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState(config = initialConfig))

  init {
    viewModelScope.launch {
      while (isActive) {
        runCatching { container.conversationRepository.refreshFromStorage() }
        refreshDiagnostics()
        delay(REFRESH_INTERVAL_MS)
      }
    }
  }

  fun refreshDiagnostics() {
    diagnostics.value = buildDiagnostics(
      llmTestStatus = diagnostics.value.llmTestStatus,
      llmTestInProgress = diagnostics.value.llmTestInProgress,
      lastError = diagnostics.value.lastError
    )
  }

  fun saveConfig(config: LlmConfig) {
    viewModelScope.launch {
      saveConfigBlocking(config)
    }
  }

  suspend fun saveConfigBlocking(config: LlmConfig): Boolean {
    return withContext(NonCancellable + Dispatchers.IO) {
      runCatching {
        container.configRepository.save(config)
      }.isSuccess
    }.also {
      refreshDiagnostics()
    }
  }

  fun testLlmConnection(configOverride: LlmConfig? = null) {
    viewModelScope.launch {
      diagnostics.value = buildDiagnostics(
        llmTestStatus = "测试中...",
        llmTestInProgress = true,
        lastError = ""
      )
      runCatching {
        container.llmClient.probe(configOverride ?: container.configRepository.config.value)
      }.onSuccess { answer ->
        diagnostics.value = buildDiagnostics(
          llmTestStatus = answer.ifBlank { "LLM 连接正常" },
          llmTestInProgress = false,
          lastError = ""
        )
      }.onFailure { error ->
        diagnostics.value = buildDiagnostics(
          llmTestStatus = "失败",
          llmTestInProgress = false,
          lastError = error.message ?: error.javaClass.simpleName
        )
      }
    }
  }

  fun setAsrBlacklistEnabled(enabled: Boolean) {
    viewModelScope.launch {
      runCatching {
        val config = container.configRepository.config.value
        container.configRepository.save(config.copy(asrBlacklistEnabled = enabled))
      }
        .onSuccess { refreshDiagnostics() }
        .onFailure { diagnostics.value = buildDiagnostics(lastError = it.message ?: it.javaClass.simpleName) }
    }
  }

  fun upsertAsrBlacklistRule(rule: AivsAsrBlacklistRule) {
    viewModelScope.launch {
      runCatching {
        val config = container.configRepository.config.value
        val rules = config.asrBlacklistRules.toMutableList()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) {
          rules[index] = rule
        } else {
          rules.add(rule)
        }
        container.configRepository.save(config.copy(asrBlacklistRules = rules))
      }
        .onSuccess { refreshDiagnostics() }
        .onFailure { diagnostics.value = buildDiagnostics(lastError = it.message ?: it.javaClass.simpleName) }
    }
  }

  fun deleteAsrBlacklistRule(id: String) {
    viewModelScope.launch {
      runCatching {
        val config = container.configRepository.config.value
        container.configRepository.save(
          config.copy(asrBlacklistRules = config.asrBlacklistRules.filterNot { it.id == id })
        )
      }
        .onSuccess { refreshDiagnostics() }
        .onFailure { diagnostics.value = buildDiagnostics(lastError = it.message ?: it.javaClass.simpleName) }
    }
  }

  fun addMemory(content: String) {
    viewModelScope.launch {
      runCatching { container.memoryRepository.add(content, source = MemorySource.MANUAL) }
        .onFailure { diagnostics.value = buildDiagnostics(lastError = it.message ?: it.javaClass.simpleName) }
    }
  }

  fun updateMemory(id: String, content: String) {
    viewModelScope.launch {
      runCatching { container.memoryRepository.updateContent(id, content) }
        .onFailure { diagnostics.value = buildDiagnostics(lastError = it.message ?: it.javaClass.simpleName) }
    }
  }

  fun setMemoryEnabled(id: String, enabled: Boolean) {
    viewModelScope.launch {
      runCatching { container.memoryRepository.setEnabled(id, enabled) }
        .onFailure { diagnostics.value = buildDiagnostics(lastError = it.message ?: it.javaClass.simpleName) }
    }
  }

  fun deleteMemory(id: String) {
    viewModelScope.launch {
      runCatching { container.memoryRepository.delete(id) }
        .onFailure { diagnostics.value = buildDiagnostics(lastError = it.message ?: it.javaClass.simpleName) }
    }
  }

  fun createConversation(title: String? = null) {
    viewModelScope.launch {
      runCatching { container.conversationRepository.createConversation(title) }
        .onSuccess { refreshDiagnostics() }
        .onFailure { diagnostics.value = buildDiagnostics(lastError = it.message ?: it.javaClass.simpleName) }
    }
  }

  fun renameConversation(id: String, title: String) {
    viewModelScope.launch {
      runCatching { container.conversationRepository.renameConversation(id, title) }
        .onSuccess { refreshDiagnostics() }
        .onFailure { diagnostics.value = buildDiagnostics(lastError = it.message ?: it.javaClass.simpleName) }
    }
  }

  fun selectConversation(id: String) {
    viewModelScope.launch {
      runCatching { container.conversationRepository.setActiveConversation(id) }
        .onSuccess { refreshDiagnostics() }
        .onFailure { diagnostics.value = buildDiagnostics(lastError = it.message ?: it.javaClass.simpleName) }
    }
  }

  fun deleteConversation(id: String) {
    viewModelScope.launch {
      runCatching { container.conversationRepository.deleteConversation(id) }
        .onSuccess { refreshDiagnostics() }
        .onFailure { diagnostics.value = buildDiagnostics(lastError = it.message ?: it.javaClass.simpleName) }
    }
  }

  fun clearConversationMessages(id: String) {
    viewModelScope.launch {
      runCatching { container.conversationRepository.clearMessages(id) }
        .onSuccess { refreshDiagnostics() }
        .onFailure { diagnostics.value = buildDiagnostics(lastError = it.message ?: it.javaClass.simpleName) }
    }
  }

  private fun buildDiagnostics(
    llmTestStatus: String = SettingsDiagnostics().llmTestStatus,
    llmTestInProgress: Boolean = SettingsDiagnostics().llmTestInProgress,
    lastError: String = SettingsDiagnostics().lastError
  ): SettingsDiagnostics {
    val runtime = ModuleRuntimeStatusStore.read(moduleContext)
    val aivs = AivsDebugSnapshotStore.read(moduleContext)
    val conversationState = container.conversationRepository.state.value
    val conversation = conversationState.activeConversation()
    val conversationMessages = conversationState.messages
      .filter { it.conversationId == conversation?.id }
      .sortedBy { it.createdAt }
    val latestUserMessage = conversationMessages.lastOrNull { it.role == ConversationMessageRole.USER }
    val latestAssistantMessage = conversationMessages.lastOrNull { it.role == ConversationMessageRole.ASSISTANT }
    val finalAsr = aivs.finalAsr.ifBlank { latestUserMessage?.content.orEmpty() }
    val llmAnswerPreview = aivs.llmAnswerPreview.ifBlank { latestAssistantMessage?.content?.takeWithEllipsis(160).orEmpty() }
    val historyPersistTime = maxOf(aivs.lastHistoryPersistTimeMillis, latestAssistantMessage?.createdAt ?: 0L)
    val aivsEvent = when {
      aivs.lastEvent.isNotBlank() && aivs.lastEvent != "等待语音链路验证" -> aivs.lastEvent
      latestUserMessage != null -> "已从当前会话恢复最近 ASR"
      else -> aivs.lastEvent
    }
    val hostVersion = resolveHostVersion(moduleContext, runtime.hostPackage)
    val hookStatus = if (runtime.isActivated) {
      "主进程与设备进程已进入 modern 101 主链，宿主入口与 AIVS 链可继续联调"
    } else if (runtime.hasAnySignal) {
      "已收到部分注入回执，等待 main/device 双通道稳定命中"
    } else {
      "未检测到稳定注入回执"
    }
    val aivsStatus = when {
      runtime.deviceProcess.isActivated && aivs.replacementConsumed ->
        "AIVS reply 已成功替换并完成一次提交"
      runtime.deviceProcess.isActivated && finalAsr.isNotBlank() ->
        "AIVS 已命中 :device，等待 reply 替换或官方回退结果"
      finalAsr.isNotBlank() ->
        "已读取当前会话最近语音上下文"
      runtime.mainProcess.isActivated ->
        "主进程设置 hook 已命中，等待 :device AIVS 继续验证"
      else -> "等待语音链路验证"
    }
    return SettingsDiagnostics(
      hostPackage = runtime.hostPackage.ifBlank { "com.mi.health" },
      hostVersion = hostVersion,
      hookStatus = hookStatus,
      hostEntry = "Mine RN + AboutActivity",
      aivsStatus = aivsStatus,
      mainProcessSummary = formatProcessStatus(runtime.mainProcess, "主进程未命中"),
      deviceProcessSummary = formatProcessStatus(runtime.deviceProcess, "设备进程未命中"),
      ignoredProcessSummary = formatIgnoredProcess(runtime.ignoredProcess),
      mainLastHookTimeText = formatTimestamp(runtime.mainProcess.lastHookTimeMillis),
      deviceLastHookTimeText = formatTimestamp(runtime.deviceProcess.lastHookTimeMillis),
      aivsEvent = aivsEvent,
      finalAsr = finalAsr,
      llmStatus = describeLlmStatus(aivs),
      llmAnswerPreview = llmAnswerPreview,
      fallbackReason = aivs.lastFallbackReason,
      lastHistoryPersistText = formatTimestamp(historyPersistTime),
      aivsLastUpdatedText = formatTimestamp(aivs.lastUpdatedTimeMillis),
      llmTestStatus = llmTestStatus,
      llmTestInProgress = llmTestInProgress,
      lastError = lastError.ifBlank {
        listOf(
          runtime.deviceProcess.message,
          runtime.mainProcess.message,
          aivs.lastError
        ).firstOrNull { it.isNotBlank() }.orEmpty()
      },
      memoryState = aivs.memoryState,
      memoryActionCount = aivs.memoryActionCount,
      memoryError = aivs.memoryError,
      lastMemoryUpdateText = formatTimestamp(aivs.lastMemoryUpdateTimeMillis),
      aivsHookPoint = aivs.lastHookPoint,
      aivsHookHits = aivs.hookHitSummary,
      aivsHookError = aivs.hookLastError,
      aivsLastSendInstruction = aivs.lastSendInstructionSummary,
      activeConversationTitle = conversation?.title ?: "默认会话",
      activeConversationMessageCount = conversation?.messageCount ?: 0,
      activeConversationUpdatedText = formatTimestamp(conversation?.updatedAt ?: 0L),
      lastHookTimeText = formatTimestamp(
        maxOf(
          runtime.mainProcess.lastHookTimeMillis,
          runtime.deviceProcess.lastHookTimeMillis,
          runtime.ignoredProcess.lastHookTimeMillis
        )
      )
    )
  }

  private fun formatProcessStatus(status: ModuleProcessStatus, emptyLabel: String): String {
    if (!status.hasSignal) {
      return emptyLabel
    }
    val process = status.processName.ifBlank { "未知进程" }
    val message = status.message.ifBlank { "无附加消息" }
    return "$process / hooks=${status.installedHooks} / $message"
  }

  private fun formatIgnoredProcess(status: ModuleProcessStatus): String {
    if (!status.hasSignal) {
      return ""
    }
    return formatProcessStatus(status, "")
  }

  private fun describeLlmStatus(snapshot: AivsDebugSnapshot): String {
    return when (snapshot.llmState) {
      "started" -> "LLM 请求中"
      "success" -> "LLM 成功"
      "timeout" -> "LLM 超时，已回退官方"
      "exception" -> "LLM 异常，已回退官方"
      "runtime_unavailable" -> "运行时未就绪，已回退官方"
      "empty_final_asr" -> "ASR 为空，未触发接管"
      else -> "未开始"
    }
  }

  private fun resolveHostVersion(context: Context, packageName: String): String {
    return runCatching {
      val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
        context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(packageName, 0)
      }
      "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("未知")
  }

  private fun formatTimestamp(value: Long): String {
    if (value <= 0L) {
      return "从未"
    }
    return DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(value)).toString()
  }

  private fun ConversationState.activeConversation() =
    conversations.firstOrNull { it.id == activeConversationId }

  private fun String.takeWithEllipsis(maxLength: Int): String {
    return if (length <= maxLength) this else take(maxLength - 3) + "..."
  }

  companion object {
    private const val REFRESH_INTERVAL_MS = 1_000L

    fun factory(context: Context): ViewModelProvider.Factory {
      val moduleContext = ProcessEnvironment.resolveModuleContext(context)
      return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
          return SettingsViewModel(moduleContext) as T
        }
      }
    }
  }
}



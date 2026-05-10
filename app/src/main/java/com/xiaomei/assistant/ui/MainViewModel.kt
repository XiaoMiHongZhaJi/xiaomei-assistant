package com.xiaomei.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xiaomei.assistant.XiaoMeiApplication
import com.xiaomei.assistant.model.LlmConfig
import com.xiaomei.assistant.model.MainUiState
import com.xiaomei.assistant.model.ModuleDiagnostics
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(
  application: Application
) : AndroidViewModel(application) {
  private val container = (application as XiaoMeiApplication).container
  private val diagnostics = MutableStateFlow(ModuleDiagnostics())

  val uiState: StateFlow<MainUiState> = combine(
    container.configRepository.config,
    container.historyRepository.records,
    diagnostics
  ) { config, history, diagnostics ->
    MainUiState(
      config = config,
      history = history,
      diagnostics = diagnostics
    )
  }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState())

  fun saveConfig(config: LlmConfig) {
    viewModelScope.launch {
      container.configRepository.save(config)
    }
  }

  fun testLlmConnection(configOverride: LlmConfig? = null) {
    viewModelScope.launch {
      diagnostics.value = diagnostics.value.copy(
        llmTestInProgress = true,
        llmTestStatus = "测试中...",
        lastError = ""
      )
      runCatching {
        container.llmClient.probe(configOverride ?: container.configRepository.config.value)
      }.onSuccess { answer ->
        diagnostics.value = diagnostics.value.copy(
          llmTestInProgress = false,
          llmTestStatus = answer.ifBlank { "LLM连接正常" }
        )
      }.onFailure { error ->
        diagnostics.value = diagnostics.value.copy(
          llmTestInProgress = false,
          llmTestStatus = "失败",
          lastError = error.message ?: error.javaClass.simpleName
        )
      }
    }
  }

  companion object {
    fun factory(application: Application): ViewModelProvider.Factory =
      object : ViewModelProvider.AndroidViewModelFactory(application) {
        @Suppress("UNCHECKED_CAST")
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
          return MainViewModel(application) as T
        }
      }
  }
}

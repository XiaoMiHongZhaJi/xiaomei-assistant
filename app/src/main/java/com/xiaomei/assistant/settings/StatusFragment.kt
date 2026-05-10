package com.xiaomei.assistant.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xiaomei.assistant.R
import com.xiaomei.assistant.host.HostSettingsNavigation
import com.xiaomei.assistant.settings.dsl.HeaderItem
import com.xiaomei.assistant.settings.dsl.SettingsListAdapter
import com.xiaomei.assistant.settings.dsl.SpacerItem
import com.xiaomei.assistant.settings.dsl.TextListItem
import com.xiaomei.assistant.settings.dsl.createSettingsRecyclerView
import kotlinx.coroutines.launch

class StatusFragment : BaseRootLayoutFragment() {
  private val viewModel by activityViewModels<SettingsViewModel> {
    SettingsViewModel.factory(requireContext())
  }
  private val listAdapter = SettingsListAdapter()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    titleText = if (currentSection() == HostSettingsNavigation.targetAivsStatus) {
      getString(R.string.settings_aivs_title)
    } else {
      getString(R.string.settings_runtime_title)
    }
  }

  override fun doOnCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    return createSettingsRecyclerView(requireContext(), listAdapter)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    rootLayoutView = view as ViewGroup
    applyRootLayoutPaddingFor(rootLayoutView!!)
    viewLifecycleOwner.lifecycleScope.launch {
      viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { render(it.diagnostics) }
      }
    }
  }

  private fun render(diagnostics: SettingsDiagnostics) {
    listAdapter.submitList(
      if (currentSection() == HostSettingsNavigation.targetAivsStatus) buildAivsItems(diagnostics) else buildRuntimeItems(diagnostics)
    )
  }

  private fun buildRuntimeItems(diagnostics: SettingsDiagnostics) = listOf(
    SpacerItem(8),
    HeaderItem("宿主"),
    TextListItem(getString(R.string.settings_status_host), diagnostics.hostPackage, diagnostics.hostVersion),
    TextListItem("入口策略", diagnostics.hostEntry, null),
    HeaderItem("进程状态"),
    TextListItem("主进程", shortText(diagnostics.mainProcessSummary), diagnostics.mainLastHookTimeText),
    TextListItem(":device", shortText(diagnostics.deviceProcessSummary), diagnostics.deviceLastHookTimeText),
    TextListItem("忽略进程", diagnostics.ignoredProcessSummary.ifBlank { "无" }, null),
    HeaderItem("诊断"),
    TextListItem(getString(R.string.settings_status_last_hook), diagnostics.lastHookTimeText, null),
    TextListItem(getString(R.string.settings_status_last_error), diagnostics.lastError.ifBlank { "无" }, null, divider = false),
    SpacerItem(24)
  )

  private fun buildAivsItems(diagnostics: SettingsDiagnostics) = listOf(
    SpacerItem(8),
    HeaderItem("AIVS 链路"),
    TextListItem("最近事件", diagnostics.aivsEvent.ifBlank { "无" }, diagnostics.aivsLastUpdatedText),
    TextListItem("最近 Hook", diagnostics.aivsHookPoint.ifBlank { "尚未命中" }, null),
    TextListItem("Hook 命中", diagnostics.aivsHookHits.ifBlank { "无" }, null),
    TextListItem("send 指令", diagnostics.aivsLastSendInstruction.ifBlank { "尚未捕获" }, null),
    TextListItem("Hook 错误", diagnostics.aivsHookError.ifBlank { "无" }, null),
    TextListItem("Final ASR", diagnostics.finalAsr.ifBlank { "尚未捕获" }, null),
    HeaderItem("模型与回复"),
    TextListItem("LLM 状态", diagnostics.llmStatus.ifBlank { "未开始" }, null),
    TextListItem("回答摘要", diagnostics.llmAnswerPreview.ifBlank { "无" }, null),
    TextListItem("回退原因", diagnostics.fallbackReason.ifBlank { "无" }, null),
    HeaderItem("提交状态"),
    TextListItem("当前会话", diagnostics.activeConversationTitle, "${diagnostics.activeConversationMessageCount} 条"),
    TextListItem("会话更新", diagnostics.activeConversationUpdatedText, null),
    TextListItem("Reply 策略", diagnostics.replyPipeline, null),
    TextListItem("历史落库", diagnostics.lastHistoryPersistText.ifBlank { "未落库" }, null),
    TextListItem("记忆维护", memoryStatus(diagnostics), diagnostics.lastMemoryUpdateText, divider = false),
    SpacerItem(24)
  )

  private fun memoryStatus(diagnostics: SettingsDiagnostics): String {
    return when (diagnostics.memoryState) {
      "started" -> "进行中"
      "success" -> "完成 ${diagnostics.memoryActionCount} 项"
      "skipped" -> "无变更"
      "failed" -> diagnostics.memoryError.ifBlank { "失败" }
      else -> "未开始"
    }
  }

  private fun shortText(value: String): String {
    return if (value.length <= 80) value else value.take(80) + "..."
  }

  private fun currentSection(): String {
    return arguments?.getString(ARG_SECTION) ?: HostSettingsNavigation.targetRuntimeStatus
  }

  companion object {
    private const val ARG_ENTRY_SOURCE = "entry_source"
    private const val ARG_SECTION = "section"

    fun newInstance(entrySource: String, section: String): StatusFragment {
      return StatusFragment().apply {
        arguments = Bundle().apply {
          putString(ARG_ENTRY_SOURCE, entrySource)
          putString(ARG_SECTION, section)
        }
      }
    }
  }
}

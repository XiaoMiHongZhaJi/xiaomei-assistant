package com.xiaomei.assistant.settings

import android.content.Intent
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

class TroubleshootFragment : BaseRootLayoutFragment() {
  private val viewModel by activityViewModels<SettingsViewModel> {
    SettingsViewModel.factory(requireContext())
  }
  private val listAdapter = SettingsListAdapter()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    titleText = getString(R.string.settings_troubleshoot_title)
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
      listOf(
        SpacerItem(8),
        HeaderItem("操作"),
        TextListItem(
          title = getString(R.string.settings_action_refresh),
          summary = "重新读取本地状态快照",
          onClick = { viewModel.refreshDiagnostics() }
        ),
        TextListItem(
          title = getString(R.string.settings_action_test_llm),
          summary = "使用当前配置测试模型连通性",
          value = if (diagnostics.llmTestInProgress) "测试中" else null,
          onClick = { viewModel.testLlmConnection() }
        ),
        TextListItem(
          title = getString(R.string.settings_action_open_host),
          summary = "打开小米运动健康",
          onClick = { openHostApp() }
        ),
        HeaderItem("最近状态"),
        TextListItem("最近事件", diagnostics.aivsEvent.ifBlank { "无" }, null),
        TextListItem("最近回退", diagnostics.fallbackReason.ifBlank { "无" }, null),
        TextListItem("最近错误", diagnostics.lastError.ifBlank { "无" }, null),
        TextListItem("记忆维护", troubleshootMemoryStatus(diagnostics), diagnostics.lastMemoryUpdateText, divider = false),
        SpacerItem(24)
      )
    )
  }

  private fun troubleshootMemoryStatus(diagnostics: SettingsDiagnostics): String {
    return when (diagnostics.memoryState) {
      "success" -> "完成 ${diagnostics.memoryActionCount} 项"
      "skipped" -> "无变更"
      "failed" -> diagnostics.memoryError.ifBlank { "失败" }
      "started" -> "进行中"
      else -> "未开始"
    }
  }

  private fun openHostApp() {
    val launchIntent = requireContext().packageManager.getLaunchIntentForPackage("com.mi.health")
    if (launchIntent != null) {
      startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }

  companion object {
    private const val ARG_ENTRY_SOURCE = "entry_source"

    fun newInstance(entrySource: String): TroubleshootFragment {
      return TroubleshootFragment().apply {
        arguments = Bundle().apply {
          putString(ARG_ENTRY_SOURCE, entrySource.ifBlank { HostSettingsNavigation.sourceModuleApp })
        }
      }
    }
  }
}

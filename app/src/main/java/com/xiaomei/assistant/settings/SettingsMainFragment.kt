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

class SettingsMainFragment : BaseRootLayoutFragment() {
  private val viewModel by activityViewModels<SettingsViewModel> {
    SettingsViewModel.factory(requireContext())
  }
  private val listAdapter = SettingsListAdapter()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    titleText = getString(R.string.settings_title)
  }

  override fun doOnCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    return createSettingsRecyclerView(requireContext(), listAdapter)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    rootLayoutView = view as ViewGroup
    applyRootLayoutPaddingFor(rootLayoutView!!)
    viewLifecycleOwner.lifecycleScope.launch {
      viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { render() }
      }
    }
  }

  private fun render() {
    listAdapter.submitList(
      listOf(
        SpacerItem(8),
        HeaderItem("核心配置"),
        TextListItem(
          title = getString(R.string.settings_llm_title),
          summary = "配置模型、密钥、端点与提示词",
          onClick = {
            requireSettingsHostActivity().presentFragment(LlmConfigFragment.newInstance(currentEntrySource()))
          }
        ),
        TextListItem(
          title = "自定义指令",
          summary = "配置自定义指令，命中后执行",
          divider = false,
          onClick = {
            requireSettingsHostActivity().presentFragment(CustomCommandFragment.newInstance(currentEntrySource()))
          }
        ),
        TextListItem(
          title = "接管规则",
          summary = "配置 ASR 黑名单，命中后放行官方 reply",
          divider = false,
          onClick = {
            requireSettingsHostActivity().presentFragment(AivsRulesFragment.newInstance(currentEntrySource()))
          }
        ),
        HeaderItem("运行状态"),
        TextListItem(
          title = getString(R.string.settings_runtime_title),
          summary = "查看主进程、device 进程与 Hook 回执",
          onClick = {
            requireSettingsHostActivity().presentFragment(
              StatusFragment.newInstance(currentEntrySource(), HostSettingsNavigation.targetRuntimeStatus)
            )
          }
        ),
        TextListItem(
          title = getString(R.string.settings_aivs_title),
          summary = "查看 ASR、LLM、reply 替换与回退结果",
          onClick = {
            requireSettingsHostActivity().presentFragment(
              StatusFragment.newInstance(currentEntrySource(), HostSettingsNavigation.targetAivsStatus)
            )
          }
        ),
        HeaderItem("调试与数据"),
        TextListItem(
          title = getString(R.string.settings_history_title),
          summary = getString(R.string.settings_history_subtitle),
          onClick = {
            requireSettingsHostActivity().presentFragment(HistoryFragment.newInstance(currentEntrySource()))
          }
        ),
        TextListItem(
          title = "记忆管理",
          summary = "管理长期偏好、事实与自动抽取结果",
          onClick = {
            requireSettingsHostActivity().presentFragment(MemoryFragment.newInstance(currentEntrySource()))
          }
        ),
        TextListItem(
          title = getString(R.string.settings_troubleshoot_title),
          summary = getString(R.string.settings_troubleshoot_subtitle),
          divider = false,
          onClick = {
            requireSettingsHostActivity().presentFragment(TroubleshootFragment.newInstance(currentEntrySource()))
          }
        ),
        SpacerItem(24)
      )
    )
  }

  private fun currentEntrySource(): String {
    return arguments?.getString(ARG_ENTRY_SOURCE) ?: HostSettingsNavigation.sourceModuleApp
  }

  companion object {
    private const val ARG_ENTRY_SOURCE = "entry_source"

    fun newInstance(entrySource: String): SettingsMainFragment {
      return SettingsMainFragment().apply {
        arguments = Bundle().apply {
          putString(ARG_ENTRY_SOURCE, entrySource)
        }
      }
    }
  }
}


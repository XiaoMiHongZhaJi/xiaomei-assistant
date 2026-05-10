package com.xiaomei.assistant.uihost

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.xiaomei.assistant.R
import com.xiaomei.assistant.host.HostSettingsNavigation
import com.xiaomei.assistant.ui.MainViewModel
import kotlinx.coroutines.launch

class SettingsMainFragment : BaseSettingFragment() {
  private val viewModel by activityViewModels<MainViewModel> {
    MainViewModel.factory(requireActivity().application)
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val entrySource = requireArguments().getString(ARG_ENTRY_SOURCE)
      ?: HostSettingsNavigation.sourceModuleApp
    setTitle("小美设置")
    setSubtitle(
      when (entrySource) {
        HostSettingsNavigation.sourceHostHookMine -> "宿主内入口 - 我的页"
        HostSettingsNavigation.sourceHostHookAbout -> "宿主内入口 - 关于页"
        HostSettingsNavigation.sourceHostHook -> "宿主内入口"
        HostSettingsNavigation.sourceLsposed -> "LSPosed 入口"
        else -> "模块状态入口"
      }
    )
  }

  override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    return inflater.inflate(R.layout.fragment_host_settings_main, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    view.findViewById<MaterialButton>(R.id.action_open_llm).setOnClickListener {
      requireSettingsHostActivity().presentFragment(LlmConfigFragment())
    }
    view.findViewById<MaterialButton>(R.id.action_test_llm).setOnClickListener {
      viewModel.testLlmConnection()
    }

    viewLifecycleOwner.lifecycleScope.launch {
      viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { uiState ->
          render(view, uiState)
        }
      }
    }
  }

  private fun render(root: View, state: com.xiaomei.assistant.model.MainUiState) {
    val diagnostics = state.diagnostics
    root.findViewById<TextView>(R.id.status_architecture).text = diagnostics.architecture
    root.findViewById<TextView>(R.id.status_host).text =
      "${diagnostics.hostPackage} / ${diagnostics.hostVersion}"
    root.findViewById<TextView>(R.id.status_hook).text = diagnostics.hookStatus
    root.findViewById<TextView>(R.id.status_pipeline).text = diagnostics.replyPipeline
    root.findViewById<TextView>(R.id.status_entry).text = diagnostics.hostEntry
    root.findViewById<TextView>(R.id.status_asr).text = diagnostics.officialAsr
    root.findViewById<TextView>(R.id.status_llm).text =
      if (diagnostics.llmTestInProgress) {
        "LLM 连通性测试中..."
      } else {
        "LLM 测试状态：${diagnostics.llmTestStatus}"
      }

    val recentHistory = if (state.history.isEmpty()) {
      "暂无会话历史。当前重点是先把新版宿主入口和 AIVS 链路打通。"
    } else {
      state.history.take(3).joinToString("\n\n") { item ->
        "Q: ${item.question}\nA: ${item.summary}"
      }
    }
    root.findViewById<TextView>(R.id.status_recent_history).text = recentHistory

    val errorView = root.findViewById<TextView>(R.id.status_error)
    errorView.isVisible = diagnostics.lastError.isNotBlank()
    errorView.text = diagnostics.lastError
  }

  companion object {
    private const val ARG_ENTRY_SOURCE = "entry_source"
    private const val ARG_ENTRY_TARGET = "entry_target"

    fun newInstance(
      entrySource: String,
      entryTarget: String
    ): SettingsMainFragment {
      return SettingsMainFragment().apply {
        arguments = Bundle().apply {
          putString(ARG_ENTRY_SOURCE, entrySource)
          putString(ARG_ENTRY_TARGET, entryTarget)
        }
      }
    }
  }
}

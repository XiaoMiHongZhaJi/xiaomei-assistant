package com.xiaomei.assistant.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.xiaomei.assistant.R
import com.xiaomei.assistant.host.HostSettingsNavigation
import com.xiaomei.assistant.model.LlmConfig
import kotlinx.coroutines.launch

class LlmConfigFragment : BaseRootLayoutFragment() {
  private val viewModel by activityViewModels<SettingsViewModel> {
    SettingsViewModel.factory(requireContext())
  }
  private var initialized = false
  private var latestConfig = LlmConfig()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    titleText = getString(R.string.settings_llm_title)
  }

  override fun doOnCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    return inflater.inflate(R.layout.fragment_llm_config, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    rootLayoutView = view as ViewGroup
    applyRootLayoutPaddingFor(rootLayoutView!!)
    LlmProviderUi.setup(view)

    view.findViewById<MaterialButton>(R.id.action_save_config).setOnClickListener {
      val config = readConfigFromInputs(view) ?: return@setOnClickListener
      viewLifecycleOwner.lifecycleScope.launch {
        bindRuntimeState(view, "保存中...")
        val success = viewModel.saveConfigBlocking(config)
        bindRuntimeState(view, if (success) "配置已保存" else "配置保存失败")
        Toast.makeText(requireContext(), if (success) "配置已保存" else "配置保存失败", Toast.LENGTH_SHORT).show()
      }
    }
    view.findViewById<MaterialButton>(R.id.action_test_config).setOnClickListener {
      val config = readConfigFromInputs(view) ?: return@setOnClickListener
      viewLifecycleOwner.lifecycleScope.launch {
        bindRuntimeState(view, "保存中...")
        val success = viewModel.saveConfigBlocking(config)
        if (!success) {
          bindRuntimeState(view, "配置保存失败")
          Toast.makeText(requireContext(), "配置保存失败", Toast.LENGTH_SHORT).show()
          return@launch
        }
        viewModel.testLlmConnection(config)
      }
    }

    viewLifecycleOwner.lifecycleScope.launch {
      viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { uiState ->
          latestConfig = uiState.config
          if (!initialized) {
            bindConfig(view, uiState.config)
            initialized = true
          }
          bindDiagnostics(view, uiState.diagnostics)
        }
      }
    }
  }

  private fun bindConfig(root: View, config: LlmConfig) {
    root.findViewById<EditText>(R.id.input_base_url).setText(config.baseUrl)
    root.findViewById<EditText>(R.id.input_api_key).setText(config.apiKey)
    root.findViewById<EditText>(R.id.input_model).setText(config.model)
    LlmProviderUi.bind(root, config)
    root.findViewById<EditText>(R.id.input_system_prompt).setText(config.systemPrompt)
    root.findViewById<EditText>(R.id.input_temperature).setText(config.temperature.toString())
    root.findViewById<EditText>(R.id.input_max_tokens).setText(config.maxTokens.toString())
  }

  private fun bindDiagnostics(root: View, diagnostics: SettingsDiagnostics) {
    val text = buildString {
      append("当前宿主：")
      append(diagnostics.hostVersion)
      append('\n')
      append("连通性：")
      append(
        if (diagnostics.llmTestInProgress) {
          "测试中..."
        } else {
          diagnostics.llmTestStatus
        }
      )
      if (diagnostics.lastError.isNotBlank()) {
        append('\n')
        append("最近错误：")
        append(diagnostics.lastError)
      }
    }
    root.findViewById<TextView>(R.id.text_runtime_state).text = text
  }

  private fun bindRuntimeState(root: View, text: String) {
    root.findViewById<TextView>(R.id.text_runtime_state).text = text
  }

  private fun readConfigFromInputs(root: View): LlmConfig? {
    val temperatureText = root.findViewById<EditText>(R.id.input_temperature).text.toString().trim()
    val maxTokensText = root.findViewById<EditText>(R.id.input_max_tokens).text.toString().trim()
    val temperature = temperatureText.toFloatOrNull()
    val maxTokens = maxTokensText.toIntOrNull()
    if (temperature == null || maxTokens == null) {
      Toast.makeText(requireContext(), "Temperature 或 Max Tokens 格式无效", Toast.LENGTH_SHORT).show()
      return null
    }
    return latestConfig.copy(
      baseUrl = root.findViewById<EditText>(R.id.input_base_url).text.toString().trim(),
      apiKey = root.findViewById<EditText>(R.id.input_api_key).text.toString().trim(),
      model = root.findViewById<EditText>(R.id.input_model).text.toString().trim(),
      provider = LlmProviderUi.selectedProvider(root),
      apiMode = LlmProviderUi.selectedApiMode(root),
      systemPrompt = root.findViewById<EditText>(R.id.input_system_prompt).text.toString(),
      temperature = temperature,
      maxTokens = maxTokens
    )
  }

  companion object {
    private const val ARG_ENTRY_SOURCE = "entry_source"

    fun newInstance(entrySource: String): LlmConfigFragment {
      return LlmConfigFragment().apply {
        arguments = Bundle().apply {
          putString(ARG_ENTRY_SOURCE, entrySource.ifBlank { HostSettingsNavigation.sourceModuleApp })
        }
      }
    }
  }
}


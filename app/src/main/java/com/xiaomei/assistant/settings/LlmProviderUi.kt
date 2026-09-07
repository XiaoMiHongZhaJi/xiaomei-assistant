package com.xiaomei.assistant.settings

import android.view.View
import android.view.MotionEvent
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import com.xiaomei.assistant.R
import com.xiaomei.assistant.model.LlmApiMode
import com.xiaomei.assistant.model.LlmConfig
import com.xiaomei.assistant.model.LlmProvider
import java.util.WeakHashMap

internal object LlmProviderUi {
  private data class Option(val label: String, val value: String)
  private data class UiState(
    var binding: Boolean = false,
    var providerTouched: Boolean = false,
    var lastProvider: String = LlmProvider.OPENAI
  )

  private val states = WeakHashMap<View, UiState>()

  private val providerOptions = listOf(
    Option("OpenAI", LlmProvider.OPENAI),
    Option("Xiaomi MiMo", LlmProvider.XIAOMI_MIMO),
    Option("Google Gemini", LlmProvider.GEMINI)
  )

  private val openAiModes = listOf(
    Option("Chat Completions", LlmApiMode.OPENAI_CHAT_COMPLETIONS),
    Option("Responses", LlmApiMode.OPENAI_RESPONSES)
  )

  private val mimoModes = listOf(
    Option("MiMo Chat Completions", LlmApiMode.MIMO_CHAT_COMPLETIONS)
  )

  private val geminiModes = listOf(
    Option("Gemini Generate Content", LlmApiMode.GEMINI_GENERATE_CONTENT)
  )

  fun setup(root: View) {
    val state = stateFor(root)
    val providerSpinner = root.findViewById<Spinner>(R.id.input_provider)
    providerSpinner.adapter = optionsAdapter(root, providerOptions)
    providerSpinner.setOnTouchListener { _, event ->
      if (event.actionMasked == MotionEvent.ACTION_DOWN) {
        state.providerTouched = true
      }
      false
    }
    providerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        if (state.binding) return
        val provider = selectedProvider(root)
        if (provider == state.lastProvider) {
          state.providerTouched = false
          return
        }
        state.providerTouched = false
        state.lastProvider = provider
        updateModeOptions(root, provider, null)
      }

      override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    state.binding = true
    try {
      updateModeOptions(root, LlmProvider.OPENAI, LlmApiMode.OPENAI_CHAT_COMPLETIONS)
      state.lastProvider = LlmProvider.OPENAI
    } finally {
      state.binding = false
    }
  }

  fun bind(root: View, config: LlmConfig) {
    val state = stateFor(root)
    val provider = LlmProvider.normalize(config.provider)
    val providerIndex = providerOptions.indexOfFirst { it.value == provider }.coerceAtLeast(0)
    state.binding = true
    try {
      state.providerTouched = false
      root.findViewById<Spinner>(R.id.input_provider).setSelection(providerIndex)
      updateModeOptions(root, provider, LlmApiMode.normalize(provider, config.apiMode))
      state.lastProvider = provider
    } finally {
      root.post {
        state.binding = false
      }
    }
  }

  fun selectedProvider(root: View): String {
    val position = root.findViewById<Spinner>(R.id.input_provider).selectedItemPosition
    return providerOptions.getOrElse(position) { providerOptions.first() }.value
  }

  fun selectedApiMode(root: View): String {
    val provider = selectedProvider(root)
    val options = modeOptions(provider)
    val position = root.findViewById<Spinner>(R.id.input_api_mode).selectedItemPosition
    return options.getOrElse(position) { options.first() }.value
  }

  private fun updateModeOptions(root: View, provider: String, preferredMode: String?) {
    val normalizedProvider = LlmProvider.normalize(provider)
    val options = modeOptions(normalizedProvider)
    val normalizedMode = LlmApiMode.normalize(
      normalizedProvider,
      preferredMode ?: options.first().value
    )
    val spinner = root.findViewById<Spinner>(R.id.input_api_mode)
    spinner.adapter = optionsAdapter(root, options)
    spinner.isEnabled = normalizedProvider == LlmProvider.OPENAI
    spinner.setSelection(options.indexOfFirst { it.value == normalizedMode }.coerceAtLeast(0))
  }

  private fun modeOptions(provider: String): List<Option> {
    return when (LlmProvider.normalize(provider)) {
      LlmProvider.XIAOMI_MIMO -> mimoModes
      LlmProvider.GEMINI -> geminiModes
      else -> openAiModes
    }
  }

  private fun optionsAdapter(root: View, options: List<Option>): ArrayAdapter<String> {
    return ArrayAdapter(
      root.context,
      android.R.layout.simple_spinner_item,
      options.map { it.label }
    ).apply {
      setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
    }
  }

  private fun stateFor(root: View): UiState {
    return states.getOrPut(root) { UiState() }
  }
}

package com.xiaomei.assistant.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xiaomei.assistant.host.HostSettingsNavigation
import com.xiaomei.assistant.model.AivsAsrBlacklistMatcher
import com.xiaomei.assistant.model.AivsAsrBlacklistRule
import com.xiaomei.assistant.model.LlmConfig
import com.xiaomei.assistant.settings.dsl.DescriptionItem
import com.xiaomei.assistant.settings.dsl.HeaderItem
import com.xiaomei.assistant.settings.dsl.SettingsListAdapter
import com.xiaomei.assistant.settings.dsl.SettingsListItem
import com.xiaomei.assistant.settings.dsl.SpacerItem
import com.xiaomei.assistant.settings.dsl.TextListItem
import com.xiaomei.assistant.settings.dsl.createSettingsRecyclerView
import java.util.UUID
import kotlinx.coroutines.launch

class AivsRulesFragment : BaseRootLayoutFragment() {
  private val viewModel by activityViewModels<SettingsViewModel> {
    SettingsViewModel.factory(requireContext())
  }
  private val listAdapter = SettingsListAdapter()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    titleText = "接管规则"
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
        viewModel.uiState.collect { render(it.config) }
      }
    }
  }

  private fun render(config: LlmConfig) {
    val items = ArrayList<SettingsListItem>()
    items.add(SpacerItem(8))
    items.add(HeaderItem("ASR 黑名单"))
    items.add(
      TextListItem(
        title = "启用黑名单",
        summary = "命中 final ASR 后放行官方 reply，不请求 LLM",
        hasSwitch = true,
        checked = config.asrBlacklistEnabled,
        onClick = { viewModel.setAsrBlacklistEnabled(!config.asrBlacklistEnabled) }
      )
    )
    items.add(
      TextListItem(
        title = "新增规则",
        summary = "支持关键词包含、关键词等于、正则包含、正则全匹配",
        divider = false,
        onClick = { showRuleEditor(null) }
      )
    )
    items.add(HeaderItem("规则"))
    if (config.asrBlacklistRules.isEmpty()) {
      items.add(DescriptionItem("暂无规则"))
    } else {
      config.asrBlacklistRules.forEachIndexed { index, rule ->
        items.add(
          TextListItem(
            title = rule.pattern,
            summary = ruleMode(rule),
            value = if (rule.enabled) "启用" else "停用",
            hasSwitch = true,
            checked = rule.enabled,
            divider = index != config.asrBlacklistRules.lastIndex,
            onClick = { showRuleActions(rule) }
          )
        )
      }
    }
    items.add(SpacerItem(24))
    listAdapter.submitList(items)
  }

  private fun showRuleActions(rule: AivsAsrBlacklistRule) {
    val nextEnabled = if (rule.enabled) "停用" else "启用"
    AlertDialog.Builder(requireContext())
      .setTitle("ASR 黑名单规则")
      .setMessage("${ruleMode(rule)}\n${rule.pattern}")
      .setPositiveButton("编辑") { _, _ -> showRuleEditor(rule) }
      .setNeutralButton(nextEnabled) { _, _ ->
        viewModel.upsertAsrBlacklistRule(rule.copy(enabled = !rule.enabled, updatedAt = System.currentTimeMillis()))
      }
      .setNegativeButton("删除") { _, _ -> confirmDelete(rule) }
      .show()
  }

  private fun showRuleEditor(rule: AivsAsrBlacklistRule?) {
    val patternInput = EditText(requireContext()).apply {
      minLines = 2
      setText(rule?.pattern.orEmpty())
      setSelection(text.length)
      hint = "关键词或正则表达式"
    }
    val regexCheck = CheckBox(requireContext()).apply {
      text = "使用正则表达式"
      isChecked = rule?.regex ?: false
    }
    val containsCheck = CheckBox(requireContext()).apply {
      text = "包含匹配"
      isChecked = rule?.contains ?: true
    }
    val enabledCheck = CheckBox(requireContext()).apply {
      text = "启用规则"
      isChecked = rule?.enabled ?: true
    }
    val content = LinearLayout(requireContext()).apply {
      orientation = LinearLayout.VERTICAL
      val padding = dp(20)
      setPadding(padding, padding / 2, padding, 0)
      addView(patternInput)
      addView(regexCheck)
      addView(containsCheck)
      addView(enabledCheck)
    }
    AlertDialog.Builder(requireContext())
      .setTitle(if (rule == null) "新增规则" else "编辑规则")
      .setView(content)
      .setPositiveButton("保存", null)
      .setNegativeButton("取消", null)
      .create()
      .apply {
        setOnShowListener {
          getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val pattern = patternInput.text?.toString().orEmpty()
            val error = AivsAsrBlacklistMatcher.validate(pattern, regexCheck.isChecked)
            if (error != null) {
              Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
              return@setOnClickListener
            }
            val now = System.currentTimeMillis()
            viewModel.upsertAsrBlacklistRule(
              AivsAsrBlacklistRule(
                id = rule?.id ?: UUID.randomUUID().toString(),
                pattern = pattern.trim(),
                enabled = enabledCheck.isChecked,
                regex = regexCheck.isChecked,
                contains = containsCheck.isChecked,
                createdAt = rule?.createdAt ?: now,
                updatedAt = now
              )
            )
            dismiss()
          }
        }
      }
      .show()
  }

  private fun confirmDelete(rule: AivsAsrBlacklistRule) {
    AlertDialog.Builder(requireContext())
      .setTitle("删除规则")
      .setMessage(rule.pattern)
      .setPositiveButton("删除") { _, _ -> viewModel.deleteAsrBlacklistRule(rule.id) }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun ruleMode(rule: AivsAsrBlacklistRule): String {
    return if (rule.regex) {
      if (rule.contains) "正则包含" else "正则全匹配"
    } else {
      if (rule.contains) "关键词包含" else "关键词等于"
    }
  }

  private fun dp(value: Int): Int {
    return (value * resources.displayMetrics.density + 0.5f).toInt()
  }

  companion object {
    private const val ARG_ENTRY_SOURCE = "entry_source"

    fun newInstance(entrySource: String): AivsRulesFragment {
      return AivsRulesFragment().apply {
        arguments = Bundle().apply {
          putString(ARG_ENTRY_SOURCE, entrySource.ifBlank { HostSettingsNavigation.sourceModuleApp })
        }
      }
    }
  }
}

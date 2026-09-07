package com.xiaomei.assistant.settings

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xiaomei.assistant.host.HostSettingsNavigation
import com.xiaomei.assistant.model.CustomCommandMatcher
import com.xiaomei.assistant.model.CustomCommandRule
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

class CustomCommandFragment : BaseRootLayoutFragment() {

  private val viewModel by activityViewModels<SettingsViewModel> {
    SettingsViewModel.factory(requireContext())
  }

  private val listAdapter = SettingsListAdapter()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    titleText = "自定义指令"
  }

  override fun doOnCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View {
    return createSettingsRecyclerView(requireContext(), listAdapter)
  }

  override fun onViewCreated(
    view: View,
    savedInstanceState: Bundle?
  ) {
    super.onViewCreated(view, savedInstanceState)

    rootLayoutView = view as ViewGroup
    applyRootLayoutPaddingFor(rootLayoutView!!)

    viewLifecycleOwner.lifecycleScope.launch {
      viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect {
          render(it.config)
        }
      }
    }
  }

  private fun render(config: LlmConfig) {
    val items = ArrayList<SettingsListItem>()

    items.add(SpacerItem(8))

    items.add(HeaderItem("自定义指令"))

    items.add(
      TextListItem(
        title = "启用自定义指令",
        summary = "命中 ASR 后执行对应指令，不请求 LLM",
        hasSwitch = true,
        checked = config.customCommandEnabled,
        onClick = {
          viewModel.setCustomCommandEnabled(
            !config.customCommandEnabled
          )
        }
      )
    )

    items.add(
      TextListItem(
        title = "新增指令",
        summary = "支持关键词包含、关键词等于、正则包含、正则全匹配",
        divider = false,
        onClick = {
          showCommandEditor(null)
        }
      )
    )

    items.add(HeaderItem("指令规则"))

    if (config.customCommandRules.isEmpty()) {
      items.add(
        DescriptionItem("暂无自定义指令")
      )
    } else {
      config.customCommandRules.forEachIndexed { index, rule ->

        items.add(
          TextListItem(
            title = rule.name.ifBlank { rule.pattern },
            summary = buildRuleSummary(rule),
            value = if (rule.enabled) "启用" else "停用",
            hasSwitch = true,
            checked = rule.enabled,
            divider = index != config.customCommandRules.lastIndex,
            onClick = {
              showCommandActions(rule)
            }
          )
        )
      }
    }

    items.add(SpacerItem(24))

    listAdapter.submitList(items)
  }

  private fun showCommandActions(
    rule: CustomCommandRule
  ) {
    val nextEnabled = if (rule.enabled) "停用" else "启用"

    AlertDialog.Builder(requireContext())
      .setTitle("自定义指令")
      .setMessage(
        buildString {
          append(rule.name.ifBlank { "未命名指令" })
          append("\n\n")
          append("匹配：")
          append(rule.pattern)
          append("\n\n")
          append("指令：")
          append(rule.command)
          append("\n\n")
          append(ruleMode(rule))
        }
      )
      .setPositiveButton("编辑") { _, _ ->
        showCommandEditor(rule)
      }
      .setNeutralButton(nextEnabled) { _, _ ->
        viewModel.upsertCustomCommandRule(
          rule.copy(
            enabled = !rule.enabled,
            updatedAt = System.currentTimeMillis()
          )
        )
      }
      .setNegativeButton("删除") { _, _ ->
        confirmDelete(rule)
      }
      .show()
  }

  private fun showCommandEditor(
    rule: CustomCommandRule?
  ) {
    val nameInput = EditText(requireContext()).apply {
      minLines = 1
      setSingleLine(false)
      setText(rule?.name.orEmpty())
      setSelection(text.length)
      hint = "例如：打开客厅灯"
    }

    val patternInput = EditText(requireContext()).apply {
      minLines = 2
      setSingleLine(false)
      setText(rule?.pattern.orEmpty())
      setSelection(text.length)
      hint = "关键词或正则表达式"
    }

    val commandInput = EditText(requireContext()).apply {
      minLines = 3
      maxLines = 6
      setSingleLine(false)
      setText(rule?.command.orEmpty())
      setSelection(text.length)
      hint = "例如：curl http://127.0.0.1:8080/light"
      inputType =
        InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
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

    val helpText = TextView(requireContext()).apply {
      text = buildString {
        append("正则参数：\n")
        append("\$0 = 整个匹配内容\n")
        append("\$1 = 第 1 个捕获组\n")
        append("\$2 = 第 2 个捕获组\n")
        append("……\n")
        append("{input} = 完整 ASR 文本")
      }

      val padding = dp(4)
      setPadding(0, padding, 0, padding)
      textSize = 13f
    }

    val content = LinearLayout(requireContext()).apply {
      orientation = LinearLayout.VERTICAL

      val padding = dp(20)

      setPadding(
        padding,
        padding / 2,
        padding,
        0
      )

      addView(
        createLabel("名称")
      )

      addView(nameInput)

      addView(
        createLabel("匹配内容")
      )

      addView(patternInput)

      addView(regexCheck)
      addView(containsCheck)

      addView(
        createLabel("执行指令")
      )

      addView(commandInput)

      addView(helpText)

      addView(enabledCheck)
    }

    AlertDialog.Builder(requireContext())
      .setTitle(
        if (rule == null) {
          "新增指令"
        } else {
          "编辑指令"
        }
      )
      .setView(content)
      .setPositiveButton("保存", null)
      .setNegativeButton("取消", null)
      .create()
      .apply {

        setOnShowListener {

          getButton(
            AlertDialog.BUTTON_POSITIVE
          ).setOnClickListener {

            val name =
              nameInput.text
                ?.toString()
                .orEmpty()
                .trim()

            val pattern =
              patternInput.text
                ?.toString()
                .orEmpty()
                .trim()

            val command =
              commandInput.text
                ?.toString()
                .orEmpty()
                .trim()

            if (name.isBlank()) {
              Toast.makeText(
                requireContext(),
                "请输入指令名称",
                Toast.LENGTH_SHORT
              ).show()

              return@setOnClickListener
            }

            if (pattern.isBlank()) {
              Toast.makeText(
                requireContext(),
                "请输入匹配内容",
                Toast.LENGTH_SHORT
              ).show()

              return@setOnClickListener
            }

            if (command.isBlank()) {
              Toast.makeText(
                requireContext(),
                "请输入执行指令",
                Toast.LENGTH_SHORT
              ).show()

              return@setOnClickListener
            }

            val error =
              CustomCommandMatcher.validate(
                pattern,
                regexCheck.isChecked
              )

            if (error != null) {
              Toast.makeText(
                requireContext(),
                error,
                Toast.LENGTH_SHORT
              ).show()

              return@setOnClickListener
            }

            val now =
              System.currentTimeMillis()

            val newRule =
              CustomCommandRule(
                id = rule?.id
                  ?: UUID.randomUUID().toString(),

                name = name,

                pattern = pattern,

                command = command,

                enabled =
                  enabledCheck.isChecked,

                regex =
                  regexCheck.isChecked,

                contains =
                  containsCheck.isChecked,

                createdAt =
                  rule?.createdAt ?: now,

                updatedAt = now
              )

            viewModel.upsertCustomCommandRule(
              newRule
            )

            dismiss()
          }
        }
      }
      .show()
  }

  private fun confirmDelete(
    rule: CustomCommandRule
  ) {
    AlertDialog.Builder(requireContext())
      .setTitle("删除指令")
      .setMessage(
        rule.name.ifBlank {
          rule.pattern
        }
      )
      .setPositiveButton("删除") { _, _ ->
        viewModel.deleteCustomCommandRule(
          rule.id
        )
      }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun buildRuleSummary(
    rule: CustomCommandRule
  ): String {
    return buildString {
      append(ruleMode(rule))

      if (rule.command.isNotBlank()) {
        append(" · ")
        append(rule.command)
      }
    }
  }

  private fun ruleMode(
    rule: CustomCommandRule
  ): String {
    return if (rule.regex) {
      if (rule.contains) {
        "正则包含"
      } else {
        "正则全匹配"
      }
    } else {
      if (rule.contains) {
        "关键词包含"
      } else {
        "关键词等于"
      }
    }
  }

  private fun createLabel(
    text: String
  ): TextView {
    return TextView(requireContext()).apply {
      this.text = text
      textSize = 13f

      val top = dp(8)
      val bottom = dp(2)

      setPadding(
        0,
        top,
        0,
        bottom
      )
    }
  }

  private fun dp(
    value: Int
  ): Int {
    return (
            value *
                    resources.displayMetrics.density +
                    0.5f
            ).toInt()
  }

  companion object {

    private const val ARG_ENTRY_SOURCE =
      "entry_source"

    fun newInstance(
      entrySource: String
    ): CustomCommandFragment {
      return CustomCommandFragment().apply {

        arguments = Bundle().apply {
          putString(
            ARG_ENTRY_SOURCE,
            entrySource.ifBlank {
              HostSettingsNavigation
                .sourceModuleApp
            }
          )
        }
      }
    }
  }
}
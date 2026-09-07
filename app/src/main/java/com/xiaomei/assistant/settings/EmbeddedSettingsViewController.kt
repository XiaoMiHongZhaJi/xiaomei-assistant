package com.xiaomei.assistant.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.xiaomei.assistant.R
import com.xiaomei.assistant.host.HostSettingsNavigation
import com.xiaomei.assistant.model.AivsAsrBlacklistMatcher
import com.xiaomei.assistant.model.AivsAsrBlacklistRule
import com.xiaomei.assistant.model.CustomCommandMatcher
import com.xiaomei.assistant.model.CustomCommandRule
import com.xiaomei.assistant.model.ConversationMessageRecord
import com.xiaomei.assistant.model.ConversationMessageRole
import com.xiaomei.assistant.model.ConversationRecord
import com.xiaomei.assistant.model.ConversationState
import com.xiaomei.assistant.model.LlmConfig
import com.xiaomei.assistant.model.MemoryRecord
import com.xiaomei.assistant.model.MemorySource
import com.xiaomei.assistant.model.MemoryToolAction
import com.xiaomei.assistant.model.MemoryToolActionType
import com.xiaomei.assistant.runtime.RuntimeContainer
import com.xiaomei.assistant.settings.dsl.DescriptionItem
import com.xiaomei.assistant.settings.dsl.HeaderItem
import com.xiaomei.assistant.settings.dsl.SettingsListAdapter
import com.xiaomei.assistant.settings.dsl.SettingsListItem
import com.xiaomei.assistant.settings.dsl.SpacerItem
import com.xiaomei.assistant.settings.dsl.TextListItem
import com.xiaomei.assistant.settings.dsl.createSettingsRecyclerView
import com.xiaomei.assistant.status.AivsDebugSnapshot
import com.xiaomei.assistant.status.AivsDebugSnapshotStore
import com.xiaomei.assistant.status.ModuleProcessStatus
import com.xiaomei.assistant.status.ModuleRuntimeStatusStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.UUID

internal class EmbeddedSettingsViewController(
  private val context: Context,
  private val moduleContext: Context,
  private val hostActivity: Activity,
  extras: Bundle?,
  private val dismissHost: () -> Unit
) {
  val rootView: FrameLayout = FrameLayout(context)

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
  private val runtime = RuntimeContainer(hostActivity)
  private val diagnosticsContext = hostActivity.applicationContext ?: hostActivity
  private val container = FrameLayout(context)
  private val topBar = FrameLayout(context)
  private val titleView = TextView(context)
  private val backButton = TextView(context)
  private val stack = ArrayList<Page>()
  private var latestLlmConfig = LlmConfig()

  init {
    buildRootView()
    backButton.setOnClickListener {
      if (!popBackStack()) dismissHost()
    }
    val entrySource = extras?.getString(HostSettingsNavigation.extraEntrySource) ?: HostSettingsNavigation.sourceHostHook
    when (extras?.getString(HostSettingsNavigation.extraEntryTarget)) {
      HostSettingsNavigation.targetLlmConfig -> showLlmConfigPage(entrySource, replace = true)
      HostSettingsNavigation.targetAivsRules -> showAivsRulesPage(replace = true)
      HostSettingsNavigation.targetCustomCommand -> showCustomCommandPage(replace = true)
      HostSettingsNavigation.targetRuntimeStatus -> showStatusPage(HostSettingsNavigation.targetRuntimeStatus, replace = true)
      HostSettingsNavigation.targetAivsStatus -> showStatusPage(HostSettingsNavigation.targetAivsStatus, replace = true)
      HostSettingsNavigation.targetSessionHistory -> showHistoryPage(replace = true)
      HostSettingsNavigation.targetTroubleshoot -> showTroubleshootPage(replace = true)
      else -> showMainPage(entrySource, replace = true)
    }
  }

  fun destroy() {
    scope.cancel()
  }

  fun popBackStack(): Boolean {
    if (stack.size <= 1) {
      return false
    }
    stack.removeAt(stack.lastIndex)
    renderTopPage()
    return true
  }

  private fun showMainPage(entrySource: String, replace: Boolean = false) {
    val adapter = SettingsListAdapter()
    val view = createPaddedList(adapter)
    adapter.submitList(
      listOf(
        SpacerItem(8),
        HeaderItem("核心配置"),
        TextListItem("LLM 配置", "配置模型、密钥、端点与提示词") {
          showLlmConfigPage(entrySource)
        },
        TextListItem("自定义指令", "配置自定义指令，命中后执行", divider = false) {
          showCustomCommandPage()
        },
        TextListItem("接管规则", "配置 ASR 黑名单，命中后放行官方 reply", divider = false) {
          showAivsRulesPage()
        },
        HeaderItem("运行状态"),
        TextListItem("运行状态", "查看主进程、device 进程与 Hook 回执") {
          showStatusPage(HostSettingsNavigation.targetRuntimeStatus)
        },
        TextListItem("AIVS 接管状态", "查看 ASR、LLM、reply 替换与回退结果") {
          showStatusPage(HostSettingsNavigation.targetAivsStatus)
        },
        HeaderItem("调试与数据"),
        TextListItem("会话管理", "选择当前会话，隔离短期上下文") {
          showHistoryPage()
        },
        TextListItem("记忆管理", "管理长期偏好、事实与自动抽取结果") {
          showMemoryPage()
        },
        TextListItem("故障排查", "刷新状态、测试 LLM 与查看最近错误", divider = false) {
          showTroubleshootPage()
        },
        SpacerItem(24)
      )
    )
    pushPage(Page("小美助手", view), replace)
  }

  private fun showStatusPage(section: String, replace: Boolean = false) {
    val adapter = SettingsListAdapter()
    val title = if (section == HostSettingsNavigation.targetAivsStatus) "AIVS 接管状态" else "运行状态"
    val view = createPaddedList(adapter)
    adapter.submitList(listOf(SpacerItem(8), DescriptionItem("正在读取状态..."), SpacerItem(24)))
    pushPage(Page(title, view), replace)
    scope.launch {
      while (stack.lastOrNull()?.view === view) {
        val diagnostics = withContext(Dispatchers.IO) { buildDiagnosticsSafely() }
        adapter.submitList(
          if (section == HostSettingsNavigation.targetAivsStatus) {
            buildAivsItems(diagnostics)
          } else {
            buildRuntimeItems(diagnostics)
          }
        )
        delay(STATUS_REFRESH_INTERVAL_MS)
      }
    }
  }

  private fun showHistoryPage(replace: Boolean = false) {
    val adapter = SettingsListAdapter()
    val view = createPaddedList(adapter)
    adapter.submitList(listOf(SpacerItem(8), DescriptionItem("正在读取会话..."), SpacerItem(24)))
    pushPage(
      Page(
        title = "会话管理",
        view = view,
        onVisible = { renderConversationList(adapter) }
      ),
      replace
    )
  }

  private fun renderConversationList(adapter: SettingsListAdapter) {
    scope.launch {
      val state = withContext(Dispatchers.IO) {
        runCatching { runtime.conversationRepository.stateSnapshot() }.getOrDefault(ConversationState())
      }
      val items = ArrayList<SettingsListItem>()
      items.add(SpacerItem(8))
      items.add(HeaderItem("操作"))
      items.add(
        TextListItem("新建会话", "创建新的短期上下文，不自动切换", divider = false) {
          showConversationEditor(null) { renderConversationList(adapter) }
        }
      )
      items.add(HeaderItem("会话"))
      if (state.conversations.isEmpty()) {
        items.add(DescriptionItem("暂无会话"))
      } else {
        state.conversations.forEachIndexed { index, conversation ->
          val active = conversation.id == state.activeConversationId
          items.add(
            TextListItem(
              title = if (active) "当前 · ${conversation.title}" else conversation.title,
              summary = "${conversation.messageCount} 条 / ${formatTimestamp(conversation.updatedAt, "MM-dd HH:mm")} / ${conversation.lastMessagePreview.ifBlank { "暂无消息" }}",
              divider = index != state.conversations.lastIndex
            ) {
              showConversationDetail(conversation.id)
            }
          )
        }
      }
      items.add(SpacerItem(24))
      adapter.submitList(items)
    }
  }

  private fun showConversationDetail(conversationId: String, replace: Boolean = false) {
    val adapter = SettingsListAdapter()
    val view = createPaddedList(adapter)
    adapter.submitList(listOf(SpacerItem(8), DescriptionItem("正在读取消息..."), SpacerItem(24)))
    pushPage(Page("会话详情", view), replace)
    renderConversationDetail(adapter, conversationId)
  }

  private fun renderConversationDetail(adapter: SettingsListAdapter, conversationId: String) {
    scope.launch {
      val state = withContext(Dispatchers.IO) {
        runCatching { runtime.conversationRepository.stateSnapshot() }.getOrDefault(ConversationState())
      }
      val conversation = state.conversations.firstOrNull { it.id == conversationId }
      if (conversation == null) {
        adapter.submitList(listOf(SpacerItem(8), DescriptionItem("会话不存在"), SpacerItem(24)))
        return@launch
      }
      val messages = state.messages.filter { it.conversationId == conversationId }.sortedBy { it.createdAt }
      val items = ArrayList<SettingsListItem>()
      items.add(SpacerItem(8))
      items.add(HeaderItem(conversation.title))
      items.add(
        TextListItem("切换为当前会话", if (conversation.id == state.activeConversationId) "已是当前会话" else "后续 AIVS 将使用这个会话") {
          scope.launch {
            withContext(Dispatchers.IO) {
              runtime.conversationRepository.setActiveConversation(conversation.id)
            }
            renderConversationDetail(adapter, conversationId)
          }
        }
      )
      items.add(TextListItem("重命名", "修改会话标题") { showConversationEditor(conversation) { renderConversationDetail(adapter, conversationId) } })
      items.add(TextListItem("清空消息", "保留会话，但移除短期上下文") { confirmClearConversation(conversation) { renderConversationDetail(adapter, conversationId) } })
      items.add(TextListItem("删除会话", "删除标题和所有消息", divider = false) { confirmDeleteConversation(conversation) })
      items.add(HeaderItem("消息"))
      if (messages.isEmpty()) {
        items.add(DescriptionItem("暂无消息"))
      } else {
        messages.forEachIndexed { index, message ->
          items.add(
            TextListItem(
              title = conversationRoleTitle(message),
              summary = message.content.takeWithEllipsis(160),
              value = formatTimestamp(message.createdAt, "MM-dd HH:mm"),
              divider = index != messages.lastIndex
            )
          )
        }
      }
      items.add(SpacerItem(24))
      adapter.submitList(items)
    }
  }

  private fun showConversationEditor(record: ConversationRecord?, onChanged: () -> Unit) {
    val input = EditText(context).apply {
      setText(record?.title.orEmpty())
      setSelection(text.length)
      hint = "会话标题"
    }
    AlertDialog.Builder(context)
      .setTitle(if (record == null) "新建会话" else "重命名会话")
      .setView(input)
      .setPositiveButton("保存") { _, _ ->
        val title = input.text?.toString().orEmpty()
        scope.launch {
          withContext(Dispatchers.IO) {
            if (record == null) {
              runtime.conversationRepository.createConversation(title)
            } else {
              runtime.conversationRepository.renameConversation(record.id, title)
            }
          }
          onChanged()
        }
      }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun confirmClearConversation(record: ConversationRecord, onChanged: () -> Unit) {
    AlertDialog.Builder(context)
      .setTitle("清空消息")
      .setMessage("确定清空「${record.title}」里的所有消息？")
      .setPositiveButton("清空") { _, _ ->
        scope.launch {
          withContext(Dispatchers.IO) { runtime.conversationRepository.clearMessages(record.id) }
          onChanged()
        }
      }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun confirmDeleteConversation(record: ConversationRecord) {
    AlertDialog.Builder(context)
      .setTitle("删除会话")
      .setMessage("确定删除「${record.title}」？")
      .setPositiveButton("删除") { _, _ ->
        scope.launch {
          withContext(Dispatchers.IO) { runtime.conversationRepository.deleteConversation(record.id) }
          popBackStack()
        }
      }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun conversationRoleTitle(message: ConversationMessageRecord): String {
    return when (message.role) {
      ConversationMessageRole.USER -> "用户"
      ConversationMessageRole.ASSISTANT -> "小美"
      else -> message.role
    }
  }

  private fun showMemoryPage(replace: Boolean = false) {
    val adapter = SettingsListAdapter()
    val view = createPaddedList(adapter)
    adapter.submitList(listOf(SpacerItem(8), DescriptionItem("正在读取记忆..."), SpacerItem(24)))
    pushPage(Page("记忆管理", view), replace)
    renderMemoryPage(adapter)
  }

  private fun renderMemoryPage(adapter: SettingsListAdapter) {
    scope.launch {
      val memories = withContext(Dispatchers.IO) {
        runCatching { runtime.memoryRepository.enabledSnapshot(100) }.getOrDefault(emptyList())
      }
      val items = ArrayList<SettingsListItem>()
      items.add(SpacerItem(8))
      items.add(HeaderItem("操作"))
      items.add(
        TextListItem("新增记忆", "手动写入长期偏好或事实", divider = false) {
          showMemoryEditor(null) { renderMemoryPage(adapter) }
        }
      )
      items.add(HeaderItem("启用记忆"))
      if (memories.isEmpty()) {
        items.add(DescriptionItem("暂无记忆"))
      } else {
        memories.forEachIndexed { index, record ->
          items.add(
            TextListItem(
              title = record.content,
              summary = "${if (record.source == MemorySource.AUTO) "自动" else "手动"} / ${formatTimestamp(record.updatedAt, "MM-dd HH:mm")}",
              hasSwitch = true,
              checked = record.enabled,
              divider = index != memories.lastIndex,
              onClick = { showMemoryActions(record) { renderMemoryPage(adapter) } }
            )
          )
        }
      }
      items.add(SpacerItem(24))
      adapter.submitList(items)
    }
  }

  private fun showCustomCommandPage(replace: Boolean = false) {
    val adapter = SettingsListAdapter()
    val view = createPaddedList(adapter)
    adapter.submitList(listOf(SpacerItem(8), DescriptionItem("正在读取自定义指令..."), SpacerItem(24)))
    pushPage(Page("自定义指令", view), replace)
    renderCustomCommandPage(adapter)
  }

  private fun renderCustomCommandPage(adapter: SettingsListAdapter) {
    scope.launch {
      val config = withContext(Dispatchers.IO) {
        runCatching { runtime.configRepository.snapshot() }.getOrDefault(LlmConfig())
      }
      latestLlmConfig = config
      val items = ArrayList<SettingsListItem>()
      items.add(SpacerItem(8))

      // =========================
      // 自定义指令
      // =========================

      items.add(SpacerItem(16))

      items.add(
        HeaderItem("自定义指令")
      )

      items.add(
        TextListItem(
          title = "启用自定义指令",
          summary = "命中 final ASR 后执行对应指令，不请求 LLM",
          hasSwitch = true,
          checked = config.customCommandEnabled
        ) {
          saveAivsRulesConfig(
            config.copy(
              customCommandEnabled =
                !config.customCommandEnabled
            )
          ) {
            renderCustomCommandPage(adapter)
          }
        }
      )

      items.add(
        TextListItem(
          title = "新增指令",
          summary = "支持关键词包含、关键词等于、正则包含、正则全匹配",
          divider = false
        ) {
          showCustomCommandEditor(null) {
            renderCustomCommandPage(adapter)
          }
        }
      )

      items.add(
        HeaderItem("指令")
      )

      if (config.customCommandRules.isEmpty()) {

        items.add(
          DescriptionItem("暂无自定义指令")
        )

      } else {

        config.customCommandRules.forEachIndexed { index, rule ->

          items.add(
            TextListItem(
              title = rule.name.ifBlank {
                rule.pattern
              },
              summary = customCommandRuleSummary(rule),
              value = if (rule.enabled) {
                "启用"
              } else {
                "停用"
              },
              hasSwitch = true,
              checked = rule.enabled,
              divider =
                index != config.customCommandRules.lastIndex
            ) {
              showCustomCommandActions(rule) {
                renderCustomCommandPage(adapter)
              }
            }
          )
        }
      }

      items.add(
        SpacerItem(24)
      )

      adapter.submitList(items)
    }
  }

  private fun showCustomCommandActions(
    rule: CustomCommandRule,
    onChanged: () -> Unit
  ) {
    val nextEnabled = if (rule.enabled) "停用" else "启用"

    AlertDialog.Builder(context)
      .setTitle("自定义指令")
      .setMessage(
        "${rule.name}\n" +
                "${customCommandRuleSummary(rule)}\n" +
                "匹配规则：${rule.pattern}\n" +
                "执行指令：${rule.command}"
      )
      .setPositiveButton("编辑") { _, _ ->
        showCustomCommandEditor(rule, onChanged)
      }
      .setNeutralButton(nextEnabled) { _, _ ->
        upsertCustomCommand(
          rule.copy(
            enabled = !rule.enabled,
            updatedAt = System.currentTimeMillis()
          ),
          onChanged
        )
      }
      .setNegativeButton("删除") { _, _ ->
        confirmDeleteCustomCommand(rule, onChanged)
      }
      .show()
  }

  private fun upsertCustomCommand(
    rule: CustomCommandRule,
    onChanged: () -> Unit
  ) {
    val rules = latestLlmConfig.customCommandRules.toMutableList()
    val index = rules.indexOfFirst { it.id == rule.id }

    if (index >= 0) {
      rules[index] = rule
    } else {
      rules.add(rule)
    }

    saveAivsRulesConfig(
      latestLlmConfig.copy(customCommandRules = rules),
      onChanged
    )
  }

  private fun confirmDeleteCustomCommand(
    rule: CustomCommandRule,
    onChanged: () -> Unit
  ) {
    AlertDialog.Builder(context)
      .setTitle("删除自定义指令")
      .setMessage(
        "${rule.name}\n${rule.pattern}\n\n执行：${rule.command}"
      )
      .setPositiveButton("删除") { _, _ ->
        saveAivsRulesConfig(
          latestLlmConfig.copy(
            customCommandRules =
              latestLlmConfig.customCommandRules.filterNot { it.id == rule.id }
          ),
          onChanged
        )
      }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun showCustomCommandEditor(
    rule: CustomCommandRule?,
    onChanged: () -> Unit
  ) {
    val nameInput = EditText(context).apply {
      minLines = 1
      setText(rule?.name.orEmpty())
      setSelection(text.length)
      hint = "例如：打开客厅灯"
    }

    val patternInput = EditText(context).apply {
      minLines = 2
      setText(rule?.pattern.orEmpty())
      setSelection(text.length)
      hint = "关键词或正则表达式"
    }

    val commandInput = EditText(context).apply {
      minLines = 3
      setText(rule?.command.orEmpty())
      setSelection(text.length)
      hint = "例如：curl http://127.0.0.1:8080/light"
    }

    val regexCheck = CheckBox(context).apply {
      text = "使用正则表达式"
      isChecked = rule?.regex ?: false
    }

    val containsCheck = CheckBox(context).apply {
      text = "包含匹配"
      isChecked = rule?.contains ?: true
    }

    val enabledCheck = CheckBox(context).apply {
      text = "启用规则"
      isChecked = rule?.enabled ?: true
    }

    val content = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL

      val padding = context.dp(20)

      setPadding(
        padding,
        padding / 2,
        padding,
        0
      )

      addView(nameInput)
      addView(patternInput)
      addView(regexCheck)
      addView(containsCheck)

      addView(commandInput)
      addView(enabledCheck)
    }

    AlertDialog.Builder(context)
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
                context,
                "请输入指令名称",
                Toast.LENGTH_SHORT
              ).show()

              return@setOnClickListener
            }

            if (pattern.isBlank()) {
              Toast.makeText(
                context,
                "请输入匹配规则",
                Toast.LENGTH_SHORT
              ).show()

              return@setOnClickListener
            }

            if (command.isBlank()) {
              Toast.makeText(
                context,
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
                context,
                error,
                Toast.LENGTH_SHORT
              ).show()

              return@setOnClickListener
            }

            val now =
              System.currentTimeMillis()

            upsertCustomCommand(
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
              ),
              onChanged
            )

            dismiss()
          }
        }
      }
      .show()
  }

  private fun customCommandRuleSummary(
    rule: CustomCommandRule
  ): String {
    return buildString {

      append(
        if (rule.regex) {
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
      )

      if (rule.command.isNotBlank()) {
        append(" · ")
        append(
          rule.command
            .replace("\n", " ")
            .take(100)
        )
      }
    }
  }

  private fun showAivsRulesPage(replace: Boolean = false) {
    val adapter = SettingsListAdapter()
    val view = createPaddedList(adapter)
    adapter.submitList(listOf(SpacerItem(8), DescriptionItem("正在读取接管规则..."), SpacerItem(24)))
    pushPage(Page("接管规则", view), replace)
    renderAivsRulesPage(adapter)
  }

  private fun renderAivsRulesPage(adapter: SettingsListAdapter) {
    scope.launch {
      val config = withContext(Dispatchers.IO) {
        runCatching { runtime.configRepository.snapshot() }.getOrDefault(LlmConfig())
      }
      latestLlmConfig = config
      val items = ArrayList<SettingsListItem>()
      items.add(SpacerItem(8))
      items.add(HeaderItem("ASR 黑名单"))
      items.add(
        TextListItem(
          title = "启用黑名单",
          summary = "命中 final ASR 后放行官方 reply，不请求 LLM",
          hasSwitch = true,
          checked = config.asrBlacklistEnabled
        ) {
          saveAivsRulesConfig(config.copy(asrBlacklistEnabled = !config.asrBlacklistEnabled)) {
            renderAivsRulesPage(adapter)
          }
        }
      )
      items.add(
        TextListItem(
          title = "新增规则",
          summary = "支持关键词包含、关键词等于、正则包含、正则全匹配",
          divider = false
        ) {
          showAivsRuleEditor(null) { renderAivsRulesPage(adapter) }
        }
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
              divider = index != config.asrBlacklistRules.lastIndex
            ) {
              showAivsRuleActions(rule) { renderAivsRulesPage(adapter) }
            }
          )
        }
      }
      items.add(SpacerItem(24))
      adapter.submitList(items)
    }
  }

  private fun showTroubleshootPage(replace: Boolean = false) {
    val adapter = SettingsListAdapter()
    val view = createPaddedList(adapter)
    pushPage(Page("故障排查", view), replace)
    renderTroubleshoot(adapter)
  }

  private fun renderTroubleshoot(adapter: SettingsListAdapter, testStatus: String? = null) {
    scope.launch {
      val diagnostics = withContext(Dispatchers.IO) { buildDiagnosticsSafely() }
      adapter.submitList(
        listOf(
          SpacerItem(8),
          HeaderItem("操作"),
          TextListItem("刷新状态", "重新读取本地状态快照") {
            renderTroubleshoot(adapter)
          },
          TextListItem("测试 LLM", "使用当前配置测试模型连通性", testStatus) {
            testLlm(adapter)
          },
          TextListItem("打开运动健康", "回到当前宿主应用") {
            openHostApp()
          },
          HeaderItem("最近状态"),
          TextListItem("最近事件", diagnostics.aivsEvent.ifBlank { "无" }),
          TextListItem("最近回退", diagnostics.fallbackReason.ifBlank { "无" }),
          TextListItem("最近错误", diagnostics.lastError.ifBlank { "无" }),
          TextListItem("记忆维护", memoryStatus(diagnostics), diagnostics.lastMemoryUpdateText, divider = false),
          SpacerItem(24)
        )
      )
    }
  }

  private fun showLlmConfigPage(entrySource: String, replace: Boolean = false) {
    val view = android.view.LayoutInflater.from(context).inflate(R.layout.fragment_llm_config, container, false)
    applyContentPadding(view)
    LlmProviderUi.setup(view)
    pushPage(Page("LLM 配置", view), replace)
    scope.launch {
      val config = withContext(Dispatchers.IO) {
        runCatching { runtime.configRepository.snapshot() }.getOrDefault(LlmConfig())
      }
      latestLlmConfig = config
      bindConfig(view, config)
      bindLlmState(view, "入口：${sourceLabel(entrySource)}")
    }
    view.findViewById<View>(R.id.action_save_config).setOnClickListener {
      val config = readConfigFromInputs(view) ?: return@setOnClickListener
      scope.launch {
        bindLlmState(view, "保存中...")
        val success = saveConfigBlocking(config)
        bindLlmState(view, if (success) "配置已保存" else "配置保存失败")
        Toast.makeText(context, if (success) "配置已保存" else "配置保存失败", Toast.LENGTH_SHORT).show()
      }
    }
    view.findViewById<View>(R.id.action_test_config).setOnClickListener {
      val config = readConfigFromInputs(view) ?: return@setOnClickListener
      scope.launch {
        bindLlmState(view, "保存中...")
        val saved = saveConfigBlocking(config)
        if (!saved) {
          bindLlmState(view, "配置保存失败")
          Toast.makeText(context, "配置保存失败", Toast.LENGTH_SHORT).show()
          return@launch
        }
        bindLlmState(view, "测试中...")
        val result = withContext(Dispatchers.IO) {
          runCatching { runtime.llmClient.probe(config) }
        }
        bindLlmState(
          view,
          result.fold(
            onSuccess = { it.ifBlank { "LLM 连接正常" } },
            onFailure = { "失败：${it.message ?: it.javaClass.simpleName}" }
          )
        )
      }
    }
  }

  private fun pushPage(page: Page, replace: Boolean) {
    if (replace) {
      stack.clear()
    }
    stack.add(page)
    renderTopPage()
  }

  private fun renderTopPage() {
    val page = stack.lastOrNull() ?: return
    container.removeAllViews()
    container.addView(page.view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    titleView.text = page.title
    backButton.visibility = View.VISIBLE
    page.onVisible?.invoke()
  }

  private fun createPaddedList(adapter: SettingsListAdapter): View {
    return createSettingsRecyclerView(context, adapter).also(::applyContentPadding)
  }

  private fun applyContentPadding(view: View) {
    val fallbackTop = context.dp(56)
    view.setPadding(view.paddingLeft, topBar.height.takeIf { it > 0 } ?: fallbackTop, view.paddingRight, view.paddingBottom)
    topBar.post {
      val top = topBar.height.takeIf { it > 0 } ?: fallbackTop
      view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
    }
  }

  private fun buildRootView() {
    rootView.setBackgroundColor(context.getColor(R.color.xiaomei_host_background))
    rootView.addView(
      container,
      FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    )
    val topBarHeight = statusBarHeight() + context.dp(56)
    topBar.setBackgroundColor(context.getColor(R.color.xiaomei_host_background))
    topBar.elevation = context.dp(1).toFloat()
    rootView.addView(
      topBar,
      FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topBarHeight, Gravity.TOP)
    )
    backButton.apply {
      text = "‹"
      applyTopBarTextStyle(gravity = Gravity.CENTER, textSizeSp = 28f)
      visibility = View.VISIBLE
      isClickable = true
      isFocusable = true
    }
    topBar.addView(
      backButton,
      FrameLayout.LayoutParams(context.dp(56), context.dp(56), Gravity.START or Gravity.BOTTOM)
    )
    titleView.apply {
      applyTopBarTextStyle(gravity = Gravity.CENTER_VERTICAL, textSizeSp = 20f)
    }
    topBar.addView(
      titleView,
      FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(56), Gravity.BOTTOM).apply {
        marginStart = context.dp(56)
        marginEnd = context.dp(21)
      }
    )
  }

  private fun TextView.applyTopBarTextStyle(gravity: Int, textSizeSp: Float) {
    textSize = textSizeSp
    this.gravity = gravity
    setTextColor(context.getColor(R.color.firstTextColor))
    includeFontPadding = false
    maxLines = 1
  }

  private fun buildRuntimeItems(diagnostics: SettingsDiagnostics) = listOf(
    SpacerItem(8),
    HeaderItem("宿主"),
    TextListItem("宿主版本", diagnostics.hostPackage, diagnostics.hostVersion),
    TextListItem("入口策略", diagnostics.hostEntry),
    HeaderItem("进程状态"),
    TextListItem("主进程", diagnostics.mainProcessSummary.takeWithEllipsis(80), diagnostics.mainLastHookTimeText),
    TextListItem(":device", diagnostics.deviceProcessSummary.takeWithEllipsis(80), diagnostics.deviceLastHookTimeText),
    TextListItem("忽略进程", diagnostics.ignoredProcessSummary.ifBlank { "无" }),
    HeaderItem("诊断"),
    TextListItem("最近注入时间", diagnostics.lastHookTimeText),
    TextListItem("最近错误", diagnostics.lastError.ifBlank { "无" }, divider = false),
    SpacerItem(24)
  )

  private fun buildAivsItems(diagnostics: SettingsDiagnostics) = listOf(
    SpacerItem(8),
    HeaderItem("AIVS 链路"),
    TextListItem("最近事件", diagnostics.aivsEvent.ifBlank { "无" }, diagnostics.aivsLastUpdatedText),
    TextListItem("最近 Hook", diagnostics.aivsHookPoint.ifBlank { "尚未命中" }),
    TextListItem("Hook 命中", diagnostics.aivsHookHits.ifBlank { "无" }),
    TextListItem("send 指令", diagnostics.aivsLastSendInstruction.ifBlank { "尚未捕获" }),
    TextListItem("Hook 错误", diagnostics.aivsHookError.ifBlank { "无" }),
    TextListItem("Final ASR", diagnostics.finalAsr.ifBlank { "尚未捕获" }),
    HeaderItem("模型与回复"),
    TextListItem("LLM 状态", diagnostics.llmStatus.ifBlank { "未开始" }),
    TextListItem("回答摘要", diagnostics.llmAnswerPreview.ifBlank { "无" }),
    TextListItem("回退原因", diagnostics.fallbackReason.ifBlank { "无" }),
    HeaderItem("提交状态"),
    TextListItem("当前会话", diagnostics.activeConversationTitle, "${diagnostics.activeConversationMessageCount} 条"),
    TextListItem("会话更新", diagnostics.activeConversationUpdatedText),
    TextListItem("Reply 策略", diagnostics.replyPipeline),
    TextListItem("历史落库", diagnostics.lastHistoryPersistText.ifBlank { "未落库" }),
    TextListItem("记忆维护", memoryStatus(diagnostics), diagnostics.lastMemoryUpdateText, divider = false),
    SpacerItem(24)
  )

  private suspend fun buildDiagnosticsSafely(): SettingsDiagnostics {
    return runCatching {
      val runtimeSnapshot = ModuleRuntimeStatusStore.read(diagnosticsContext)
      val aivs = AivsDebugSnapshotStore.read(diagnosticsContext)
      val conversationState = runtime.conversationRepository.stateSnapshot()
      val conversation = conversationState.conversations.firstOrNull { it.id == conversationState.activeConversationId }
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
      val hostVersion = resolveHostVersion(runtimeSnapshot.hostPackage)
      SettingsDiagnostics(
        hostPackage = runtimeSnapshot.hostPackage.ifBlank { HostSettingsNavigation.hostPackageName },
        hostVersion = hostVersion,
        hookStatus = if (runtimeSnapshot.isActivated) "已进入 modern 101 主链" else "等待注入回执",
        hostEntry = "Mine RN + AboutActivity",
        aivsStatus = when {
          aivs.replacementConsumed -> "AIVS reply 已替换"
          finalAsr.isNotBlank() -> "已读取当前会话最近语音上下文"
          else -> "等待语音链路验证"
        },
        mainProcessSummary = formatProcessStatus(runtimeSnapshot.mainProcess, "主进程未命中"),
        deviceProcessSummary = formatProcessStatus(runtimeSnapshot.deviceProcess, "设备进程未命中"),
        ignoredProcessSummary = formatProcessStatus(runtimeSnapshot.ignoredProcess, ""),
        mainLastHookTimeText = formatTimestamp(runtimeSnapshot.mainProcess.lastHookTimeMillis),
        deviceLastHookTimeText = formatTimestamp(runtimeSnapshot.deviceProcess.lastHookTimeMillis),
        aivsEvent = aivsEvent,
        finalAsr = finalAsr,
        llmStatus = describeLlmStatus(aivs),
        llmAnswerPreview = llmAnswerPreview,
        fallbackReason = aivs.lastFallbackReason,
        lastHistoryPersistText = formatTimestamp(historyPersistTime),
        aivsLastUpdatedText = formatTimestamp(aivs.lastUpdatedTimeMillis),
        lastError = listOf(runtimeSnapshot.deviceProcess.message, runtimeSnapshot.mainProcess.message, aivs.lastError)
          .firstOrNull { it.isNotBlank() }
          .orEmpty(),
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
            runtimeSnapshot.mainProcess.lastHookTimeMillis,
            runtimeSnapshot.deviceProcess.lastHookTimeMillis,
            runtimeSnapshot.ignoredProcess.lastHookTimeMillis
          )
        )
      )
    }.getOrElse {
      SettingsDiagnostics(lastError = it.message ?: it.javaClass.simpleName)
    }
  }

  private suspend fun saveConfigBlocking(config: LlmConfig): Boolean {
    Log.w("config", "saveConfigBlocking LlmConfig: $config")
    return withContext(NonCancellable + Dispatchers.IO) {
      runCatching {
        runtime.configRepository.save(config)
      }.isSuccess
    }
  }

  private fun bindConfig(root: View, config: LlmConfig) {
    latestLlmConfig = config
    root.findViewById<EditText>(R.id.input_base_url).setText(config.baseUrl)
    root.findViewById<EditText>(R.id.input_api_key).setText(config.apiKey)
    root.findViewById<EditText>(R.id.input_model).setText(config.model)
    LlmProviderUi.bind(root, config)
    root.findViewById<EditText>(R.id.input_system_prompt).setText(config.systemPrompt)
    root.findViewById<EditText>(R.id.input_temperature).setText(config.temperature.toString())
    root.findViewById<EditText>(R.id.input_max_tokens).setText(config.maxTokens.toString())
  }

  private fun bindLlmState(root: View, value: String) {
    root.findViewById<TextView>(R.id.text_runtime_state).text = value
  }

  private fun readConfigFromInputs(root: View): LlmConfig? {
    val temperature = root.findViewById<EditText>(R.id.input_temperature).text.toString().trim().toFloatOrNull()
    val maxTokens = root.findViewById<EditText>(R.id.input_max_tokens).text.toString().trim().toIntOrNull()
    if (temperature == null || maxTokens == null) {
      Toast.makeText(context, "Temperature 或 Max Tokens 格式无效", Toast.LENGTH_SHORT).show()
      return null
    }
    return latestLlmConfig.copy(
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

  private fun showAivsRuleActions(rule: AivsAsrBlacklistRule, onChanged: () -> Unit) {
    val nextEnabled = if (rule.enabled) "停用" else "启用"
    AlertDialog.Builder(context)
      .setTitle("ASR 黑名单规则")
      .setMessage("${ruleMode(rule)}\n${rule.pattern}")
      .setPositiveButton("编辑") { _, _ -> showAivsRuleEditor(rule, onChanged) }
      .setNeutralButton(nextEnabled) { _, _ ->
        upsertAivsRule(rule.copy(enabled = !rule.enabled, updatedAt = System.currentTimeMillis()), onChanged)
      }
      .setNegativeButton("删除") { _, _ -> confirmDeleteAivsRule(rule, onChanged) }
      .show()
  }

  private fun showAivsRuleEditor(rule: AivsAsrBlacklistRule?, onChanged: () -> Unit) {
    val patternInput = EditText(context).apply {
      minLines = 2
      setText(rule?.pattern.orEmpty())
      setSelection(text.length)
      hint = "关键词或正则表达式"
    }
    val regexCheck = CheckBox(context).apply {
      text = "使用正则表达式"
      isChecked = rule?.regex ?: false
    }
    val containsCheck = CheckBox(context).apply {
      text = "包含匹配"
      isChecked = rule?.contains ?: true
    }
    val enabledCheck = CheckBox(context).apply {
      text = "启用规则"
      isChecked = rule?.enabled ?: true
    }
    val content = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      val padding = context.dp(20)
      setPadding(padding, padding / 2, padding, 0)
      addView(patternInput)
      addView(regexCheck)
      addView(containsCheck)
      addView(enabledCheck)
    }
    AlertDialog.Builder(context)
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
              Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
              return@setOnClickListener
            }
            val now = System.currentTimeMillis()
            upsertAivsRule(
              AivsAsrBlacklistRule(
                id = rule?.id ?: UUID.randomUUID().toString(),
                pattern = pattern.trim(),
                enabled = enabledCheck.isChecked,
                regex = regexCheck.isChecked,
                contains = containsCheck.isChecked,
                createdAt = rule?.createdAt ?: now,
                updatedAt = now
              ),
              onChanged
            )
            dismiss()
          }
        }
      }
      .show()
  }

  private fun upsertAivsRule(rule: AivsAsrBlacklistRule, onChanged: () -> Unit) {
    val rules = latestLlmConfig.asrBlacklistRules.toMutableList()
    val index = rules.indexOfFirst { it.id == rule.id }
    if (index >= 0) {
      rules[index] = rule
    } else {
      rules.add(rule)
    }
    saveAivsRulesConfig(latestLlmConfig.copy(asrBlacklistRules = rules), onChanged)
  }

  private fun confirmDeleteAivsRule(rule: AivsAsrBlacklistRule, onChanged: () -> Unit) {
    AlertDialog.Builder(context)
      .setTitle("删除规则")
      .setMessage(rule.pattern)
      .setPositiveButton("删除") { _, _ ->
        saveAivsRulesConfig(
          latestLlmConfig.copy(asrBlacklistRules = latestLlmConfig.asrBlacklistRules.filterNot { it.id == rule.id }),
          onChanged
        )
      }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun saveAivsRulesConfig(config: LlmConfig, onChanged: () -> Unit) {
    latestLlmConfig = config
    scope.launch {
      val success = saveConfigBlocking(config)
      Toast.makeText(context, if (success) "规则已保存" else "规则保存失败", Toast.LENGTH_SHORT).show()
      onChanged()
    }
  }

  private fun ruleMode(rule: AivsAsrBlacklistRule): String {
    return if (rule.regex) {
      if (rule.contains) "正则包含" else "正则全匹配"
    } else {
      if (rule.contains) "关键词包含" else "关键词等于"
    }
  }

  private fun showMemoryActions(record: MemoryRecord, onChanged: () -> Unit) {
    AlertDialog.Builder(context)
      .setTitle("记忆")
      .setMessage(record.content)
      .setPositiveButton("编辑") { _, _ -> showMemoryEditor(record, onChanged) }
      .setNeutralButton("停用") { _, _ ->
        scope.launch {
          withContext(Dispatchers.IO) {
            runtime.memoryRepository.upsert(record.copy(enabled = false, updatedAt = System.currentTimeMillis()))
          }
          onChanged()
        }
      }
      .setNegativeButton("删除") { _, _ ->
        scope.launch {
          withContext(Dispatchers.IO) {
            runtime.memoryRepository.applyToolActions(
              listOf(MemoryToolAction(MemoryToolActionType.DELETE, id = record.id)),
              record.sourceSessionId
            )
          }
          onChanged()
        }
      }
      .show()
  }

  private fun showMemoryEditor(record: MemoryRecord?, onChanged: () -> Unit) {
    val input = EditText(context).apply {
      minLines = 3
      setText(record?.content.orEmpty())
      setSelection(text.length)
    }
    AlertDialog.Builder(context)
      .setTitle(if (record == null) "新增记忆" else "编辑记忆")
      .setView(input)
      .setPositiveButton("保存") { _, _ ->
        val content = input.text?.toString().orEmpty().trim()
        if (content.isBlank()) return@setPositiveButton
        val now = System.currentTimeMillis()
        val updated = record?.copy(content = content, source = MemorySource.MANUAL, updatedAt = now)
          ?: MemoryRecord(
            id = UUID.randomUUID().toString(),
            content = content,
            enabled = true,
            source = MemorySource.MANUAL,
            createdAt = now,
            updatedAt = now
          )
        scope.launch {
          withContext(Dispatchers.IO) {
            runtime.memoryRepository.upsert(updated)
          }
          onChanged()
        }
      }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun testLlm(adapter: SettingsListAdapter) {
    renderTroubleshoot(adapter, "测试中")
    scope.launch {
      val result = withContext(Dispatchers.IO) {
        runCatching {
          val config = runtime.configRepository.snapshot()
          runtime.llmClient.probe(config)
        }
      }
      renderTroubleshoot(adapter, result.fold({ it.ifBlank { "正常" } }, { "失败" }))
    }
  }

  private fun openHostApp() {
    val launchIntent = hostActivity.packageManager.getLaunchIntentForPackage(HostSettingsNavigation.hostPackageName)
    if (launchIntent != null) {
      hostActivity.startActivity(launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
  }

  private fun memoryStatus(diagnostics: SettingsDiagnostics): String {
    return when (diagnostics.memoryState) {
      "started" -> "进行中"
      "success" -> "完成 ${diagnostics.memoryActionCount} 项"
      "skipped" -> "无变更"
      "failed" -> diagnostics.memoryError.ifBlank { "失败" }
      else -> "未开始"
    }
  }

  private fun formatProcessStatus(status: ModuleProcessStatus, emptyLabel: String): String {
    if (!status.hasSignal) return emptyLabel
    val process = status.processName.ifBlank { "未知进程" }
    val message = status.message.ifBlank { "无附加消息" }
    return "$process / hooks=${status.installedHooks} / $message"
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

  private fun resolveHostVersion(packageName: String): String {
    return runCatching {
      val info = if (android.os.Build.VERSION.SDK_INT >= 33) {
        hostActivity.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
      } else {
        @Suppress("DEPRECATION")
        hostActivity.packageManager.getPackageInfo(packageName, 0)
      }
      "${info.versionName} (${info.longVersionCode})"
    }.getOrDefault("未知")
  }

  private fun sourceLabel(source: String): String = when (source) {
    HostSettingsNavigation.sourceHostHookMine -> "我的页"
    HostSettingsNavigation.sourceHostHookAbout -> "关于页"
    HostSettingsNavigation.sourceLsposed -> "LSPosed"
    HostSettingsNavigation.sourceModuleApp -> "模块 App"
    else -> source
  }

  private fun formatTimestamp(value: Long, pattern: String = "yyyy-MM-dd HH:mm:ss"): String {
    if (value <= 0L) return "从未"
    return DateFormat.format(pattern, Date(value)).toString()
  }

  private fun statusBarHeight(): Int {
    val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
  }

  private fun String.takeWithEllipsis(maxLength: Int): String {
    return if (length <= maxLength) this else take(maxLength) + "..."
  }

  private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

  private companion object {
    const val STATUS_REFRESH_INTERVAL_MS = 1_000L
  }

  private data class Page(
    val title: String,
    val view: View,
    val onVisible: (() -> Unit)? = null
  )
}

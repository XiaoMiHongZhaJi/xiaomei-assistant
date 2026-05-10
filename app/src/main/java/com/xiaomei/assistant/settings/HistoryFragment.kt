package com.xiaomei.assistant.settings

import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.xiaomei.assistant.R
import com.xiaomei.assistant.host.HostSettingsNavigation
import com.xiaomei.assistant.model.ConversationMessageRecord
import com.xiaomei.assistant.model.ConversationMessageRole
import com.xiaomei.assistant.model.ConversationRecord
import com.xiaomei.assistant.model.ConversationState
import com.xiaomei.assistant.settings.dsl.DescriptionItem
import com.xiaomei.assistant.settings.dsl.HeaderItem
import com.xiaomei.assistant.settings.dsl.SettingsListAdapter
import com.xiaomei.assistant.settings.dsl.SettingsListItem
import com.xiaomei.assistant.settings.dsl.SpacerItem
import com.xiaomei.assistant.settings.dsl.TextListItem
import com.xiaomei.assistant.settings.dsl.createSettingsRecyclerView
import java.util.Date
import kotlinx.coroutines.launch

class HistoryFragment : BaseRootLayoutFragment() {
  private val viewModel by activityViewModels<SettingsViewModel> {
    SettingsViewModel.factory(requireContext())
  }
  private val listAdapter = SettingsListAdapter()
  private var detailConversationId: String? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    titleText = getString(R.string.settings_history_title)
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
        viewModel.uiState.collect { render(it.conversationState) }
      }
    }
  }

  private fun render(state: ConversationState) {
    val detailId = detailConversationId
    if (detailId == null) {
      renderConversationList(state)
    } else {
      renderDetail(state, detailId)
    }
  }

  private fun renderConversationList(state: ConversationState) {
    val items = ArrayList<SettingsListItem>()
    items.add(SpacerItem(8))
    items.add(HeaderItem("操作"))
    items.add(
      TextListItem(
        title = "新建会话",
        summary = "创建新的短期上下文，不自动切换",
        divider = false,
        onClick = { showCreateDialog() }
      )
    )
    items.add(HeaderItem("会话"))
    if (state.conversations.isEmpty()) {
      items.add(DescriptionItem("暂无会话。新建后，AIVS 只会读取当前会话的短期上下文。"))
    } else {
      state.conversations.forEachIndexed { index, conversation ->
        val isActive = conversation.id == state.activeConversationId
        items.add(
          TextListItem(
            title = if (isActive) "当前 · ${conversation.title}" else conversation.title,
            summary = conversationSummary(conversation),
            divider = index != state.conversations.lastIndex,
            onClick = {
              detailConversationId = conversation.id
              renderDetail(viewModel.uiState.value.conversationState, conversation.id)
            }
          )
        )
      }
    }
    items.add(SpacerItem(24))
    listAdapter.submitList(items)
  }

  private fun renderDetail(state: ConversationState, conversationId: String) {
    val conversation = state.conversations.firstOrNull { it.id == conversationId }
    if (conversation == null) {
      detailConversationId = null
      renderConversationList(state)
      return
    }
    val messages = state.messages
      .filter { it.conversationId == conversationId }
      .sortedBy { it.createdAt }
    val items = ArrayList<SettingsListItem>()
    items.add(SpacerItem(8))
    items.add(HeaderItem(conversation.title))
    items.add(
      TextListItem(
        title = "切换为当前会话",
        summary = if (conversation.id == state.activeConversationId) "已是当前会话" else "后续 AIVS 将使用这个会话的上下文",
        onClick = { viewModel.selectConversation(conversation.id) }
      )
    )
    items.add(
      TextListItem(
        title = "重命名",
        summary = "修改会话标题",
        onClick = { showRenameDialog(conversation) }
      )
    )
    items.add(
      TextListItem(
        title = "清空消息",
        summary = "保留会话，但移除短期上下文",
        onClick = { confirmClear(conversation) }
      )
    )
    items.add(
      TextListItem(
        title = "删除会话",
        summary = "删除标题和所有消息",
        divider = false,
        onClick = { confirmDelete(conversation) }
      )
    )
    items.add(HeaderItem("消息"))
    if (messages.isEmpty()) {
      items.add(DescriptionItem("暂无消息"))
    } else {
      messages.forEachIndexed { index, message ->
        items.add(
          TextListItem(
            title = roleTitle(message),
            summary = message.content.takeWithEllipsis(160),
            value = formatTimestamp(message.createdAt),
            divider = index != messages.lastIndex
          )
        )
      }
    }
    items.add(SpacerItem(24))
    listAdapter.submitList(items)
  }

  private fun showCreateDialog() {
    val input = EditText(requireContext()).apply {
      hint = "会话标题"
    }
    AlertDialog.Builder(requireContext())
      .setTitle("新建会话")
      .setView(input)
      .setPositiveButton("创建") { _, _ ->
        viewModel.createConversation(input.text?.toString().orEmpty())
      }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun showRenameDialog(conversation: ConversationRecord) {
    val input = EditText(requireContext()).apply {
      setText(conversation.title)
      setSelection(text.length)
    }
    AlertDialog.Builder(requireContext())
      .setTitle("重命名会话")
      .setView(input)
      .setPositiveButton("保存") { _, _ ->
        viewModel.renameConversation(conversation.id, input.text?.toString().orEmpty())
      }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun confirmClear(conversation: ConversationRecord) {
    AlertDialog.Builder(requireContext())
      .setTitle("清空消息")
      .setMessage("确定清空「${conversation.title}」里的所有消息？")
      .setPositiveButton("清空") { _, _ -> viewModel.clearConversationMessages(conversation.id) }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun confirmDelete(conversation: ConversationRecord) {
    AlertDialog.Builder(requireContext())
      .setTitle("删除会话")
      .setMessage("确定删除「${conversation.title}」？")
      .setPositiveButton("删除") { _, _ ->
        if (detailConversationId == conversation.id) detailConversationId = null
        viewModel.deleteConversation(conversation.id)
      }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun conversationSummary(conversation: ConversationRecord): String {
    val preview = conversation.lastMessagePreview.ifBlank { "暂无消息" }
    return "${conversation.messageCount} 条 / ${formatTimestamp(conversation.updatedAt)} / $preview"
  }

  private fun roleTitle(message: ConversationMessageRecord): String {
    return when (message.role) {
      ConversationMessageRole.USER -> "用户"
      ConversationMessageRole.ASSISTANT -> "小美"
      else -> message.role
    }
  }

  private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "从未"
    return DateFormat.format("MM-dd HH:mm", Date(timestamp)).toString()
  }

  private fun String.takeWithEllipsis(maxLength: Int): String {
    return if (length <= maxLength) this else take(maxLength - 3) + "..."
  }

  companion object {
    private const val ARG_ENTRY_SOURCE = "entry_source"

    fun newInstance(entrySource: String): HistoryFragment {
      return HistoryFragment().apply {
        arguments = Bundle().apply {
          putString(ARG_ENTRY_SOURCE, entrySource.ifBlank { HostSettingsNavigation.sourceModuleApp })
        }
      }
    }
  }
}

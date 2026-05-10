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
import com.xiaomei.assistant.host.HostSettingsNavigation
import com.xiaomei.assistant.model.MemoryRecord
import com.xiaomei.assistant.model.MemorySource
import com.xiaomei.assistant.settings.dsl.DescriptionItem
import com.xiaomei.assistant.settings.dsl.HeaderItem
import com.xiaomei.assistant.settings.dsl.SettingsListAdapter
import com.xiaomei.assistant.settings.dsl.SettingsListItem
import com.xiaomei.assistant.settings.dsl.SpacerItem
import com.xiaomei.assistant.settings.dsl.TextListItem
import com.xiaomei.assistant.settings.dsl.createSettingsRecyclerView
import kotlinx.coroutines.launch
import java.util.Date

class MemoryFragment : BaseRootLayoutFragment() {
  private val viewModel by activityViewModels<SettingsViewModel> {
    SettingsViewModel.factory(requireContext())
  }
  private val listAdapter = SettingsListAdapter()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    titleText = "记忆管理"
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
        viewModel.uiState.collect { render(it.memories) }
      }
    }
  }

  private fun render(memories: List<MemoryRecord>) {
    val items = ArrayList<SettingsListItem>()
    items.add(SpacerItem(8))
    items.add(HeaderItem("操作"))
    items.add(
      TextListItem(
        title = "新增记忆",
        summary = "手动写入长期偏好或事实",
        divider = false,
        onClick = { showEditDialog(null) }
      )
    )
    items.add(HeaderItem("记忆"))
    if (memories.isEmpty()) {
      items.add(DescriptionItem("暂无记忆"))
    } else {
      memories.forEachIndexed { index, record ->
        items.add(
          TextListItem(
            title = record.content,
            summary = memorySummary(record),
            value = if (record.enabled) "启用" else "停用",
            hasSwitch = true,
            checked = record.enabled,
            divider = index != memories.lastIndex,
            onClick = { showMemoryActions(record) }
          )
        )
      }
    }
    items.add(SpacerItem(24))
    listAdapter.submitList(items)
  }

  private fun showMemoryActions(record: MemoryRecord) {
    val nextEnabled = if (record.enabled) "停用" else "启用"
    AlertDialog.Builder(requireContext())
      .setTitle("记忆")
      .setMessage(record.content)
      .setPositiveButton("编辑") { _, _ -> showEditDialog(record) }
      .setNeutralButton(nextEnabled) { _, _ -> viewModel.setMemoryEnabled(record.id, !record.enabled) }
      .setNegativeButton("删除") { _, _ -> confirmDelete(record) }
      .show()
  }

  private fun showEditDialog(record: MemoryRecord?) {
    val input = EditText(requireContext()).apply {
      minLines = 3
      setText(record?.content.orEmpty())
      setSelection(text.length)
    }
    AlertDialog.Builder(requireContext())
      .setTitle(if (record == null) "新增记忆" else "编辑记忆")
      .setView(input)
      .setPositiveButton("保存") { _, _ ->
        val content = input.text?.toString().orEmpty()
        if (record == null) {
          viewModel.addMemory(content)
        } else {
          viewModel.updateMemory(record.id, content)
        }
      }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun confirmDelete(record: MemoryRecord) {
    AlertDialog.Builder(requireContext())
      .setTitle("删除记忆")
      .setMessage(record.content)
      .setPositiveButton("删除") { _, _ -> viewModel.deleteMemory(record.id) }
      .setNegativeButton("取消", null)
      .show()
  }

  private fun memorySummary(record: MemoryRecord): String {
    val source = if (record.source == MemorySource.AUTO) "自动" else "手动"
    return "$source / ${formatTimestamp(record.updatedAt)}"
  }

  private fun formatTimestamp(timestamp: Long): String {
    if (timestamp <= 0L) return "从未"
    return DateFormat.format("MM-dd HH:mm", Date(timestamp)).toString()
  }

  companion object {
    private const val ARG_ENTRY_SOURCE = "entry_source"

    fun newInstance(entrySource: String): MemoryFragment {
      return MemoryFragment().apply {
        arguments = Bundle().apply {
          putString(ARG_ENTRY_SOURCE, entrySource.ifBlank { HostSettingsNavigation.sourceModuleApp })
        }
      }
    }
  }
}

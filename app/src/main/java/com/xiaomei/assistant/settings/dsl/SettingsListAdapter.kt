package com.xiaomei.assistant.settings.dsl

import android.view.View
import androidx.recyclerview.widget.RecyclerView

sealed interface SettingsListItem {
  val viewType: Int
}

data class TextListItem(
  val title: String,
  val summary: String? = null,
  val value: String? = null,
  val enabled: Boolean = true,
  val hasSwitch: Boolean = false,
  val checked: Boolean = false,
  val divider: Boolean = true,
  val onClick: ((View) -> Unit)? = null
) : SettingsListItem {
  override val viewType: Int = TYPE_TEXT
}

data class HeaderItem(val title: String) : SettingsListItem {
  override val viewType: Int = TYPE_HEADER
}

data class DescriptionItem(val text: String) : SettingsListItem {
  override val viewType: Int = TYPE_DESCRIPTION
}

data class SpacerItem(val heightDp: Int = 12) : SettingsListItem {
  override val viewType: Int = TYPE_SPACER
}

class SettingsListAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
  private val items = ArrayList<SettingsListItem>()

  fun submitList(newItems: List<SettingsListItem>) {
    items.clear()
    items.addAll(newItems)
    notifyDataSetChanged()
  }

  override fun getItemCount(): Int = items.size

  override fun getItemViewType(position: Int): Int = items[position].viewType

  override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    val context = parent.context
    return when (viewType) {
      TYPE_TEXT -> TextHolder(TitleValueCell(context))
      TYPE_HEADER -> HeaderHolder(HeaderCell(context))
      TYPE_DESCRIPTION -> DescriptionHolder(TextInfoCell(context))
      TYPE_SPACER -> SpacerHolder(SpacerCell(context))
      else -> error("unknown view type: $viewType")
    }
  }

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    when (val item = items[position]) {
      is TextListItem -> (holder.itemView as TitleValueCell).apply {
        bind(item.title, item.summary, item.value, item.hasSwitch, item.checked, item.divider)
        isEnabled = item.enabled
        alpha = if (item.enabled) 1f else 0.45f
        setOnClickListener(if (item.enabled && item.onClick != null) View.OnClickListener(item.onClick) else null)
      }
      is HeaderItem -> (holder.itemView as HeaderCell).bind(item.title)
      is DescriptionItem -> (holder.itemView as TextInfoCell).bind(item.text)
      is SpacerItem -> (holder.itemView as SpacerCell).spacerHeight = holder.itemView.context.dp(item.heightDp)
    }
  }

  override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
    super.onViewRecycled(holder)
    holder.itemView.setOnClickListener(null)
  }

  class TextHolder(view: TitleValueCell) : RecyclerView.ViewHolder(view)
  class HeaderHolder(view: HeaderCell) : RecyclerView.ViewHolder(view)
  class DescriptionHolder(view: TextInfoCell) : RecyclerView.ViewHolder(view)
  class SpacerHolder(view: SpacerCell) : RecyclerView.ViewHolder(view)
}

const val TYPE_TEXT = 1
const val TYPE_HEADER = 2
const val TYPE_DESCRIPTION = 3
const val TYPE_SPACER = 4

package com.xiaomei.assistant.settings.dsl

import android.content.Context
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.xiaomei.assistant.R

fun createSettingsRecyclerView(context: Context, adapter: SettingsListAdapter): RecyclerView {
  return RecyclerView(context).apply {
    layoutManager = LinearLayoutManager(context)
    this.adapter = adapter
    setHasFixedSize(false)
    clipToPadding = false
    overScrollMode = RecyclerView.OVER_SCROLL_IF_CONTENT_SCROLLS
    setBackgroundColor(context.getColor(R.color.xiaomei_host_background))
  }
}

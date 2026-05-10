package com.xiaomei.assistant.settings

import android.view.ViewGroup

abstract class BaseRootLayoutFragment : BaseSettingFragment() {
  protected var rootLayoutView: ViewGroup? = null

  override fun onResume() {
    super.onResume()
    applyPadding()
  }

  protected fun applyRootLayoutPaddingFor(viewGroup: ViewGroup) {
    viewGroup.clipToPadding = false
    viewGroup.setPadding(
      viewGroup.paddingLeft,
      layoutPaddingTop(),
      viewGroup.paddingRight,
      layoutPaddingBottom()
    )
  }

  private fun applyPadding() {
    rootLayoutView?.let(::applyRootLayoutPaddingFor)
  }

  override fun notifyLayoutPaddingsChanged() {
    applyPadding()
  }

  override fun onDestroyView() {
    super.onDestroyView()
    rootLayoutView = null
  }
}

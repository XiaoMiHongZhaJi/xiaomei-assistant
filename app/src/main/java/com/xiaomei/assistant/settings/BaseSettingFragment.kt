package com.xiaomei.assistant.settings

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment

abstract class BaseSettingFragment : Fragment() {
  private var settingsHostActivity: SettingsHostControllerOwner? = null
  private var currentTitle: String? = null
  private var currentSubtitle: String? = null

  override fun onAttach(context: Context) {
    super.onAttach(context)
    settingsHostActivity = SettingsHostOwnerRegistry.resolve(parentFragmentManager)
      ?: requireActivity() as? SettingsHostControllerOwner
      ?: error("Settings host owner is null: ${requireActivity().javaClass.name}")
  }

  override fun onDetach() {
    super.onDetach()
    settingsHostActivity = null
  }

  protected fun requireSettingsHostActivity(): SettingsHostControllerOwner {
    return settingsHostActivity ?: error("Settings host activity is null")
  }

  fun finishFragment() {
    requireSettingsHostActivity().finishFragment(this)
  }

  var titleText: String?
    get() = currentTitle
    protected set(value) {
      currentTitle = value
      settingsHostActivity?.requestInvalidateActionBar()
    }

  var subtitleText: String?
    get() = currentSubtitle
    protected set(value) {
      currentSubtitle = value
      settingsHostActivity?.requestInvalidateActionBar()
    }

  open fun notifyLayoutPaddingsChanged() {
  }

  open fun isWrapContent(): Boolean = true

  open fun doOnCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View? {
    return super.onCreateView(inflater, container, savedInstanceState)
  }

  final override fun onCreateView(
    inflater: LayoutInflater,
    container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View? {
    return doOnCreateView(inflater, container, savedInstanceState)
  }

  open fun layoutPaddingTop(): Int = requireSettingsHostActivity().layoutPaddingTop
  open fun layoutPaddingBottom(): Int = requireSettingsHostActivity().layoutPaddingBottom
}


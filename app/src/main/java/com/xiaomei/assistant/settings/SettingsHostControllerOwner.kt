package com.xiaomei.assistant.settings

interface SettingsHostControllerOwner {
  val layoutPaddingTop: Int
  val layoutPaddingBottom: Int

  fun presentFragment(fragment: BaseSettingFragment)

  fun finishFragment(fragment: BaseSettingFragment)

  fun requestInvalidateActionBar()
}

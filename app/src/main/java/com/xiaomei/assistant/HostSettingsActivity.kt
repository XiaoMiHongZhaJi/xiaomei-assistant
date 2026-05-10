package com.xiaomei.assistant

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.xiaomei.assistant.host.SettingsLaunchHelper
import com.xiaomei.assistant.uihost.BaseSettingFragment

class HostSettingsActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState == null) {
      startActivity(SettingsLaunchHelper.createHostSettingsContainerIntent(this, intent.extras))
    }
    finish()
  }

  fun presentFragment(
    fragment: BaseSettingFragment,
    addToBackStack: Boolean = true
  ) {
    startActivity(SettingsLaunchHelper.createHostSettingsContainerIntent(this, intent.extras))
    finish()
  }

  fun finishFragment(fragment: BaseSettingFragment) {
    finish()
  }

  fun requestInvalidateActionBar() {
  }
}

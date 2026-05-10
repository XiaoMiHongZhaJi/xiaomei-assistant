package com.xiaomei.assistant.settings

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import com.xiaomei.assistant.R
import com.xiaomei.assistant.host.HostSettingsNavigation

class SettingsUiFragmentHostActivity : AppCompatTransferActivity(), SettingsHostControllerOwner {
  private lateinit var controller: SettingsUiHostController
  private val backPressedCallback = object : OnBackPressedCallback(false) {
    override fun handleOnBackPressed() {
      controller.popCurrentFragment()
    }
  }

  override val layoutPaddingTop: Int
    get() = controller.layoutPaddingTop

  override val layoutPaddingBottom: Int
    get() = controller.layoutPaddingBottom

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_settings_ui_host)
    setSupportActionBar(findViewById(R.id.topAppBar))
    requestTranslucentStatusBar()
    onBackPressedDispatcher.addCallback(this, backPressedCallback)
    controller = createSettingsUiHostController(
      rootView = findViewById(R.id.main_settings_root_frame),
      finishHost = { finish() },
      setBackEnabled = { backPressedCallback.isEnabled = it }
    )
    controller.attach(
      savedInstanceState = savedInstanceState,
      entrySource = intent.getStringExtra(HostSettingsNavigation.extraEntrySource) ?: HostSettingsNavigation.sourceModuleApp,
      entryTarget = intent.getStringExtra(HostSettingsNavigation.extraEntryTarget)
    )
  }

  override fun presentFragment(fragment: BaseSettingFragment) {
    controller.presentFragment(fragment)
  }

  override fun finishFragment(fragment: BaseSettingFragment) {
    controller.finishFragment(fragment)
  }

  override fun requestInvalidateActionBar() {
    if (::controller.isInitialized) {
      controller.requestInvalidateActionBar()
    }
  }

  override fun onSupportNavigateUp(): Boolean {
    onBackPressedDispatcher.onBackPressed()
    return true
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (::controller.isInitialized) {
      controller.onAttachedToWindow()
    }
  }
}

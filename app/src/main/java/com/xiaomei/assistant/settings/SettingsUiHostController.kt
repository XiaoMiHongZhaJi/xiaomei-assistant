package com.xiaomei.assistant.settings

import android.content.Context
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.ComponentDialog
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.FragmentManager
import cc.ioctl.util.ui.fling.SimpleFlingInterceptLayout
import com.google.android.material.appbar.AppBarLayout
import com.xiaomei.assistant.R
import com.xiaomei.assistant.host.HostSettingsNavigation
import kotlin.math.max

class SettingsUiHostController(
  private val context: Context,
  private val fragmentManager: FragmentManager,
  private val rootView: View,
  private val appBarLayout: AppBarLayout,
  private val appToolbar: Toolbar,
  private val fragmentContainer: SimpleFlingInterceptLayout,
  private val finishHost: () -> Unit,
  private val setBackEnabled: (Boolean) -> Unit,
  private val statusBarInsetProvider: () -> Int,
  private val navigationBarInsetProvider: () -> Int,
  private val setHomeAsUp: (Boolean) -> Unit,
  private val setTitle: (String) -> Unit,
  private val setSubtitle: (String?) -> Unit
) : SettingsHostControllerOwner, SimpleFlingInterceptLayout.SimpleOnFlingHandler {
  private val fragmentStack = ArrayList<BaseSettingFragment>(4)
  private var topVisibleFragment: BaseSettingFragment? = null
  private var appBarHeight: Int = 0

  override val layoutPaddingTop: Int
    get() = max(appBarHeight, statusBarInsetProvider())

  override val layoutPaddingBottom: Int
    get() = navigationBarInsetProvider()

  fun attach(savedInstanceState: Bundle?, entrySource: String, entryTarget: String?) {
    SettingsHostOwnerRegistry.register(fragmentManager, this)
    fragmentContainer.onFlingHandler = this
    appBarLayout.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
      appBarHeight = bottom - top
      fragmentStack.forEach { it.notifyLayoutPaddingsChanged() }
    }
    if (savedInstanceState == null) {
      presentFragment(createStartFragment(entrySource, entryTarget))
    } else {
      restoreFragmentState()
    }
  }

  private fun createStartFragment(entrySource: String, entryTarget: String?): BaseSettingFragment {
    return when (entryTarget) {
      HostSettingsNavigation.targetLlmConfig -> LlmConfigFragment.newInstance(entrySource)
      HostSettingsNavigation.targetAivsRules -> AivsRulesFragment.newInstance(entrySource)
      HostSettingsNavigation.targetCustomCommand -> CustomCommandFragment.newInstance(entrySource)
      HostSettingsNavigation.targetRuntimeStatus -> StatusFragment.newInstance(entrySource, HostSettingsNavigation.targetRuntimeStatus)
      HostSettingsNavigation.targetAivsStatus -> StatusFragment.newInstance(entrySource, HostSettingsNavigation.targetAivsStatus)
      HostSettingsNavigation.targetSessionHistory -> HistoryFragment.newInstance(entrySource)
      HostSettingsNavigation.targetTroubleshoot -> TroubleshootFragment.newInstance(entrySource)
      else -> SettingsMainFragment.newInstance(entrySource)
    }
  }

  override fun presentFragment(fragment: BaseSettingFragment) {
    if (fragmentStack.isEmpty()) {
      fragmentManager.beginTransaction()
        .add(R.id.fragment_container, fragment)
        .commit()
    } else {
      fragmentManager.beginTransaction()
        .setCustomAnimations(R.anim.enter_from_right, R.anim.exit_to_left, R.anim.enter_from_left, R.anim.exit_to_right)
        .hide(topVisibleFragment!!)
        .add(R.id.fragment_container, fragment)
        .commit()
    }
    fragmentStack.add(fragment)
    topVisibleFragment = fragment
    requestInvalidateActionBar()
    updateBackState()
  }

  override fun finishFragment(fragment: BaseSettingFragment) {
    if (fragment != topVisibleFragment) {
      fragmentManager.beginTransaction().remove(fragment).commit()
      fragmentStack.remove(fragment)
      updateBackState()
      return
    }
    fragmentStack.remove(fragment)
    val previous = fragmentStack.lastOrNull()
    if (previous == null) {
      finishHost()
      return
    }
    topVisibleFragment = previous
    fragmentManager.beginTransaction()
      .setCustomAnimations(R.anim.enter_from_left, R.anim.exit_to_right, R.anim.enter_from_right, R.anim.exit_to_left)
      .hide(fragment)
      .show(previous)
      .commit()
    fragmentContainer.postDelayed({
      fragmentManager.beginTransaction().remove(fragment).commitAllowingStateLoss()
    }, 260L)
    requestInvalidateActionBar()
    updateBackState()
  }

  fun popCurrentFragment() {
    topVisibleFragment?.let(::finishFragment) ?: finishHost()
  }

  override fun requestInvalidateActionBar() {
    val fragment = topVisibleFragment
    setTitle(fragment?.titleText ?: context.getString(R.string.app_name))
    setSubtitle(null)
    setHomeAsUp(fragmentStack.size > 1)
    fragmentContainer.isInterceptEnabled = fragment?.isWrapContent() ?: true
  }

  override fun onFlingLeftToRight(): Boolean {
    if (fragmentStack.size > 1) {
      popCurrentFragment()
      return true
    }
    return false
  }

  override fun onFlingRightToLeft(): Boolean = false

  fun onAttachedToWindow() {
    fragmentStack.forEach { it.notifyLayoutPaddingsChanged() }
  }

  private fun restoreFragmentState() {
    fragmentStack.clear()
    fragmentStack.addAll(fragmentManager.fragments.filterIsInstance<BaseSettingFragment>())
    topVisibleFragment = fragmentManager.fragments
      .filterIsInstance<BaseSettingFragment>()
      .lastOrNull { it.isVisible }
      ?: fragmentStack.lastOrNull()
    requestInvalidateActionBar()
    updateBackState()
  }

  private fun updateBackState() {
    setBackEnabled(fragmentStack.size > 1)
  }
}

fun AppCompatActivity.createSettingsUiHostController(
  rootView: View,
  finishHost: () -> Unit,
  setBackEnabled: (Boolean) -> Unit
): SettingsUiHostController {
  val appBarLayout = rootView.findViewById<AppBarLayout>(R.id.topAppBarLayout)
  val appToolbar = rootView.findViewById<Toolbar>(R.id.topAppBar)
  val fragmentContainer = rootView.findViewById<SimpleFlingInterceptLayout>(R.id.fragment_container)
  return SettingsUiHostController(
    context = this,
    fragmentManager = supportFragmentManager,
    rootView = rootView,
    appBarLayout = appBarLayout,
    appToolbar = appToolbar,
    fragmentContainer = fragmentContainer,
    finishHost = finishHost,
    setBackEnabled = setBackEnabled,
    statusBarInsetProvider = { if (this is AppCompatTransferActivity) statusBarLayoutInset else 0 },
    navigationBarInsetProvider = { if (this is AppCompatTransferActivity) navigationBarLayoutInset else 0 },
    setHomeAsUp = { supportActionBar?.setDisplayHomeAsUpEnabled(it) },
    setTitle = { supportActionBar?.title = it },
    setSubtitle = {
      supportActionBar?.subtitle = null
      appToolbar.subtitle = null
    }
  )
}

fun ComponentDialog.createSettingsUiHostController(
  rootView: View,
  fragmentManager: FragmentManager,
  finishHost: () -> Unit,
  setBackEnabled: (Boolean) -> Unit,
  statusBarInsetProvider: () -> Int,
  navigationBarInsetProvider: () -> Int
): SettingsUiHostController {
  val appBarLayout = rootView.findViewById<AppBarLayout>(R.id.topAppBarLayout)
  val appToolbar = rootView.findViewById<Toolbar>(R.id.topAppBar)
  val fragmentContainer = rootView.findViewById<SimpleFlingInterceptLayout>(R.id.fragment_container)
  return SettingsUiHostController(
    context = context,
    fragmentManager = fragmentManager,
    rootView = rootView,
    appBarLayout = appBarLayout,
    appToolbar = appToolbar,
    fragmentContainer = fragmentContainer,
    finishHost = finishHost,
    setBackEnabled = setBackEnabled,
    statusBarInsetProvider = statusBarInsetProvider,
    navigationBarInsetProvider = navigationBarInsetProvider,
    setHomeAsUp = { enabled ->
      appToolbar.navigationIcon = if (enabled) androidx.appcompat.content.res.AppCompatResources.getDrawable(context, androidx.appcompat.R.drawable.abc_ic_ab_back_material) else null
    },
    setTitle = { appToolbar.title = it },
    setSubtitle = { appToolbar.subtitle = null }
  )
}

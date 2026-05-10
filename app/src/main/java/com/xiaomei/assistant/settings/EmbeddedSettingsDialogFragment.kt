package com.xiaomei.assistant.settings

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import androidx.activity.ComponentDialog
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import com.xiaomei.assistant.R
import com.xiaomei.assistant.host.HostSettingsNavigation

internal class EmbeddedSettingsDialogFragment : DialogFragment(), SettingsHostControllerOwner {
  private var moduleContext: Context? = null
  private var controller: SettingsUiHostController? = null
  private var backCallback: OnBackPressedCallback? = null

  override val layoutPaddingTop: Int
    get() = controller?.layoutPaddingTop ?: 0

  override val layoutPaddingBottom: Int
    get() = controller?.layoutPaddingBottom ?: 0

  override fun onAttach(context: Context) {
    super.onAttach(context)
    moduleContext = EmbeddedSettingsContextRegistry.resolve(this)
  }

  override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
    val inflater = super.onGetLayoutInflater(savedInstanceState)
    return inflater.cloneInContext(resolveDialogContext())
  }

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    return ComponentDialog(resolveDialogContext(), R.style.Theme_XiaoMei_ModuleUi).apply {
      requestWindowFeature(Window.FEATURE_NO_TITLE)
      setCanceledOnTouchOutside(false)
    }
  }

  override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
    return inflater.cloneInContext(resolveDialogContext()).inflate(R.layout.activity_settings_ui_host, container, false)
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    val dialog = requireDialog() as ComponentDialog
    val callback = object : OnBackPressedCallback(false) {
      override fun handleOnBackPressed() {
        controller?.popCurrentFragment() ?: dismissAllowingStateLoss()
      }
    }
    backCallback = callback
    dialog.onBackPressedDispatcher.addCallback(viewLifecycleOwner, callback)
    controller = dialog.createSettingsUiHostController(
      rootView = view,
      fragmentManager = childFragmentManager,
      finishHost = { dismissAllowingStateLoss() },
      setBackEnabled = { callback.isEnabled = it },
      statusBarInsetProvider = { statusBarInset(requireActivity()) },
      navigationBarInsetProvider = { navigationBarInset(requireActivity()) }
    ).also { hostController ->
      hostController.attach(
        savedInstanceState = savedInstanceState,
        entrySource = arguments?.getString(HostSettingsNavigation.extraEntrySource) ?: HostSettingsNavigation.sourceHostHook,
        entryTarget = arguments?.getString(HostSettingsNavigation.extraEntryTarget)
      )
    }
  }

  override fun onStart() {
    super.onStart()
    dialog?.window?.apply {
      decorView.setPadding(0, 0, 0, 0)
      setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
      statusBarColor = Color.TRANSPARENT
      navigationBarColor = Color.TRANSPARENT
      setBackgroundDrawable(ColorDrawable(resolveBackgroundColor(requireContext())))
    }
  }

  override fun presentFragment(fragment: BaseSettingFragment) {
    controller?.presentFragment(fragment)
  }

  override fun finishFragment(fragment: BaseSettingFragment) {
    controller?.finishFragment(fragment)
  }

  override fun requestInvalidateActionBar() {
    controller?.requestInvalidateActionBar()
  }

  override fun onDestroyView() {
    controller = null
    backCallback = null
    super.onDestroyView()
  }

  override fun onDetach() {
    EmbeddedSettingsContextRegistry.remove(this)
    moduleContext = null
    super.onDetach()
  }

  private fun resolveDialogContext(): Context {
    return moduleContext ?: ContextThemeWrapper(requireContext(), R.style.Theme_XiaoMei_ModuleUi)
  }

  private fun statusBarInset(activity: FragmentActivity): Int {
    val insets = activity.window.decorView.rootWindowInsets ?: return 0
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      insets.getInsets(WindowInsets.Type.systemBars()).top
    } else {
      @Suppress("DEPRECATION")
      insets.stableInsetTop
    }
  }

  private fun navigationBarInset(activity: FragmentActivity): Int {
    val insets = activity.window.decorView.rootWindowInsets ?: return 0
    return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
      insets.getInsets(WindowInsets.Type.systemBars()).bottom
    } else {
      @Suppress("DEPRECATION")
      insets.stableInsetBottom
    }
  }

  private fun resolveBackgroundColor(context: Context): Int {
    val out = android.util.TypedValue()
    return if (context.theme.resolveAttribute(android.R.attr.windowBackground, out, true) && out.type in android.util.TypedValue.TYPE_FIRST_COLOR_INT..android.util.TypedValue.TYPE_LAST_COLOR_INT) {
      out.data
    } else {
      Color.parseColor("#F7F7F7")
    }
  }

  companion object {
    const val tag = "xiaomei_embedded_settings"

    fun newInstance(extras: Bundle?): EmbeddedSettingsDialogFragment {
      return EmbeddedSettingsDialogFragment().apply {
        arguments = Bundle(extras ?: Bundle())
      }
    }
  }
}
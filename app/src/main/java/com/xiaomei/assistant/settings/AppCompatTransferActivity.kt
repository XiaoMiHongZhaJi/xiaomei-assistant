package com.xiaomei.assistant.settings

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

open class AppCompatTransferActivity : AppCompatActivity() {
  private var patchedClassLoader: ClassLoader? = null

  override fun getClassLoader(): ClassLoader {
    return patchedClassLoader ?: SavedInstanceStatePatchedClassReferencer(
      AppCompatTransferActivity::class.java.classLoader!!
    ).also { patchedClassLoader = it }
  }

  override fun onRestoreInstanceState(savedInstanceState: Bundle) {
    savedInstanceState.getBundle("android:viewHierarchyState")?.classLoader =
      AppCompatTransferActivity::class.java.classLoader
    super.onRestoreInstanceState(savedInstanceState)
  }

  protected fun requestTranslucentStatusBar() {
    val window = window
    val params = window.attributes
    params.flags = params.flags and WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS.inv()
    params.flags = params.flags and WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION.inv()
    params.flags = params.flags or WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      params.layoutInDisplayCutoutMode =
        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    window.attributes = params
    val decorView = window.decorView
    decorView.systemUiVisibility =
      decorView.systemUiVisibility or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
  }

  val navigationBarLayoutInset: Int
    get() {
      val decorView = window.decorView
      if (decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION == 0) {
        return 0
      }
      val insets = decorView.rootWindowInsets ?: return 0
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        insets.getInsets(WindowInsets.Type.systemBars()).bottom
      } else {
        insets.stableInsetBottom
      }
    }

  val statusBarLayoutInset: Int
    get() {
      val decorView = window.decorView
      if (decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN == 0) {
        return 0
      }
      val insets = decorView.rootWindowInsets ?: return 0
      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        insets.getInsets(WindowInsets.Type.systemBars()).top
      } else {
        insets.stableInsetTop
      }
    }

  override fun getResources(): Resources {
    return super.getResources().also {
      ModuleResourceInjector.ensureInjected(it)
    }
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    ModuleResourceInjector.ensureInjected(resources)
    super.onConfigurationChanged(newConfig)
  }
}


package com.xiaomei.assistant.settings

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.Window
import android.view.WindowManager
import com.xiaomei.assistant.R
import com.xiaomei.assistant.host.HostSettingsNavigation
import com.xiaomei.assistant.runtime.StartupInfo
import java.util.WeakHashMap

internal object EmbeddedSettingsContextRegistry {
  fun resolve(fragment: androidx.fragment.app.Fragment): Context? = null
  fun remove(fragment: androidx.fragment.app.Fragment) = Unit
}

object EmbeddedSettingsHostLauncher {
  private const val TAG = "XiaoMeiHook"
  private val activeDialogs = WeakHashMap<Activity, Dialog>()
  private val moduleResourcesCache = HashMap<String, Resources>()

  @JvmStatic
  fun show(activity: Activity, extras: Bundle? = null): Boolean {
    android.util.Log.i(TAG, "embedded_settings_show_start activity=${activity.javaClass.name} source=${extras?.getString(HostSettingsNavigation.extraEntrySource)}")
    activeDialogs[activity]?.takeIf { it.isShowing }?.let {
      android.util.Log.i(TAG, "embedded_settings_dialog_shown activity=${activity.javaClass.name} reused=true")
      return true
    }
    val moduleContext = resolveModuleContext(activity) ?: run {
      android.util.Log.w(TAG, "embedded_settings_dialog_failed activity=${activity.javaClass.name} reason=module_context_unavailable")
      return false
    }
    return runCatching {
      val dialogContext = ModuleHostContext(activity, moduleContext).apply {
        setTheme(R.style.Theme_XiaoMei_ModuleUi)
      }
      val dialog = Dialog(dialogContext, R.style.Theme_XiaoMei_ModuleUi)
      dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
      val controller = EmbeddedSettingsViewController(
        context = dialogContext,
        moduleContext = moduleContext,
        hostActivity = activity,
        extras = extras,
        dismissHost = { dialog.dismiss() }
      )
      dialog.setContentView(controller.rootView)
      dialog.setOnKeyListener { _, keyCode, event ->
        if (keyCode == android.view.KeyEvent.KEYCODE_BACK) {
          if (event.action == android.view.KeyEvent.ACTION_UP) {
            if (!controller.popBackStack()) {
              dialog.dismiss()
            }
          }
          true
        } else {
          false
        }
      }
      dialog.setOnDismissListener {
        activeDialogs.remove(activity)
        controller.destroy()
      }
      activeDialogs[activity] = dialog
      dialog.show()
      dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        statusBarColor = Color.TRANSPARENT
        navigationBarColor = Color.TRANSPARENT
      }
      android.util.Log.i(TAG, "embedded_settings_dialog_shown activity=${activity.javaClass.name} reused=false")
      true
    }.onFailure {
      activeDialogs.remove(activity)
      android.util.Log.w(TAG, "embedded_settings_dialog_failed activity=${activity.javaClass.name} reason=show_failed", it)
    }.getOrDefault(false)
  }

  private fun resolveModuleContext(activity: Activity): Context? {
    return runCatching {
      activity.createPackageContext(
        HostSettingsNavigation.modulePackageName,
        Context.CONTEXT_IGNORE_SECURITY or Context.CONTEXT_INCLUDE_CODE
      )
    }.getOrElse {
      android.util.Log.w(TAG, "createPackageContext failed for embedded settings", it)
      createEmbeddedModuleContext(activity)
    }
  }

  private fun createEmbeddedModuleContext(activity: Activity): Context? {
    val modulePath = runCatching { StartupInfo.getModulePath() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val moduleResources = runCatching {
      synchronized(moduleResourcesCache) {
        moduleResourcesCache.getOrPut(modulePath) {
          val assetManager = AssetManager::class.java.getDeclaredConstructor().newInstance()
          val addAssetPath = AssetManager::class.java.getMethod("addAssetPath", String::class.java)
          check((addAssetPath.invoke(assetManager, modulePath) as Int) != 0) {
            "Failed to add module asset path: $modulePath"
          }
          Resources(assetManager, activity.resources.displayMetrics, activity.resources.configuration)
        }
      }
    }.onFailure { android.util.Log.w(TAG, "Create embedded module resources failed", it) }.getOrNull() ?: return null
    return EmbeddedModuleContext(activity, moduleResources)
  }
}
private class ModuleHostContext(
  baseContext: Context,
  private val moduleContext: Context
) : ContextThemeWrapper(baseContext, 0) {
  private val hostApplicationContext = baseContext.applicationContext ?: baseContext
  private val moduleTheme by lazy(LazyThreadSafetyMode.NONE) {
    moduleContext.resources.newTheme().apply { setTo(moduleContext.theme) }
  }

  override fun getApplicationContext(): Context = hostApplicationContext
  override fun getAssets(): AssetManager = moduleContext.assets
  override fun getResources(): Resources = moduleContext.resources
  override fun getTheme(): Resources.Theme = moduleTheme
  override fun setTheme(resid: Int) { moduleTheme.applyStyle(resid, true) }
  override fun getPackageName(): String = moduleContext.packageName
  override fun getApplicationInfo(): ApplicationInfo = moduleContext.applicationInfo
  override fun getClassLoader(): ClassLoader = moduleContext.classLoader
}

private class EmbeddedModuleContext(
  baseContext: Context,
  private val moduleResources: Resources
) : ContextThemeWrapper(baseContext, 0) {
  private val hostBaseContext = baseContext
  private val hostApplicationContext = baseContext.applicationContext ?: baseContext
  private val moduleTheme by lazy(LazyThreadSafetyMode.NONE) {
    moduleResources.newTheme().apply {
      setTo(baseContext.theme)
      applyStyle(R.style.Theme_XiaoMei_ModuleUi, true)
    }
  }

  override fun getApplicationContext(): Context = hostApplicationContext
  override fun getAssets(): AssetManager = moduleResources.assets
  override fun getResources(): Resources = moduleResources
  override fun getTheme(): Resources.Theme = moduleTheme
  override fun setTheme(resid: Int) { moduleTheme.applyStyle(resid, true) }
  override fun getPackageName(): String = HostSettingsNavigation.modulePackageName
  override fun getApplicationInfo(): ApplicationInfo = hostBaseContext.applicationInfo
  override fun getClassLoader(): ClassLoader = EmbeddedSettingsHostLauncher::class.java.classLoader ?: hostBaseContext.classLoader
}

package com.xiaomei.assistant.status

import android.content.Context
import com.xiaomei.assistant.host.HostSettingsNavigation

internal object ModuleStoreContexts {
  private const val defaultHostPackageName = "com.mi.health"

  fun currentApplicationContext(): Context? {
    return currentApplicationOnly() ?: currentSystemContext()
  }

  fun currentHostContext(packageName: String = defaultHostPackageName): Context? {
    currentApplicationOnly()?.let { applicationContext ->
      if (applicationContext.packageName == packageName) {
        return applicationContext
      }
    }
    currentBoundAppContext(packageName)?.let { boundContext ->
      return boundContext
    }
    val systemContext = currentSystemContext() ?: return null
    return runCatching {
      systemContext.createPackageContext(packageName, Context.CONTEXT_IGNORE_SECURITY).applicationContext
    }.getOrNull()
  }

  fun resolveModuleContext(context: Context): Context? {
    if (context.packageName == HostSettingsNavigation.modulePackageName) {
      return context
    }
    return runCatching {
      context.createPackageContext(
        HostSettingsNavigation.modulePackageName,
        Context.CONTEXT_IGNORE_SECURITY
      )
    }.getOrNull()
  }

  fun resolveModuleContextFromCurrentApplication(): Context? {
    val hostContext = currentApplicationContext() ?: return null
    return resolveModuleContext(hostContext)
  }

  private fun currentApplicationOnly(): Context? {
    return runCatching {
      val clazz = Class.forName("android.app.ActivityThread")
      val application = clazz.getMethod("currentApplication").invoke(null) as? Context
      application?.applicationContext
    }.getOrNull()
  }

  private fun currentSystemContext(): Context? {
    return runCatching {
      val clazz = Class.forName("android.app.ActivityThread")
      val activityThread = clazz.getMethod("currentActivityThread").invoke(null) ?: return@runCatching null
      val systemContext = clazz.getMethod("getSystemContext").invoke(activityThread) as? Context
      systemContext?.applicationContext ?: systemContext
    }.getOrNull()
  }

  private fun currentBoundAppContext(packageName: String): Context? {
    return runCatching {
      val activityThreadClass = Class.forName("android.app.ActivityThread")
      val activityThread = activityThreadClass.getMethod("currentActivityThread").invoke(null)
        ?: return@runCatching null
      val boundApplicationField = activityThreadClass.getDeclaredField("mBoundApplication").apply {
        isAccessible = true
      }
      val boundApplication = boundApplicationField.get(activityThread) ?: return@runCatching null
      val appBindDataClass = boundApplication.javaClass
      val appInfoField = appBindDataClass.getDeclaredField("appInfo").apply {
        isAccessible = true
      }
      val appInfo = appInfoField.get(boundApplication) as? android.content.pm.ApplicationInfo
        ?: return@runCatching null
      if (appInfo.packageName != packageName) {
        return@runCatching null
      }
      val infoField = appBindDataClass.getDeclaredField("info").apply {
        isAccessible = true
      }
      val loadedApk = infoField.get(boundApplication) ?: return@runCatching null
      val contextImplClass = Class.forName("android.app.ContextImpl")
      val loadedApkClass = Class.forName("android.app.LoadedApk")
      val createAppContext = contextImplClass.getDeclaredMethod(
        "createAppContext",
        activityThreadClass,
        loadedApkClass
      ).apply {
        isAccessible = true
      }
      val context = createAppContext.invoke(null, activityThread, loadedApk) as? Context
      context?.applicationContext ?: context
    }.getOrNull()
  }
}

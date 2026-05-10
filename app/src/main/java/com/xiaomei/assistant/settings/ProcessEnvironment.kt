package com.xiaomei.assistant.settings

import android.content.Context
import com.xiaomei.assistant.host.HostSettingsNavigation
import com.xiaomei.assistant.runtime.StartupInfo

object ProcessEnvironment {
  fun isInHostProcess(): Boolean {
    return runCatching { StartupInfo.isInHostProcess() }.getOrDefault(false)
  }

  fun resolveModuleContext(context: Context): Context {
    val appContext = context.applicationContext
    if (appContext.packageName == HostSettingsNavigation.modulePackageName) {
      return appContext
    }
    return runCatching {
      appContext.createPackageContext(
        HostSettingsNavigation.modulePackageName,
        Context.CONTEXT_IGNORE_SECURITY
      )
    }.getOrDefault(appContext)
  }

  fun hostClassLoader(): ClassLoader? {
    return runCatching { StartupInfo.getHostClassLoader() }.getOrNull()
  }

  fun modulePath(): String? {
    return runCatching { StartupInfo.getModulePath() }.getOrNull()
  }
}

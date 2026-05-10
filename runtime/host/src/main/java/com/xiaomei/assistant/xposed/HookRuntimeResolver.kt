package com.xiaomei.assistant.xposed

import android.app.Application
import com.xiaomei.assistant.runtime.RuntimeContainer

object HookRuntimeResolver {
  @Volatile
  private var container: RuntimeContainer? = null

  fun resolve(): RuntimeContainer? {
    container?.let { return it }
    synchronized(this) {
      container?.let { return it }
      val hostApplication = currentApplication() ?: run {
        HookLog.w("Resolve runtime failed: host application is null")
        return null
      }
      return RuntimeContainer(hostApplication.applicationContext).also { container = it }
    }
  }

  private fun currentApplication(): Application? {
    return runCatching {
      val clazz = Class.forName("android.app.ActivityThread")
      clazz.getMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()
  }
}

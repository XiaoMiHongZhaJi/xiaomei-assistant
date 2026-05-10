package com.xiaomei.assistant.settings

import android.content.Context

class SavedInstanceStatePatchedClassReferencer(
  private val baseReferencer: ClassLoader
) : ClassLoader(Context::class.java.classLoader) {

  private val hostReferencer: ClassLoader? = ProcessEnvironment.hostClassLoader()

  override fun findClass(name: String): Class<*> {
    try {
      return Context::class.java.classLoader!!.loadClass(name)
    } catch (_: ClassNotFoundException) {
    }
    if (name == "androidx.lifecycle.ReportFragment" && hostReferencer != null) {
      try {
        return hostReferencer.loadClass(name)
      } catch (_: ClassNotFoundException) {
      }
    }
    return baseReferencer.loadClass(name)
  }
}

package com.xiaomei.assistant.xposed

object HostVersion {
  const val SUPPORTED_PACKAGE = TargetPackages.MI_HEALTH
  const val SUPPORTED_VERSION_NAME = "3.55.0"
  const val SUPPORTED_VERSION_CODE = 355000L

  fun isSupported(versionName: String?, versionCode: Long?): Boolean {
    if (versionName == SUPPORTED_VERSION_NAME) {
      return true
    }
    return versionCode == SUPPORTED_VERSION_CODE
  }
}

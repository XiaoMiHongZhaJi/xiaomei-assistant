package com.xiaomei.assistant.xposed

object TargetPackages {
  const val MI_HEALTH = "com.mi.health"
  const val MI_HEALTH_DEVICE = "com.mi.health:device"

  val supportedProcesses: Set<String> = setOf(
    MI_HEALTH,
    MI_HEALTH_DEVICE
  )

  fun normalizeProcessName(packageName: String, processName: String?): String {
    return processName?.takeIf { it.isNotBlank() } ?: packageName
  }

  fun matches(packageName: String, processName: String?): Boolean {
    if (packageName != MI_HEALTH) {
      return false
    }
    return supportedProcesses.contains(normalizeProcessName(packageName, processName))
  }
}

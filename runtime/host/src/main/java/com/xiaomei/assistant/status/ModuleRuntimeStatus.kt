package com.xiaomei.assistant.status

import kotlinx.serialization.Serializable

@Serializable
data class ModuleProcessStatus(
  val channel: String,
  val hostPackage: String = "com.mi.health",
  val processName: String = "",
  val installedHooks: Int = 0,
  val message: String = "",
  val lastHookTimeMillis: Long = 0L,
  val matchedTarget: Boolean = false
) {
  val isActivated: Boolean
    get() = matchedTarget && installedHooks > 0 && lastHookTimeMillis > 0L

  val hasSignal: Boolean
    get() = processName.isNotBlank() || message.isNotBlank() || lastHookTimeMillis > 0L
}

@Serializable
data class ModuleRuntimeSnapshot(
  val hostPackage: String = "com.mi.health",
  val mainProcess: ModuleProcessStatus = ModuleProcessStatus(
    channel = ModuleRuntimeStatusStore.CHANNEL_MAIN,
    processName = "com.mi.health"
  ),
  val deviceProcess: ModuleProcessStatus = ModuleProcessStatus(
    channel = ModuleRuntimeStatusStore.CHANNEL_DEVICE,
    processName = "com.mi.health:device"
  ),
  val ignoredProcess: ModuleProcessStatus = ModuleProcessStatus(
    channel = ModuleRuntimeStatusStore.CHANNEL_IGNORED
  )
) {
  val isActivated: Boolean
    get() = mainProcess.isActivated || deviceProcess.isActivated

  val hasAnySignal: Boolean
    get() = mainProcess.hasSignal || deviceProcess.hasSignal || ignoredProcess.hasSignal

  fun latestProcessStatus(): ModuleProcessStatus {
    return listOf(deviceProcess, mainProcess, ignoredProcess)
      .maxByOrNull { it.lastHookTimeMillis }
      ?: mainProcess
  }
}

package com.xiaomei.assistant.xposed

import com.xiaomei.assistant.status.ModuleRuntimeStatusStore

data class HookContext(
  val packageName: String,
  val processName: String,
  val classLoader: ClassLoader?,
  val modulePath: String?,
  val hostDataDir: String?
)

interface HostHook {
  val name: String

  fun install(context: HookContext): Boolean
}

class HookDispatcher(
  private val hooks: List<HostHook> = listOf(
    SettingsEntryHook(),
    AivsRuntimeHook()
  )
) {
  fun dispatch(params: HookLoadParams): HookResult {
    if (!TargetPackages.matches(params.packageName, params.processName)) {
      val ignoredProcess = TargetPackages.normalizeProcessName(
        packageName = params.packageName,
        processName = params.processName
      )
      HookLog.d("Ignore host package=${params.packageName} process=$ignoredProcess")
      ModuleRuntimeStatusStore.reportFromHookProcess(
        packageName = params.packageName,
        processName = ignoredProcess,
        installedHooks = 0,
        message = "ignored non-target host",
        matchedTarget = false
      )
      return HookResult(
        installedHooks = 0,
        matchedTarget = false,
        message = "ignored non-target host"
      )
    }

    val context = HookContext(
      packageName = params.packageName,
      processName = TargetPackages.normalizeProcessName(
        packageName = params.packageName,
        processName = params.processName
      ),
      classLoader = params.classLoader,
      modulePath = params.modulePath,
      hostDataDir = params.hostDataDir
    )

    HookLog.i("Dispatch hook package=${context.packageName} process=${context.processName}")
    var installedCount = 0
    val installedHookNames = ArrayList<String>()
    hooks.forEach { hook ->
      runCatching {
        if (hook.install(context)) {
          installedCount += 1
          installedHookNames += hook.name
          HookLog.i("Installed hook=${hook.name}")
        } else {
          HookLog.d("Skipped hook=${hook.name}")
        }
      }.onFailure { throwable ->
        HookLog.e("Failed hook=${hook.name}", throwable)
      }
    }

    val dispatchMessage = when {
      context.processName == TargetPackages.MI_HEALTH &&
        SettingsEntryHook.lastDebugStatus.isNotBlank() ->
        "dispatch finished ${SettingsEntryHook.lastDebugStatus}"
      installedHookNames.isNotEmpty() ->
        "dispatch finished hooks=${installedHookNames.joinToString(",")}"
      else -> "dispatch finished"
    }

    ModuleRuntimeStatusStore.reportFromHookProcess(
      packageName = context.packageName,
      processName = context.processName,
      installedHooks = installedCount,
      message = dispatchMessage,
      matchedTarget = true
    )

    return HookResult(
      installedHooks = installedCount,
      matchedTarget = true,
      message = dispatchMessage
    )
  }
}

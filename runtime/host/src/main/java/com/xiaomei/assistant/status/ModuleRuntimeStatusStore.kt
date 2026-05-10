package com.xiaomei.assistant.status

import android.content.Context
import android.util.Log
import com.xiaomei.assistant.bridge.ModuleSyncServiceClient
import com.xiaomei.assistant.runtime.StartupInfo

object ModuleRuntimeStatusStore {
  private const val tag = "XiaoMeiHook"
  private const val preferencesName = "xiaomei_module_runtime"
  private const val keyHostPackage = "host_package"

  internal const val CHANNEL_MAIN = "main"
  internal const val CHANNEL_DEVICE = "device"
  internal const val CHANNEL_IGNORED = "ignored"

  private const val suffixProcessName = "process_name"
  private const val suffixInstalledHooks = "installed_hooks"
  private const val suffixMessage = "message"
  private const val suffixLastHookTimeMillis = "last_hook_time_millis"
  private const val suffixMatchedTarget = "matched_target"

  fun read(context: Context): ModuleRuntimeSnapshot {
    val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    val hostPackage = prefs.getString(keyHostPackage, "com.mi.health").orEmpty()
    return ModuleRuntimeSnapshot(
      hostPackage = hostPackage,
      mainProcess = readProcessStatus(
        prefs = prefs,
        channel = CHANNEL_MAIN,
        fallbackHostPackage = hostPackage,
        fallbackProcessName = "com.mi.health"
      ),
      deviceProcess = readProcessStatus(
        prefs = prefs,
        channel = CHANNEL_DEVICE,
        fallbackHostPackage = hostPackage,
        fallbackProcessName = "com.mi.health:device"
      ),
      ignoredProcess = readProcessStatus(
        prefs = prefs,
        channel = CHANNEL_IGNORED,
        fallbackHostPackage = hostPackage,
        fallbackProcessName = ""
      )
    )
  }

  fun reportFromHookProcess(
    packageName: String,
    processName: String,
    installedHooks: Int,
    message: String,
    matchedTarget: Boolean
  ) {
    val hostContext = ModuleStoreContexts.currentHostContext(packageName) ?: run {
      StartupInfo.log(
        "Skip runtime status report because no process context package=$packageName process=$processName"
      )
      Log.w(
        tag,
        "Skip runtime status report because no process context package=$packageName process=$processName"
      )
      return
    }
    StartupInfo.log(
      "Runtime status bridge start package=$packageName process=$processName ctx=${hostContext.packageName}"
    )
    val channel = resolveChannel(processName, matchedTarget)
    val updatedStatus = ModuleProcessStatus(
      channel = channel,
      hostPackage = packageName,
      processName = processName,
      installedHooks = installedHooks,
      message = message,
      lastHookTimeMillis = System.currentTimeMillis(),
      matchedTarget = matchedTarget
    )
    applyProcessStatus(hostContext, packageName, updatedStatus)
    val success = ModuleSyncServiceClient.syncRuntimeProcessStatus(hostContext, updatedStatus)
    StartupInfo.log(
      "Runtime status service finish channel=$channel package=$packageName process=$processName success=$success"
    )
    if (!success) {
      Log.w(
        tag,
        "Runtime status service write failed channel=$channel package=$packageName process=$processName"
      )
      StartupInfo.log(
        "Runtime status kept in host local store channel=$channel package=$packageName process=$processName"
      )
    }
  }

  fun applyProcessStatus(
    context: Context,
    hostPackage: String,
    status: ModuleProcessStatus
  ) {
    context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
      .edit()
      .putString(keyHostPackage, hostPackage)
      .apply {
        writeProcessStatus(this, status)
      }
      .apply()
  }

  fun write(context: Context, snapshot: ModuleRuntimeSnapshot) {
    val prefs = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    prefs.edit()
      .putString(keyHostPackage, snapshot.hostPackage)
      .apply {
        writeProcessStatus(this, snapshot.mainProcess)
        writeProcessStatus(this, snapshot.deviceProcess)
        writeProcessStatus(this, snapshot.ignoredProcess)
      }
      .apply()
  }

  private fun readProcessStatus(
    prefs: android.content.SharedPreferences,
    channel: String,
    fallbackHostPackage: String,
    fallbackProcessName: String
  ): ModuleProcessStatus {
    return ModuleProcessStatus(
      channel = channel,
      hostPackage = fallbackHostPackage,
      processName = prefs.getString(keyFor(channel, suffixProcessName), fallbackProcessName).orEmpty(),
      installedHooks = prefs.getInt(keyFor(channel, suffixInstalledHooks), 0),
      message = prefs.getString(keyFor(channel, suffixMessage), "").orEmpty(),
      lastHookTimeMillis = prefs.getLong(keyFor(channel, suffixLastHookTimeMillis), 0L),
      matchedTarget = prefs.getBoolean(keyFor(channel, suffixMatchedTarget), channel != CHANNEL_IGNORED)
    )
  }

  private fun resolveChannel(processName: String, matchedTarget: Boolean): String {
    if (!matchedTarget) {
      return CHANNEL_IGNORED
    }
    return when (processName) {
      "com.mi.health" -> CHANNEL_MAIN
      "com.mi.health:device" -> CHANNEL_DEVICE
      else -> CHANNEL_IGNORED
    }
  }

  private fun keyFor(channel: String, suffix: String): String {
    return "$channel.$suffix"
  }

  private fun writeProcessStatus(
    editor: android.content.SharedPreferences.Editor,
    status: ModuleProcessStatus
  ) {
    editor
      .putString(keyFor(status.channel, suffixProcessName), status.processName)
      .putInt(keyFor(status.channel, suffixInstalledHooks), status.installedHooks)
      .putString(keyFor(status.channel, suffixMessage), status.message)
      .putLong(keyFor(status.channel, suffixLastHookTimeMillis), status.lastHookTimeMillis)
      .putBoolean(keyFor(status.channel, suffixMatchedTarget), status.matchedTarget)
  }
}

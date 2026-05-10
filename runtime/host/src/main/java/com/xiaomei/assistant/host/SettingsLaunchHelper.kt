package com.xiaomei.assistant.host

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle

object SettingsLaunchHelper {
  fun createHostSettingsContainerIntent(
    context: Context,
    entrySource: String = HostSettingsNavigation.sourceHostHook,
    entryTarget: String = HostSettingsNavigation.targetSettingsMain,
    sessionId: String? = null
  ): Intent {
    val launchIntent = context.packageManager.getLaunchIntentForPackage(HostSettingsNavigation.hostPackageName)
      ?: Intent(Intent.ACTION_MAIN).setPackage(HostSettingsNavigation.hostPackageName).addCategory(Intent.CATEGORY_LAUNCHER)
    return launchIntent
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      .putExtra(HostSettingsNavigation.extraOpenEmbeddedSettings, true)
      .putNavigationExtras(entrySource, entryTarget, sessionId)
  }

  fun createHostSettingsContainerIntent(context: Context, extras: Bundle?): Intent {
    val entrySource = extras?.getString(HostSettingsNavigation.extraEntrySource) ?: HostSettingsNavigation.sourceModuleApp
    val entryTarget = extras?.getString(HostSettingsNavigation.extraEntryTarget) ?: HostSettingsNavigation.targetSettingsMain
    val sessionId = extras?.getString(HostSettingsNavigation.extraSessionId)
    return createHostSettingsContainerIntent(context, entrySource, entryTarget, sessionId)
  }

  fun createModuleSettingsActivityIntent(
    context: Context,
    entrySource: String = HostSettingsNavigation.sourceModuleApp,
    entryTarget: String = HostSettingsNavigation.targetSettingsMain,
    sessionId: String? = null
  ): Intent {
    return Intent(HostSettingsNavigation.actionOpenSettingsUiHost)
      .setClassName(HostSettingsNavigation.modulePackageName, HostSettingsNavigation.settingsUiHostActivityClass)
      .putNavigationExtras(entrySource, entryTarget, sessionId)
      .applyDefaultFlags(context)
  }

  fun createModuleSettingsEntryIntent(
    context: Context,
    entrySource: String = HostSettingsNavigation.sourceModuleApp,
    entryTarget: String = HostSettingsNavigation.targetSettingsMain,
    sessionId: String? = null
  ): Intent {
    return createHostSettingsContainerIntent(context, entrySource, entryTarget, sessionId)
  }

  fun createHostSettingsPreviewIntent(
    context: Context,
    entrySource: String = HostSettingsNavigation.sourceModuleApp,
    sessionId: String? = null
  ): Intent {
    return createHostSettingsContainerIntent(context, entrySource, HostSettingsNavigation.targetSettingsMain, sessionId)
  }

  private fun Intent.putNavigationExtras(entrySource: String, entryTarget: String, sessionId: String?): Intent {
    putExtra(HostSettingsNavigation.extraEntrySource, entrySource)
    putExtra(HostSettingsNavigation.extraEntryTarget, entryTarget)
    if (!sessionId.isNullOrBlank()) {
      putExtra(HostSettingsNavigation.extraSessionId, sessionId)
    }
    return this
  }

  private fun Intent.applyDefaultFlags(context: Context): Intent {
    if (context !is Activity) {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return this
  }
}

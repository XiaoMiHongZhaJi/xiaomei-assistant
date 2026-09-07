package com.xiaomei.assistant.host

object HostSettingsNavigation {
  const val modulePackageName = "com.xiaomei.assistant"
  const val hostPackageName = "com.mi.health"

  const val actionOpenHostSettings = "com.xiaomei.assistant.action.OPEN_HOST_SETTINGS"
  const val actionOpenSettingsUiHost = "com.xiaomei.assistant.action.OPEN_SETTINGS_UI_HOST"
  const val moduleSettingsCategory = "de.robv.android.xposed.category.MODULE_SETTINGS"

  const val hostSettingsActivityClass = "com.xiaomei.assistant.HostSettingsActivity"
  const val settingsUiHostActivityClass =
    "com.xiaomei.assistant.settings.SettingsUiFragmentHostActivity"

  const val extraOpenEmbeddedSettings = "com.xiaomei.assistant.extra.OPEN_XIAOMEI_EMBEDDED_SETTINGS"
  const val extraEntrySource = "com.xiaomei.assistant.extra.ENTRY_SOURCE"
  const val extraEntryTarget = "com.xiaomei.assistant.extra.ENTRY_TARGET"
  const val extraSessionId = "com.xiaomei.assistant.extra.SESSION_ID"
  const val extraDiagnosticsSection = "com.xiaomei.assistant.extra.DIAGNOSTICS_SECTION"

  const val sourceModuleApp = "module_app"
  const val sourceHostHook = "host_hook"
  const val sourceHostHookMine = "host_hook_mine"
  const val sourceHostHookAbout = "host_hook_about"
  const val sourceLsposed = "lsposed"

  const val targetHostSettingsPreview = "host_settings_preview"
  const val targetModuleSettingsEntry = "module_settings_entry"
  const val targetSettingsMain = "settings_main"
  const val targetLlmConfig = "llm_config"
  const val targetAivsRules = "aivs_rules"
  const val targetCustomCommand = "custom_command"
  const val targetRuntimeStatus = "runtime_status"
  const val targetAivsStatus = "aivs_status"
  const val targetSessionHistory = "session_history"
  const val targetTroubleshoot = "troubleshoot"
}

package com.xiaomei.assistant.data

import android.content.Context
import android.content.SharedPreferences
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.xiaomei.assistant.bridge.ModuleRemoteStoreBridge
import com.xiaomei.assistant.bridge.ModuleSyncServiceClient
import com.xiaomei.assistant.model.AivsAsrBlacklistRule
import com.xiaomei.assistant.model.CustomCommandRule
import com.xiaomei.assistant.model.LlmApiMode
import com.xiaomei.assistant.model.LlmConfig
import com.xiaomei.assistant.model.LlmProvider
import com.xiaomei.assistant.runtime.StartupInfo
import com.xiaomei.assistant.xposed.HookLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val Context.configStore: DataStore<Preferences> by preferencesDataStore(name = "xiaomei_config")

class AppConfigRepository(
  private val context: Context,
  appScope: CoroutineScope
) {
  private object KeyNames {
    const val baseUrlName = "llm.baseUrl"
    const val apiKeyName = "llm.apiKey"
    const val modelName = "llm.model"
    const val providerName = "llm.provider"
    const val apiModeName = "llm.apiMode"
    const val systemPromptName = "llm.systemPrompt"
    const val temperatureName = "llm.temperature"
    const val maxTokensName = "llm.maxTokens"
    const val asrBlacklistEnabledName = "aivs.asrBlacklist.enabled"
    const val asrBlacklistRulesName = "aivs.asrBlacklist.rules"
    const val customCommandEnabledName = "aivs.customCommand.enabled"
    const val customCommandRulesName = "aivs.customCommand.rules"
    const val hostOverrideEnabledName = "llm.hostOverrideEnabled"
    const val updatedAtName = "llm.updatedAt"
  }

  private object Keys {
    val baseUrl = stringPreferencesKey(KeyNames.baseUrlName)
    val apiKey = stringPreferencesKey(KeyNames.apiKeyName)
    val model = stringPreferencesKey(KeyNames.modelName)
    val provider = stringPreferencesKey(KeyNames.providerName)
    val apiMode = stringPreferencesKey(KeyNames.apiModeName)
    val systemPrompt = stringPreferencesKey(KeyNames.systemPromptName)
    val temperature = floatPreferencesKey(KeyNames.temperatureName)
    val maxTokens = intPreferencesKey(KeyNames.maxTokensName)
    val asrBlacklistEnabled = booleanPreferencesKey(KeyNames.asrBlacklistEnabledName)
    val asrBlacklistRules = stringPreferencesKey(KeyNames.asrBlacklistRulesName)
    val customCommandEnabled = booleanPreferencesKey(KeyNames.customCommandEnabledName)
    val customCommandRules = stringPreferencesKey(KeyNames.customCommandRulesName)
  }

  val config: StateFlow<LlmConfig> = context.configStore.data
    .map { prefs ->
      if (isInHostProcess()) {
        readNewestHostSnapshot(context)?.config ?: mapPreferencesStatic(prefs)
      } else {
        mapPreferencesStatic(prefs)
      }
    }
    .stateIn(appScope, SharingStarted.Eagerly, initialConfig(context))

  suspend fun save(config: LlmConfig) {
    if (isInHostProcess()) {
      HookLog.w("config isInHostProcess LlmConfig: " + config)
      val hostOverrideWritten = writeHostOverride(context, config)
      writeConfig(context, config)
      writeLocalCache(context, config)
      val remoteWritten = writeRemoteConfigFromHost(config)
      val mirrored = ModuleSyncServiceClient.saveLlmConfig(context, config)
      StartupInfo.log(
        "Config saved in host process provider=${LlmProvider.normalize(config.provider)} " +
          "apiMode=${LlmApiMode.normalize(config.provider, config.apiMode)} " +
          "override=$hostOverrideWritten remote=$remoteWritten mirrored=$mirrored ctxPkg=${context.packageName} " +
          "appCtx=${context.applicationContext?.javaClass?.name ?: "null"}"
      )
      if (!mirrored) {
        StartupInfo.log("Config saved to host local override; module sync service unavailable")
      }
      return
    }
    HookLog.w("config is not InHostProcess LlmConfig: " + config)
    writeConfig(context, config)
    writeLocalCache(context, config)
    ModuleRemoteStoreBridge.writeLlmConfig(config)
  }

  suspend fun snapshot(): LlmConfig {
    if (isInHostProcess()) {
      readNewestHostSnapshot(context)?.let {
        StartupInfo.log(
          "Config snapshot from ${it.source} provider=${it.config.provider} apiMode=${it.config.apiMode} " +
            "updatedAt=${it.updatedAt} " +
            "ctxPkg=${context.packageName} appCtx=${context.applicationContext?.javaClass?.name ?: "null"}"
        )
        return it.config
      }
      val local = readConfig(context)
      if (local.apiKey.isNotBlank() || local.baseUrl != LlmConfig().baseUrl || local.model != LlmConfig().model) {
        StartupInfo.log("Config snapshot from host local store provider=${local.provider} apiMode=${local.apiMode}")
        return local
      }
      val remote = readRemoteConfig()
      StartupInfo.log("Config snapshot from remote store provider=${remote.provider} apiMode=${remote.apiMode}")
      return remote
    }
    return readConfig(context)
  }

  private fun isInHostProcess(): Boolean {
    return runCatching { StartupInfo.isInHostProcess() }.getOrDefault(false)
  }

  companion object {
    const val REMOTE_PREFERENCES_GROUP = "xiaomei_config"
    private const val HOST_OVERRIDE_PREFERENCES_NAME = "xiaomei_host_config_override"
    private const val LOCAL_CACHE_PREFERENCES_NAME = "xiaomei_config_cache"
    private val json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
    }
    private val asrBlacklistRuleListSerializer = ListSerializer(AivsAsrBlacklistRule.serializer())
    private val customCommandRuleListSerializer = ListSerializer(CustomCommandRule.serializer())

    private data class ConfigSnapshot(
      val config: LlmConfig,
      val updatedAt: Long,
      val source: String
    )

    suspend fun readConfig(context: Context): LlmConfig {
      return context.configStore.data
        .map(::mapPreferencesStatic)
        .first()
    }

    suspend fun writeConfig(context: Context, config: LlmConfig) {
      context.configStore.edit { prefs ->
        prefs[Keys.baseUrl] = config.baseUrl.trim()
        prefs[Keys.apiKey] = config.apiKey.trim()
        prefs[Keys.model] = config.model.trim()
        prefs[Keys.provider] = LlmProvider.normalize(config.provider)
        prefs[Keys.apiMode] = LlmApiMode.normalize(config.provider, config.apiMode)
        prefs[Keys.systemPrompt] = config.systemPrompt
        prefs[Keys.temperature] = config.temperature
        prefs[Keys.maxTokens] = config.maxTokens
        prefs[Keys.asrBlacklistEnabled] = config.asrBlacklistEnabled
        prefs[Keys.asrBlacklistRules] = encodeAsrBlacklistRules(config.asrBlacklistRules)
        prefs[Keys.customCommandEnabled] = config.customCommandEnabled
        prefs[Keys.customCommandRules] = encodeCustomCommandRules(config.customCommandRules)
      }
    }

    private fun initialConfig(context: Context): LlmConfig {
      return if (runCatching { StartupInfo.isInHostProcess() }.getOrDefault(false)) {
        readNewestLocalSnapshot(context)?.config ?: LlmConfig()
      } else {
        readLocalCache(context) ?: LlmConfig()
      }
    }

    suspend fun syncLocalToRemote(context: Context): Boolean {
      return ModuleRemoteStoreBridge.writeLlmConfig(readConfig(context))
    }

    fun writeRemotePreferences(editor: SharedPreferences.Editor, config: LlmConfig): Boolean {
      return writeSharedPreferences(editor, config)
        .commit()
    }

    private fun writeLocalCache(context: Context, config: LlmConfig): Boolean {
      return writeSharedPreferences(
        context.getSharedPreferences(LOCAL_CACHE_PREFERENCES_NAME, Context.MODE_PRIVATE).edit(),
        config
      )
        .putBoolean(KeyNames.hostOverrideEnabledName, true)
        .commit()
    }

    private fun writeHostOverride(context: Context, config: LlmConfig): Boolean {
      return writeSharedPreferences(
        context.getSharedPreferences(HOST_OVERRIDE_PREFERENCES_NAME, Context.MODE_PRIVATE).edit(),
        config
      )
        .putBoolean(KeyNames.hostOverrideEnabledName, true)
        .commit()
    }

    private fun readLocalCache(context: Context): LlmConfig? {
      return readLocalCacheSnapshot(context)?.config
    }

    private fun readLocalCacheSnapshot(context: Context): ConfigSnapshot? {
      val prefs = context.getSharedPreferences(LOCAL_CACHE_PREFERENCES_NAME, Context.MODE_PRIVATE)
      if (!prefs.getBoolean(KeyNames.hostOverrideEnabledName, false)) {
        return null
      }
      return ConfigSnapshot(
        config = mapSharedPreferencesStatic(prefs),
        updatedAt = prefs.getLong(KeyNames.updatedAtName, 0L),
        source = "local cache"
      )
    }

    private fun readHostOverride(context: Context): LlmConfig? {
      return readHostOverrideSnapshot(context)?.config
    }

    private fun readHostOverrideSnapshot(context: Context): ConfigSnapshot? {
      val prefs = context.getSharedPreferences(HOST_OVERRIDE_PREFERENCES_NAME, Context.MODE_PRIVATE)
      if (!prefs.getBoolean(KeyNames.hostOverrideEnabledName, false)) {
        return null
      }
      return ConfigSnapshot(
        config = mapSharedPreferencesStatic(prefs),
        updatedAt = prefs.getLong(KeyNames.updatedAtName, 0L),
        source = "host override"
      )
    }

    private fun writeSharedPreferences(
      editor: SharedPreferences.Editor,
      config: LlmConfig
    ): SharedPreferences.Editor {
      return editor
        .putString(KeyNames.baseUrlName, config.baseUrl.trim())
        .putString(KeyNames.apiKeyName, config.apiKey.trim())
        .putString(KeyNames.modelName, config.model.trim())
        .putString(KeyNames.providerName, LlmProvider.normalize(config.provider))
        .putString(KeyNames.apiModeName, LlmApiMode.normalize(config.provider, config.apiMode))
        .putString(KeyNames.systemPromptName, config.systemPrompt)
        .putFloat(KeyNames.temperatureName, config.temperature)
        .putInt(KeyNames.maxTokensName, config.maxTokens)
        .putBoolean(KeyNames.asrBlacklistEnabledName, config.asrBlacklistEnabled)
        .putString(KeyNames.asrBlacklistRulesName, encodeAsrBlacklistRules(config.asrBlacklistRules))
        .putBoolean(KeyNames.customCommandEnabledName, config.customCommandEnabled)
        .putString(KeyNames.customCommandRulesName, encodeCustomCommandRules(config.customCommandRules))
        .putLong(KeyNames.updatedAtName, System.currentTimeMillis())
    }

    private fun readNewestLocalSnapshot(context: Context): ConfigSnapshot? {
      return listOfNotNull(
        readHostOverrideSnapshot(context),
        readLocalCacheSnapshot(context)
      ).maxByOrNull { it.updatedAt }
    }

    private fun mapPreferencesStatic(prefs: Preferences): LlmConfig {
      return LlmConfig(
        baseUrl = prefs[Keys.baseUrl] ?: LlmConfig().baseUrl,
        apiKey = prefs[Keys.apiKey] ?: "",
        model = prefs[Keys.model] ?: LlmConfig().model,
        provider = LlmProvider.normalize(prefs[Keys.provider] ?: LlmConfig().provider),
        apiMode = LlmApiMode.normalize(
          prefs[Keys.provider] ?: LlmConfig().provider,
          prefs[Keys.apiMode] ?: LlmConfig().apiMode
        ),
        systemPrompt = prefs[Keys.systemPrompt] ?: LlmConfig().systemPrompt,
        temperature = prefs[Keys.temperature] ?: LlmConfig().temperature,
        maxTokens = prefs[Keys.maxTokens] ?: LlmConfig().maxTokens,
        asrBlacklistEnabled = prefs[Keys.asrBlacklistEnabled] ?: LlmConfig().asrBlacklistEnabled,
        asrBlacklistRules = decodeAsrBlacklistRules(prefs[Keys.asrBlacklistRules]),
        customCommandEnabled = prefs[Keys.customCommandEnabled] ?: LlmConfig().customCommandEnabled,
        customCommandRules = decodeCustomCommandRules(prefs[Keys.customCommandRules])
      )
    }

    private fun mapSharedPreferencesStatic(prefs: SharedPreferences): LlmConfig {
      val defaults = LlmConfig()
      return LlmConfig(
        baseUrl = prefs.getString(KeyNames.baseUrlName, defaults.baseUrl).orEmpty(),
        apiKey = prefs.getString(KeyNames.apiKeyName, "").orEmpty(),
        model = prefs.getString(KeyNames.modelName, defaults.model).orEmpty(),
        provider = LlmProvider.normalize(prefs.getString(KeyNames.providerName, defaults.provider).orEmpty()),
        apiMode = LlmApiMode.normalize(
          prefs.getString(KeyNames.providerName, defaults.provider).orEmpty(),
          prefs.getString(KeyNames.apiModeName, defaults.apiMode).orEmpty()
        ),
        systemPrompt = prefs.getString(KeyNames.systemPromptName, defaults.systemPrompt).orEmpty(),
        temperature = prefs.getFloat(KeyNames.temperatureName, defaults.temperature),
        maxTokens = prefs.getInt(KeyNames.maxTokensName, defaults.maxTokens),
        asrBlacklistEnabled = prefs.getBoolean(KeyNames.asrBlacklistEnabledName, defaults.asrBlacklistEnabled),
        asrBlacklistRules = decodeAsrBlacklistRules(prefs.getString(KeyNames.asrBlacklistRulesName, null)),
        customCommandEnabled = prefs.getBoolean(KeyNames.customCommandEnabledName, defaults.customCommandEnabled),
        customCommandRules = decodeCustomCommandRules(prefs.getString(KeyNames.customCommandRulesName, null))
      )
    }

    private fun readRemoteConfig(): LlmConfig {
      return readRemoteConfigSnapshot()?.config ?: LlmConfig()
    }

    private fun readRemoteConfigSnapshot(): ConfigSnapshot? {
      val module = StartupInfo.getModule()
      if (module == null) {
        StartupInfo.log("Remote config unavailable because module entry is null")
        return null
      }
      return runCatching {
        val prefs = module.getRemotePreferences(REMOTE_PREFERENCES_GROUP)
        if (!hasConfigValues(prefs)) {
          return@runCatching null
        }
        ConfigSnapshot(
          config = mapSharedPreferencesStatic(prefs),
          updatedAt = prefs.getLong(KeyNames.updatedAtName, 0L),
          source = "remote store"
        )
      }.onFailure {
        StartupInfo.log("Remote config read failed: ${it.message ?: it.javaClass.simpleName}")
        StartupInfo.log(it)
      }.getOrNull()
    }

    private fun readNewestHostSnapshot(context: Context): ConfigSnapshot? {
      return listOfNotNull(
        readHostOverrideSnapshot(context),
        readLocalCacheSnapshot(context),
        readRemoteConfigSnapshot()
      ).maxWithOrNull(
        compareBy<ConfigSnapshot> { it.updatedAt }
          .thenBy {
            when (it.source) {
              "host override" -> 3
              "local cache" -> 2
              else -> 1
            }
          }
      )
    }

    private fun hasConfigValues(prefs: SharedPreferences): Boolean {
      return prefs.contains(KeyNames.baseUrlName) ||
        prefs.contains(KeyNames.apiKeyName) ||
        prefs.contains(KeyNames.modelName) ||
        prefs.contains(KeyNames.providerName) ||
        prefs.contains(KeyNames.apiModeName) ||
        prefs.contains(KeyNames.asrBlacklistEnabledName) ||
        prefs.contains(KeyNames.asrBlacklistRulesName) ||
        prefs.contains(KeyNames.customCommandEnabledName) ||
        prefs.contains(KeyNames.customCommandRulesName)
    }

    private fun writeRemoteConfigFromHost(config: LlmConfig): Boolean {
      val module = StartupInfo.getModule()
      if (module == null) {
        StartupInfo.log("Remote config write skipped because module entry is null")
        return false
      }
      return runCatching {
        writeRemotePreferences(module.getRemotePreferences(REMOTE_PREFERENCES_GROUP).edit(), config)
      }.onFailure {
        StartupInfo.log("Remote config write failed: ${it.message ?: it.javaClass.simpleName}")
        StartupInfo.log(it)
      }.getOrDefault(false)
    }

    private fun encodeAsrBlacklistRules(rules: List<AivsAsrBlacklistRule>): String {
      return json.encodeToString(asrBlacklistRuleListSerializer, rules)
    }

    private fun decodeAsrBlacklistRules(raw: String?): List<AivsAsrBlacklistRule> {
      if (raw.isNullOrBlank()) {
        return emptyList()
      }
      return runCatching {
        json.decodeFromString(asrBlacklistRuleListSerializer, raw)
      }.getOrDefault(emptyList())
    }

    private fun encodeCustomCommandRules(rules: List<CustomCommandRule>): String {
      return json.encodeToString(customCommandRuleListSerializer, rules)
    }

    private fun decodeCustomCommandRules(raw: String?): List<CustomCommandRule> {
      if (raw.isNullOrBlank()) {
        return emptyList()
      }
      return runCatching {
        json.decodeFromString(customCommandRuleListSerializer, raw)
      }.getOrDefault(emptyList())
    }
  }
}

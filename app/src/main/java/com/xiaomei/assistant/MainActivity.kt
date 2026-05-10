package com.xiaomei.assistant

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateFormat
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xiaomei.assistant.host.HostSettingsNavigation
import com.xiaomei.assistant.host.SettingsLaunchHelper
import com.xiaomei.assistant.status.ModuleProcessStatus
import com.xiaomei.assistant.status.ModuleRuntimeSnapshot
import com.xiaomei.assistant.status.ModuleRuntimeStatusStore
import java.util.Date

class MainActivity : AppCompatActivity() {

  private lateinit var activationCard: LinearLayout
  private lateinit var activationIcon: ImageView
  private lateinit var activationTitle: TextView
  private lateinit var activationDesc: TextView
  private lateinit var versionText: TextView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (maybeForwardToHostSettings()) {
      return
    }
    setContentView(R.layout.main_v2_normal)

    setSupportActionBar(findViewById(R.id.topAppBar))
    supportActionBar?.title = getString(R.string.app_name)

    activationCard = findViewById(R.id.mainV2_activationStatusLinearLayout)
    activationIcon = findViewById(R.id.mainV2_activationStatusIcon)
    activationTitle = findViewById(R.id.mainV2_activationStatusTitle)
    activationDesc = findViewById(R.id.mainV2_activationStatusDesc)
    versionText = findViewById(R.id.mainTextViewVersion)

    versionText.text = BuildConfig.VERSION_NAME
    activationCard.setOnClickListener {
      openLsposedManager()
    }
  }

  override fun onResume() {
    super.onResume()
    updateActivationStatus()
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    maybeForwardToHostSettings()
  }

  fun openModuleSettingForHost(view: View) {
    when (view.id) {
      R.id.mainRelativeLayoutButtonOpenQQ -> {
        startActivity(
          SettingsLaunchHelper.createHostSettingsContainerIntent(
            context = this,
            entrySource = HostSettingsNavigation.sourceModuleApp
          )
        )
      }

      R.id.mainRelativeLayoutButtonOpenTIM -> {
        openPackage(TargetPackages.hostPackage)
      }

      R.id.mainRelativeLayoutButtonOpenQQLite -> {
        openLsposedManager()
      }
    }
  }

  fun handleClickEvent(view: View) {
    when (view.id) {
      R.id.mainV2_githubRepo -> {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_HOME_URL)))
      }

      R.id.mainV2_help -> {
        AlertDialog.Builder(this)
          .setTitle(R.string.tip)
          .setMessage(R.string.module_help_message)
          .setPositiveButton(android.R.string.ok, null)
          .show()
      }

      R.id.mainV2_troubleshoot -> {
        val status = ModuleRuntimeStatusStore.read(this)
        AlertDialog.Builder(this)
          .setTitle(R.string.BugCheck)
          .setMessage(buildDiagnosticsMessage(status))
          .setPositiveButton(android.R.string.ok, null)
          .setNeutralButton(R.string.mi_fitness) { _, _ ->
            openPackage(TargetPackages.hostPackage)
          }
          .show()
      }
    }
  }

  private fun updateActivationStatus() {
    val status = ModuleRuntimeStatusStore.read(this)
    val state = when {
      status.isActivated -> ActivationUiState(
        background = R.drawable.bg_green_solid,
        icon = R.drawable.ic_success_white,
        title = getString(R.string.status_activated),
        desc = buildActivatedDescription(status)
      )

      status.hasAnySignal -> ActivationUiState(
        background = R.drawable.bg_yellow_solid,
        icon = R.drawable.ic_info_white,
        title = getString(R.string.status_partial),
        desc = buildPartialDescription(status)
      )

      else -> ActivationUiState(
        background = R.drawable.bg_red_solid,
        icon = R.drawable.ic_failure_white,
        title = getString(R.string.status_not_activated),
        desc = getString(R.string.status_not_activated_desc)
      )
    }
    activationCard.background = ContextCompat.getDrawable(this, state.background)
    activationIcon.setImageResource(state.icon)
    activationTitle.text = state.title
    activationDesc.text = state.desc
  }

  private fun buildActivatedDescription(status: ModuleRuntimeSnapshot): String {
    val process = listOf(status.mainProcess, status.deviceProcess)
      .filter { it.isActivated }
      .joinToString(" / ") { it.processName.ifBlank { TargetPackages.hostPackage } }
      .ifBlank { TargetPackages.hostPackage }
    return getString(
      R.string.status_activated_desc_template,
      process,
      formatTimestamp(maxOf(status.mainProcess.lastHookTimeMillis, status.deviceProcess.lastHookTimeMillis))
    )
  }

  private fun buildPartialDescription(status: ModuleRuntimeSnapshot): String {
    val latest = status.latestProcessStatus()
    val time = formatTimestamp(latest.lastHookTimeMillis)
    val message = latest.message.ifBlank { getString(R.string.status_partial_desc_fallback) }
    return getString(R.string.status_partial_desc_template, time, message)
  }

  private fun buildDiagnosticsMessage(status: ModuleRuntimeSnapshot): String {
    return buildString {
      appendLine(getString(R.string.diag_host, status.hostPackage))
      appendLine(getString(R.string.diag_version, BuildConfig.VERSION_NAME))
      appendLine("主进程：${formatProcessStatus(status.mainProcess)}")
      appendLine("设备进程：${formatProcessStatus(status.deviceProcess)}")
      if (status.ignoredProcess.hasSignal) {
        appendLine("忽略进程：${formatProcessStatus(status.ignoredProcess)}")
      }
    }
  }

  private fun formatProcessStatus(status: ModuleProcessStatus): String {
    val process = status.processName.ifBlank { "-" }
    val time = if (status.lastHookTimeMillis > 0L) {
      formatTimestamp(status.lastHookTimeMillis)
    } else {
      getString(R.string.never)
    }
    val message = status.message.ifBlank { "-" }
    return "$process / hooks=${status.installedHooks} / $time / $message"
  }

  private fun openPackage(packageName: String) {
    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
    if (launchIntent == null) {
      AlertDialog.Builder(this)
        .setTitle(R.string.action_failed)
        .setMessage(getString(R.string.package_missing_template, packageName))
        .setPositiveButton(android.R.string.ok, null)
        .show()
      return
    }
    try {
      startActivity(launchIntent)
    } catch (error: ActivityNotFoundException) {
      AlertDialog.Builder(this)
        .setTitle(R.string.action_failed)
        .setMessage(error.message ?: error.javaClass.simpleName)
        .setPositiveButton(android.R.string.ok, null)
        .show()
    }
  }

  private fun openLsposedManager() {
    openPackage(TargetPackages.lsposedPackage)
  }

  private fun maybeForwardToHostSettings(): Boolean {
    val entryTarget = intent.getStringExtra(HostSettingsNavigation.extraEntryTarget)
    if (entryTarget != HostSettingsNavigation.targetModuleSettingsEntry) {
      return false
    }
    startActivity(
      SettingsLaunchHelper.createHostSettingsContainerIntent(
        context = this,
        entrySource = intent.getStringExtra(HostSettingsNavigation.extraEntrySource)
          ?: HostSettingsNavigation.sourceHostHook,
        entryTarget = HostSettingsNavigation.targetSettingsMain,
        sessionId = intent.getStringExtra(HostSettingsNavigation.extraSessionId)
      )
    )
    finish()
    return true
  }

  private fun formatTimestamp(timestamp: Long): String {
    return DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(timestamp)).toString()
  }

  private data class ActivationUiState(
    val background: Int,
    val icon: Int,
    val title: String,
    val desc: String
  )

  private object TargetPackages {
    const val hostPackage = "com.mi.health"
    const val lsposedPackage = "org.lsposed.manager"
  }

  private companion object {
    const val PROJECT_HOME_URL = "https://github.com/2186056836/xiaomei-assistant"
  }
}

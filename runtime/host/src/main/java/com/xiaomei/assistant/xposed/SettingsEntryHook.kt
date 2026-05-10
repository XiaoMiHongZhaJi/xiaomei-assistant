package com.xiaomei.assistant.xposed

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import com.xiaomei.assistant.host.HostSettingsNavigation
import com.xiaomei.assistant.host.SettingsLaunchHelper
import com.xiaomei.assistant.status.ModuleRuntimeStatusStore
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.io.File
import java.util.ArrayList
import java.util.regex.Matcher
import java.util.regex.Pattern

class SettingsEntryHook : HostHook {
  override val name: String = "SettingsEntryHook"

  override fun install(context: HookContext): Boolean {
    if (context.processName != TargetPackages.MI_HEALTH) {
      return false
    }
    val classLoader = context.classLoader ?: run {
      HookLog.w("Skip settings hook because classLoader is null")
      return false
    }
    HookLog.i("Install settings entry hook for host ${HostVersion.SUPPORTED_VERSION_NAME}")
    patchMineRnBundle(context.hostDataDir)
    val embeddedInstalled = installEmbeddedSettingsIntentEntry(classLoader)
    val mineInstalled = installMineRnEntry(classLoader)
    val aboutInstalled = installAboutActivityEntry(classLoader)
    reportSettingsStatus("settings_install embedded=$embeddedInstalled mine_rn=$mineInstalled about_activity=$aboutInstalled")
    return embeddedInstalled || mineInstalled || aboutInstalled
  }

  private fun installEmbeddedSettingsIntentEntry(classLoader: ClassLoader): Boolean {
    return runCatching {
      XposedHelpers.findAndHookMethod(
        Activity::class.java,
        "onResume",
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as? Activity ?: return
            val intent = activity.intent ?: return
            if (!intent.getBooleanExtra(HostSettingsNavigation.extraOpenEmbeddedSettings, false)) {
              return
            }
            val extras = Bundle().apply {
              putString(
                HostSettingsNavigation.extraEntrySource,
                intent.getStringExtra(HostSettingsNavigation.extraEntrySource) ?: HostSettingsNavigation.sourceHostHook
              )
              putString(
                HostSettingsNavigation.extraEntryTarget,
                intent.getStringExtra(HostSettingsNavigation.extraEntryTarget) ?: HostSettingsNavigation.targetSettingsMain
              )
              intent.getStringExtra(HostSettingsNavigation.extraSessionId)?.let {
                putString(HostSettingsNavigation.extraSessionId, it)
              }
            }
            intent.removeExtra(HostSettingsNavigation.extraOpenEmbeddedSettings)
            intent.removeExtra(HostSettingsNavigation.extraEntrySource)
            intent.removeExtra(HostSettingsNavigation.extraEntryTarget)
            intent.removeExtra(HostSettingsNavigation.extraSessionId)
            activity.window?.decorView?.post {
              showEmbeddedSettings(activity, extras)
            }
            reportSettingsStatus("embedded_settings_intent_opened activity=${activity.javaClass.simpleName}")
          }
        }
      )
      true
    }.onFailure {
      HookLog.w("Hook embedded settings intent entry failed", it)
    }.getOrDefault(false)
  }
  private fun installMineRnEntry(classLoader: ClassLoader): Boolean {
    var installed = false
    installed = hookMineRnOnResume(classLoader) || installed
    installed = hookMineRnWindowFocusChanged(classLoader) || installed
    return installed
  }

  private fun hookMineRnOnResume(classLoader: ClassLoader): Boolean {
    return runCatching {
      XposedHelpers.findAndHookMethod(
        MAIN_ACTIVITY_CLASS,
        classLoader,
        "onResume",
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            attachMineRnEntryFromActivity(
              activity = param.thisObject as? Activity ?: return,
              sourceMethod = "onResume"
            )
          }
        }
      )
      HookLog.i("Hooked Mine RN activity method=onResume")
      true
    }.onFailure {
      HookLog.w("Hook Mine RN activity failed method=onResume", it)
    }.getOrDefault(false)
  }

  private fun hookMineRnWindowFocusChanged(classLoader: ClassLoader): Boolean {
    return runCatching {
      XposedHelpers.findAndHookMethod(
        MAIN_ACTIVITY_CLASS,
        classLoader,
        "onWindowFocusChanged",
        Boolean::class.javaPrimitiveType,
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            if (param.args.getOrNull(0) as? Boolean != true) {
              return
            }
            attachMineRnEntryFromActivity(
              activity = param.thisObject as? Activity ?: return,
              sourceMethod = "onWindowFocusChanged"
            )
          }
        }
      )
      HookLog.i("Hooked Mine RN activity method=onWindowFocusChanged")
      true
    }.onFailure {
      HookLog.w("Hook Mine RN activity failed method=onWindowFocusChanged", it)
    }.getOrDefault(false)
  }

  private fun attachMineRnEntryFromActivity(activity: Activity, sourceMethod: String) {
    patchMineRnBundle(activity.applicationInfo?.dataDir)
    val decorView = activity.window?.decorView ?: run {
      HookLog.d("Mine RN attach skipped: decorView missing source=$sourceMethod")
      return
    }
    ensureGlobalLayoutObserver(decorView, MINE_RN_ENTRY_OBSERVER_FIELD) {
      attachMineRnEntry(decorView, suppressSkipLog = false)
    }
    ensureScrollObserver(decorView, MINE_RN_SCROLL_OBSERVER_FIELD) {
      attachMineRnEntry(decorView, suppressSkipLog = false)
    }
    attachMineRnEntry(decorView, suppressSkipLog = false)
    scheduleRetryAttach(
      decorView = decorView,
      fieldPrefix = MINE_RN_RETRY_PREFIX,
      action = { attachMineRnEntry(decorView, suppressSkipLog = false) }
    )
  }

  private fun attachMineRnEntry(
    rootView: View,
    suppressSkipLog: Boolean = false
  ) {
    if (!isMineTabSelected(rootView)) {
      HookLog.d("Mine RN attach skipped: current tab is not mine")
      return
    }
    reportSettingsStatus("mine_rn_resumed MainActivity")
    val existingEntry = findTextAnchorView(rootView, ENTRY_TITLE)
    if (existingEntry != null) {
      configureMineRnClick(rootView, existingEntry)
      reportEntryVisibility(
        "mine_rn_entry",
        rootView,
        existingEntry,
        existingEntry.parent as? ViewGroup ?: rootView.rootView as ViewGroup,
        existingEntry
      )
      reportSettingsStatus("mine_rn_inserted ${resolveViewName(rootView, existingEntry)}")
      return
    }
    val anchorView = findMineRnAnchorView(rootView) ?: run {
      if (!suppressSkipLog) {
        HookLog.d("Mine RN attach skipped: missing anchors=${MINE_RN_TEXT_ANCHORS.joinToString()}")
        reportSettingsStatus("mine_rn_anchor_missing")
      }
      return
    }
    val parent = anchorView.parent as? ViewGroup ?: run {
      HookLog.d(
        "Mine RN attach skipped: anchor parent missing anchor=${resolveViewName(rootView, anchorView)}"
      )
      return
    }
    if (isEntryAttached(rootView, MINE_RN_ENTRY_ATTACHED_FIELD)) {
      HookLog.d("Mine RN entry already attached anchor=${resolveViewName(rootView, anchorView)}")
      return
    }
    HookLog.i(
      "Mine RN anchor resolved anchor=${resolveViewName(rootView, anchorView)} parent=${parent.javaClass.name}"
    )
    reportSettingsStatus(
      "mine_rn_anchor ${resolveViewName(rootView, anchorView)} parent=${parent.javaClass.simpleName}"
    )
    reportSettingsStatus("mine_rn_wait_bundle_reload ${resolveViewName(rootView, anchorView)}")
  }

  private fun configureMineRnClick(rootView: View, entryView: View) {
    if (isEntryAttached(rootView, MINE_RN_ENTRY_ATTACHED_FIELD)) {
      return
    }
    entryView.tag = MINE_RN_ENTRY_TAG
    entryView.isClickable = true
    entryView.isFocusable = true
    entryView.setOnTouchListener { view, event ->
      if (event.action == MotionEvent.ACTION_UP) {
        openMineRnSettings(view)
      }
      true
    }
    entryView.setOnClickListener { view ->
      openMineRnSettings(view)
    }
    markEntryAttached(rootView, MINE_RN_ENTRY_ATTACHED_FIELD)
    HookLog.i("Mine RN real entry touch intercepted anchor=${resolveViewName(rootView, entryView)}")
  }

  private fun openMineRnSettings(view: View) {
    reportSettingsStatus("mine_rn_click_intercepted")
    runCatching {
      openEmbeddedSettings(view, HostSettingsNavigation.sourceHostHookMine)
    }.onFailure { error ->
      HookLog.e("Open module settings from mine RN entry failed", error)
    }
  }

  private fun showEmbeddedSettings(activity: Activity, extras: Bundle) {
    val source = extras.getString(HostSettingsNavigation.extraEntrySource) ?: HostSettingsNavigation.sourceHostHook
    reportSettingsStatus("embedded_settings_reflection_start source=$source activity=${activity.javaClass.name}")
    HookLog.i("Embedded settings reflection start source=$source activity=${activity.javaClass.name}")
    runCatching {
      val launcher = Class.forName("com.xiaomei.assistant.settings.EmbeddedSettingsHostLauncher")
      val instance = launcher.getField("INSTANCE").get(null)
      val shown = launcher.getMethod("show", Activity::class.java, Bundle::class.java)
        .invoke(instance, activity, extras) as? Boolean ?: false
      if (shown) {
        reportSettingsStatus("embedded_settings_reflection_success source=$source activity=${activity.javaClass.simpleName}")
        HookLog.i("Embedded settings reflection success source=$source activity=${activity.javaClass.name}")
      } else {
        reportSettingsStatus("embedded_settings_reflection_failed source=$source activity=${activity.javaClass.simpleName} error=show_false")
        HookLog.w("Embedded settings reflection returned false source=$source activity=${activity.javaClass.name}")
      }
    }.onFailure {
      reportSettingsStatus("embedded_settings_reflection_failed source=$source activity=${activity.javaClass.simpleName} error=${it.javaClass.simpleName}")
      HookLog.w("Embedded settings reflection launch failed source=$source activity=${activity.javaClass.name}", it)
    }
  }
  private fun openEmbeddedSettings(view: View, entrySource: String) {
    val activity = findActivity(view.context)
    if (activity != null) {
      showEmbeddedSettings(
        activity,
        Bundle().apply {
          putString(HostSettingsNavigation.extraEntrySource, entrySource)
          putString(HostSettingsNavigation.extraEntryTarget, HostSettingsNavigation.targetSettingsMain)
        }
      )
      return
    }
    reportSettingsStatus("embedded_settings_activity_missing source=$entrySource context=${view.context.javaClass.name}")
    HookLog.w("Embedded settings skipped because Activity context is unavailable source=$entrySource context=${view.context.javaClass.name}")
  }

  private tailrec fun findActivity(context: Context?): Activity? {
    return when (context) {
      is Activity -> context
      is android.content.ContextWrapper -> findActivity(context.baseContext)
      else -> null
    }
  }
  private fun patchMineRnBundle(hostDataDir: String?) {
    if (hostDataDir.isNullOrBlank()) {
      HookLog.w("Mine RN bundle patch skipped: hostDataDir missing")
      return
    }
    val mineDir = File(hostDataDir, "cache/mirn_bundles/production/miwear/mine")
    if (!mineDir.isDirectory) {
      HookLog.d("Mine RN bundle patch skipped: mine cache missing path=${mineDir.absolutePath}")
      return
    }
    val bundles = mineDir.walkTopDown()
      .filter { it.isFile && it.name == MINE_RN_BUNDLE_FILE }
      .toList()
    if (bundles.isEmpty()) {
      HookLog.d("Mine RN bundle patch skipped: no index bundle path=${mineDir.absolutePath}")
      return
    }
    bundles.forEach { bundle -> patchMineRnBundleFile(bundle) }
  }

  private fun patchMineRnBundleFile(bundle: File) {
    runCatching {
      val original = normalizeMineRnBundlePatch(bundle.readText(Charsets.UTF_8))
      val matcher = MINE_RN_APP_SETTINGS_PATTERN.matcher(original)
      val patchedBuilder = StringBuffer()
      var insertCount = 0
      while (matcher.find()) {
        val prefixStart = (matcher.start() - MINE_RN_PATCH_LOOKBACK).coerceAtLeast(0)
        val prefix = original.substring(prefixStart, matcher.start())
        val replacement = if (prefix.contains("name:\"$ENTRY_TITLE\"")) {
          matcher.group()
        } else {
          insertCount += 1
          createMineRnXiaoMeiElement(matcher.group()) + matcher.group()
        }
        matcher.appendReplacement(patchedBuilder, Matcher.quoteReplacement(replacement))
      }
      matcher.appendTail(patchedBuilder)
      val patched = patchedBuilder.toString()
      if (insertCount == 0 && patched == bundle.readText(Charsets.UTF_8)) {
        HookLog.w("Mine RN bundle patch missed pattern file=${bundle.absolutePath}")
        return
      }
      bundle.writeText(patched, Charsets.UTF_8)
      HookLog.i("Mine RN bundle patched file=${bundle.absolutePath} count=$insertCount")
      reportSettingsStatus("mine_rn_bundle_patched count=$insertCount")
    }.onFailure { error ->
      HookLog.w("Mine RN bundle patch failed file=${bundle.absolutePath}", error)
    }
  }

  private fun normalizeMineRnBundlePatch(bundleText: String): String {
    return MINE_RN_XIAOMEI_OLD_PATTERN.matcher(bundleText).replaceAll("")
  }

  private fun createMineRnXiaoMeiElement(appSettingsElement: String): String {
    val settingItemVar = MINE_RN_SETTING_ITEM_VAR_PATTERN.matcher(appSettingsElement)
      .takeIf { it.find() }
      ?.group(1)
      ?: "h"
    val imageAssetsVar = MINE_RN_IMAGE_ASSETS_VAR_PATTERN.matcher(appSettingsElement)
      .takeIf { it.find() }
      ?.group(1)
      ?: "l"
    return "n.default.createElement($settingItemVar.SettingItem,{name:\"$ENTRY_TITLE\",source:$imageAssetsVar.ImageAssets.menu.appSetting,onPress:function(){}}),"
  }

  private fun isMineTabSelected(rootView: View): Boolean {
    if (hasAnyMineMenuText(rootView)) {
      return true
    }
    val mineTabText = findTextViewByEntryName(
      root = rootView,
      expectedEntryName = MAIN_TAB_TEXT_ENTRY_NAME,
      expectedText = MAIN_TAB_LABEL_MINE
    ) ?: return false
    if (mineTabText.isSelected) {
      return true
    }
    return hasSelectedAncestor(mineTabText)
  }

  private fun hasAnyMineMenuText(rootView: View): Boolean {
    return MINE_RN_PAGE_MARKERS.any { marker -> findTextView(rootView, marker) != null }
  }

  private fun findMineRnAnchorView(rootView: View): View? {
    MINE_RN_TEXT_ANCHORS.forEach { anchorText ->
      findTextAnchorView(rootView, anchorText)?.let { anchor ->
        return anchor
      }
    }
    return null
  }

  private fun hasSelectedAncestor(view: View): Boolean {
    var current: View? = view
    repeat(8) {
      current = current?.parent as? View ?: return false
      if (current?.isSelected == true) {
        return true
      }
    }
    return false
  }

  private fun installMineV4Entry(classLoader: ClassLoader): Boolean {
    val mineItemClass = runCatching {
      Class.forName(MINE_ITEM_CLASS, false, classLoader)
    }.getOrNull() ?: return false
    val adapterClass = runCatching {
      Class.forName(MINE_ITEM_ADAPTER_CLASS, false, classLoader)
    }.getOrNull() ?: return false

    var installed = false
    installed = hookMineV4ListMutation(classLoader, "freshItemList", mineItemClass) || installed
    installed = hookMineV4ListMutation(
      classLoader,
      "showOrHideAidongCoursePaymentEntrance",
      mineItemClass
    ) || installed
    installed = hookMineV4AdapterBinding(classLoader, mineItemClass) || installed

    runCatching {
      XposedHelpers.findAndHookMethod(
        MINE_ITEM_VIEW_HOLDER_CLASS,
        classLoader,
        "bind\$lambda\$1",
        adapterClass,
        mineItemClass,
        View::class.java,
        object : XC_MethodHook() {
          override fun beforeHookedMethod(param: MethodHookParam) {
            val entity = param.args.getOrNull(1) ?: return
            if (!isInjectedMineItem(entity)) {
              return
            }
            val view = param.args.getOrNull(2) as? View ?: return
            HookLog.i(
              "Mine V4 click intercepted path=mine_v4_list title=${getMineItemTitle(entity)}"
            )
            runCatching {
              openEmbeddedSettings(view, HostSettingsNavigation.sourceHostHookMine)

              param.result = null
            }.onFailure { error ->
              HookLog.e("Open module settings from Mine V4 click failed", error)
            }
          }
        }
      )
      HookLog.i("Hooked Mine V4 click dispatcher")
      installed = true
    }.onFailure {
      HookLog.w("Hook Mine V4 click dispatcher failed", it)
    }
    return installed
  }

  private fun hookMineV4ListMutation(
    classLoader: ClassLoader,
    methodName: String,
    mineItemClass: Class<*>
  ): Boolean {
    return runCatching {
      XposedHelpers.findAndHookMethod(
        MINE_V4_VIEW_MODEL_CLASS,
        classLoader,
        methodName,
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            injectMineV4Item(param.thisObject, mineItemClass, methodName)
          }
        }
      )
      HookLog.i("Hooked Mine V4 list mutation method=$methodName")
      true
    }.onFailure {
      HookLog.w("Hook Mine V4 list mutation failed method=$methodName", it)
    }.getOrDefault(false)
  }

  private fun hookMineV4AdapterBinding(
    classLoader: ClassLoader,
    mineItemClass: Class<*>
  ): Boolean {
    return runCatching {
      val viewHolderClass = Class.forName(
        "androidx.recyclerview.widget.RecyclerView\$d0",
        false,
        classLoader
      )
      XposedHelpers.findAndHookMethod(
        MINE_ITEM_ADAPTER_CLASS,
        classLoader,
        "onBindViewHolder",
        viewHolderClass,
        Int::class.javaPrimitiveType,
        object : XC_MethodHook() {
          override fun beforeHookedMethod(param: MethodHookParam) {
            ensureMineV4AdapterEntry(
              adapter = param.thisObject ?: return,
              mineItemClass = mineItemClass,
              holder = param.args.getOrNull(0)
            )
          }

          override fun afterHookedMethod(param: MethodHookParam) {
            reportMineV4Binding(
              adapter = param.thisObject ?: return,
              holder = param.args.getOrNull(0),
              position = param.args.getOrNull(1) as? Int ?: return
            )
          }
        }
      )
      HookLog.i("Hooked Mine V4 adapter binder")
      true
    }.onFailure {
      HookLog.w("Hook Mine V4 adapter binder failed", it)
    }.getOrDefault(false)
  }

  private fun ensureMineV4AdapterEntry(
    adapter: Any,
    mineItemClass: Class<*>,
    holder: Any?
  ) {
    val currentList = runCatching {
      @Suppress("UNCHECKED_CAST")
      XposedHelpers.callMethod(adapter, "getCurrentList") as? List<Any?>
    }.getOrNull() ?: return
    if (currentList.isEmpty()) {
      return
    }
    if (currentList.any { it != null && isInjectedMineItem(it) }) {
      return
    }
    if (XposedHelpers.getAdditionalInstanceField(adapter, ADAPTER_PENDING_FIELD) != null) {
      return
    }

    val icon = resolveMineV4Icon(currentList)
    val customItem = createMineV4Item(mineItemClass, icon) ?: return
    val newList = ArrayList(currentList)
    val insertIndex = resolveMineV4InsertIndex(currentList)
    newList.add(insertIndex, customItem)
    val submit = Runnable {
      runCatching {
        XposedHelpers.callMethod(adapter, "submitList", newList)
        HookLog.i(
          "Posted Mine V4 adapter submitList path=mine_v4_list size=${newList.size} " +
            "index=$insertIndex type=$CUSTOM_ITEM_TYPE"
        )
      }.onFailure {
        HookLog.w("Submit injected Mine V4 list failed", it)
      }
      XposedHelpers.removeAdditionalInstanceField(adapter, ADAPTER_PENDING_FIELD)
    }
    XposedHelpers.setAdditionalInstanceField(adapter, ADAPTER_PENDING_FIELD, submit)
    val itemView = runCatching {
      XposedHelpers.getObjectField(holder, "itemView") as? View
    }.getOrNull()
    if (itemView != null) {
      itemView.post(submit)
    } else {
      submit.run()
    }
  }

  private fun injectMineV4Item(viewModel: Any, mineItemClass: Class<*>, sourceMethod: String) {
    val itemList = getFieldValue(viewModel, "itemList") as? MutableList<Any?> ?: run {
      HookLog.d("Mine V4 injection skipped: itemList missing on ${viewModel.javaClass.name}")
      return
    }
    if (itemList.any { it != null && isInjectedMineItem(it) }) {
      HookLog.d(
        "Mine V4 injection skipped: custom item already present size=${itemList.size} source=$sourceMethod"
      )
      return
    }
    val icon = resolveMineV4Icon(itemList)
    val customItem = createMineV4Item(mineItemClass, icon) ?: return
    val insertIndex = resolveMineV4InsertIndex(itemList)
    itemList.add(insertIndex, customItem)
    HookLog.i(
      "Inserted Mine V4 entry path=mine_v4_list size=${itemList.size} index=$insertIndex " +
        "type=$CUSTOM_ITEM_TYPE source=$sourceMethod"
    )
    reportSettingsStatus("mine_v4_inserted index=$insertIndex size=${itemList.size}")

    val remoteItemList = getFieldValue(viewModel, "remoteItemList") ?: run {
      HookLog.d("Mine V4 injection skipped: remoteItemList missing on ${viewModel.javaClass.name}")
      return
    }
    if (postValue(remoteItemList, ArrayList(itemList))) {
      HookLog.i(
        "Posted Mine V4 remoteItemList path=mine_v4_list size=${itemList.size} " +
          "index=$insertIndex type=$CUSTOM_ITEM_TYPE"
      )
    }
  }

  private fun reportMineV4Binding(adapter: Any, holder: Any?, position: Int) {
    val currentList = runCatching {
      @Suppress("UNCHECKED_CAST")
      XposedHelpers.callMethod(adapter, "getCurrentList") as? List<Any?>
    }.getOrNull() ?: return
    val entity = currentList.getOrNull(position) ?: return
    if (!isInjectedMineItem(entity)) {
      return
    }
    val itemView = runCatching {
      XposedHelpers.getObjectField(holder, "itemView") as? View
    }.getOrNull() ?: return
    HookLog.i(
      "Mine V4 bound path=mine_v4_list position=$position title=${getMineItemTitle(entity)} " +
        "isShown=${itemView.isShown} visibility=${visibilityName(itemView.visibility)} " +
        "chain=${buildParentChainSummary(itemView)}"
    )
    reportSettingsStatus("mine_v4_bound position=$position title=${getMineItemTitle(entity)}")
  }

  private fun createMineV4Item(mineItemClass: Class<*>, icon: Int): Any? {
    return runCatching {
      val constructor = mineItemClass.getDeclaredConstructor(
        Int::class.javaPrimitiveType,
        String::class.java,
        Int::class.javaPrimitiveType,
        Boolean::class.javaPrimitiveType,
        String::class.java,
        String::class.java,
        String::class.java,
        Integer::class.java
      )
      constructor.isAccessible = true
      constructor.newInstance(
        CUSTOM_ITEM_TYPE,
        ENTRY_TITLE,
        icon,
        true,
        null,
        null,
        null,
        null
      )
    }.onFailure {
      HookLog.w("Create Mine V4 custom item failed", it)
    }.getOrNull()
  }

  private fun resolveMineV4Icon(itemList: List<Any?>): Int {
    val appSettingIcon = itemList.firstOrNull { getMineItemType(it) == APP_SETTING_ITEM_TYPE }
      ?.let(::getMineItemIcon)
    if (appSettingIcon != null && appSettingIcon != 0) {
      return appSettingIcon
    }
    return itemList.firstOrNull()?.let(::getMineItemIcon) ?: 0
  }

  private fun resolveMineV4InsertIndex(itemList: List<Any?>): Int {
    return itemList.indexOfFirst { getMineItemType(it) == APP_SETTING_ITEM_TYPE }
      .takeIf { it >= 0 }
      ?: itemList.indexOfFirst { getMineItemType(it) == TRACK_ITEM_TYPE }
        .takeIf { it >= 0 }
      ?: 0
  }

  private fun isInjectedMineItem(entity: Any): Boolean {
    return getMineItemType(entity) == CUSTOM_ITEM_TYPE || getMineItemTitle(entity) == ENTRY_TITLE
  }

  private fun getMineItemType(entity: Any?): Int {
    return invokeZeroArg(entity, "getType")?.toIntOrNull() ?: Int.MIN_VALUE
  }

  private fun getMineItemIcon(entity: Any?): Int {
    return invokeZeroArg(entity, "getIcon")?.toIntOrNull() ?: 0
  }

  private fun getMineItemTitle(entity: Any?): String? {
    return invokeZeroArg(entity, "getTitle")
  }

  private fun installAboutActivityEntry(classLoader: ClassLoader): Boolean {
    var installed = false
    installed = hookAboutActivityOnResume(classLoader) || installed
    installed = hookAboutActivityWindowFocusChanged(classLoader) || installed
    return installed
  }

  private fun hookAboutActivityOnResume(classLoader: ClassLoader): Boolean {
    return runCatching {
      XposedHelpers.findAndHookMethod(
        Activity::class.java,
        "onResume",
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            val activity = param.thisObject as? Activity ?: return
            if (activity.javaClass.name != ABOUT_ACTIVITY_CLASS) {
              return
            }
            attachAboutEntryFromActivity(activity = activity, sourceMethod = "onResume")
          }
        }
      )
      HookLog.i("Hooked AboutActivity method=onResume")
      true
    }.onFailure {
      HookLog.w("Hook AboutActivity failed method=onResume", it)
    }.getOrDefault(false)
  }

  private fun attachAboutEntryFromActivity(
    activity: Activity,
    sourceMethod: String
  ) {
    val decorView = activity.window?.decorView ?: run {
      HookLog.d("About activity attach skipped: decorView missing source=$sourceMethod")
      return
    }
    reportSettingsStatus("about_activity_resumed ${activity.javaClass.simpleName}")
    ensureGlobalLayoutObserver(decorView, ABOUT_ACTIVITY_ENTRY_OBSERVER_FIELD) {
      attachAboutEntry(decorView, suppressSkipLog = false)
    }
    attachAboutEntry(decorView, suppressSkipLog = false)
    scheduleRetryAttach(
      decorView = decorView,
      fieldPrefix = ABOUT_ACTIVITY_RETRY_PREFIX,
      action = { attachAboutEntry(decorView, suppressSkipLog = false) }
    )
  }

  private fun hookAboutActivityWindowFocusChanged(classLoader: ClassLoader): Boolean {
    return runCatching {
      XposedHelpers.findAndHookMethod(
        Activity::class.java,
        "onWindowFocusChanged",
        Boolean::class.javaPrimitiveType,
        object : XC_MethodHook() {
          override fun afterHookedMethod(param: MethodHookParam) {
            if (param.args.getOrNull(0) as? Boolean != true) {
              return
            }
            val activity = param.thisObject as? Activity ?: return
            if (activity.javaClass.name != ABOUT_ACTIVITY_CLASS) {
              return
            }
            attachAboutEntryFromActivity(activity = activity, sourceMethod = "onWindowFocusChanged")
          }
        }
      )
      HookLog.i("Hooked AboutActivity method=onWindowFocusChanged")
      true
    }.onFailure {
      HookLog.w("Hook AboutActivity failed method=onWindowFocusChanged", it)
    }.getOrDefault(false)
  }

  private fun ensureGlobalLayoutObserver(
    rootView: View,
    fieldName: String,
    onLayout: () -> Unit
  ) {
    if (XposedHelpers.getAdditionalInstanceField(rootView, fieldName) != null) {
      return
    }
    val listener = ViewTreeObserver.OnGlobalLayoutListener { onLayout() }
    rootView.viewTreeObserver.addOnGlobalLayoutListener(listener)
    XposedHelpers.setAdditionalInstanceField(rootView, fieldName, listener)
  }

  private fun ensureScrollObserver(
    rootView: View,
    fieldName: String,
    onScroll: () -> Unit
  ) {
    if (XposedHelpers.getAdditionalInstanceField(rootView, fieldName) != null) {
      return
    }
    val listener = ViewTreeObserver.OnScrollChangedListener { onScroll() }
    rootView.viewTreeObserver.addOnScrollChangedListener(listener)
    XposedHelpers.setAdditionalInstanceField(rootView, fieldName, listener)
  }

  private fun scheduleRetryAttach(
    decorView: View,
    fieldPrefix: String,
    action: () -> Unit
  ) {
    val delays = longArrayOf(120L, 360L, 720L, 1200L, 1800L, 2600L, 4200L, 6500L)
    delays.forEachIndexed { index, delay ->
      val fieldName = "$fieldPrefix.$index"
      if (XposedHelpers.getAdditionalInstanceField(decorView, fieldName) != null) {
        return@forEachIndexed
      }
      val runnable = Runnable { action() }
      XposedHelpers.setAdditionalInstanceField(decorView, fieldName, runnable)
      decorView.postDelayed(runnable, delay)
    }
  }

  private fun attachAboutEntry(
    rootView: View,
    suppressSkipLog: Boolean = false
  ) {
    val anchorView = findAboutAnchorView(rootView) ?: run {
      if (!suppressSkipLog) {
        HookLog.d(
          "About entry attach skipped: missing ids=${ABOUT_VIEW_ID_ANCHORS.joinToString()} " +
            "texts=${ABOUT_TEXT_ANCHORS.joinToString()}"
        )
        reportSettingsStatus("about_activity_anchor_missing")
      }
      return
    }
    if (!isAboutPrivacyAnchor(rootView, anchorView)) {
      if (!suppressSkipLog) {
        HookLog.d("About entry attach skipped: resolved anchor is not privacy item anchor=${resolveViewName(rootView, anchorView)}")
        reportSettingsStatus("about_activity_anchor_missing")
      }
      return
    }
    val parent = anchorView.parent as? ViewGroup ?: run {
      if (!suppressSkipLog) {
        HookLog.d(
          "About entry attach skipped: anchor parent missing anchor=${resolveViewName(rootView, anchorView)}"
        )
      }
      return
    }
    if (isEntryAttached(rootView, ABOUT_ENTRY_ATTACHED_FIELD)) {
      HookLog.d("About entry already attached for anchor=${resolveViewName(rootView, anchorView)}")
      return
    }
    HookLog.d(
      "About entry anchor resolved=${resolveViewName(rootView, anchorView)} parent=${parent.javaClass.name}"
    )
    reportSettingsStatus(
      "about_activity_anchor ${resolveViewName(rootView, anchorView)} parent=${parent.javaClass.simpleName}"
    )
    val entryView = createHostEntryView(
      anchorView = anchorView,
      tag = ABOUT_ENTRY_TAG,
      entrySource = HostSettingsNavigation.sourceHostHookAbout,
      failureMessage = "Open module settings from about entry failed",
      hostViewClassName = HOST_SINGLE_LINE_ENTRY_VIEW_CLASS,
      subtitle = null,
      badge = null,
      showIcon = false
    )
    syncEntryPresentation(anchorView, entryView)
    if (!insertEntryView(rootView, parent, anchorView, entryView, "about_activity_entry")) {
      HookLog.w(
        "Insert about settings entry failed for anchor=${resolveViewName(rootView, anchorView)} " +
          "parent=${parent.javaClass.name}"
      )
      return
    }
    markEntryAttached(rootView, ABOUT_ENTRY_ATTACHED_FIELD)
    reportEntryVisibility("about_activity_entry", rootView, anchorView, parent, entryView)
    reportSettingsStatus("about_activity_inserted ${resolveViewName(rootView, anchorView)}")
  }

  private fun findAboutAnchorView(rootView: View): View? {
    ABOUT_VIEW_ID_ANCHORS.firstNotNullOfOrNull { entryName ->
      findViewByResourceEntryName(rootView, entryName)?.let { anchor ->
        return anchor
      }
    }
    return null
  }

  private fun isAboutPrivacyAnchor(rootView: View, anchorView: View): Boolean {
    val resourceEntryName = runCatching {
      if (anchorView.id != View.NO_ID) anchorView.resources.getResourceEntryName(anchorView.id) else null
    }.getOrNull()
    return resourceEntryName != null && ABOUT_VIEW_ID_ANCHORS.contains(resourceEntryName)
  }

  private fun getFieldValue(target: Any, fieldName: String): Any? {
    return runCatching {
      val field = target.javaClass.getDeclaredField(fieldName)
      field.isAccessible = true
      field.get(target)
    }.getOrNull()
  }

  private fun postValue(target: Any, value: Any): Boolean {
    return runCatching {
      val method = target.javaClass.methods.firstOrNull {
        it.name == "postValue" && it.parameterTypes.size == 1
      } ?: return false
      method.isAccessible = true
      method.invoke(target, value)
      true
    }.onFailure {
      HookLog.w("postValue failed on ${target.javaClass.name}", it)
    }.getOrDefault(false)
  }

  private fun createEntryView(
    context: Context,
    anchorView: View,
    hostViewClassName: String = HOST_ENTRY_VIEW_CLASS,
    forceFallback: Boolean = false,
    subtitle: String? = ENTRY_SUBTITLE,
    badge: String? = ENTRY_BADGE,
    showIcon: Boolean = true
  ): View {
    if (forceFallback) {
      return createListFallbackEntryView(context, anchorView, subtitle, badge, showIcon)
    }
    return runCatching {
      val clazz = Class.forName(hostViewClassName, false, context.classLoader)
      val entryView = clazz.getConstructor(Context::class.java).newInstance(context) as View
      entryView.setPadding(
        anchorView.paddingLeft,
        anchorView.paddingTop,
        anchorView.paddingRight,
        anchorView.paddingBottom
      )
      invokeIfExists(entryView, "setTitle", ENTRY_TITLE)
      if (!subtitle.isNullOrBlank()) {
        invokeIfExists(entryView, "setSubtitle", subtitle)
      }
      invokeIfExists(entryView, "showMore", true)
      if (!badge.isNullOrBlank()) {
        invokeIfExists(entryView, "setRemindText", badge)
      }
      if (showIcon) {
        val iconId = context.resources.getIdentifier(
          HOST_ICON_NAME,
          "drawable",
          context.packageName
        )
        if (iconId != 0) {
          invokeIfExists(entryView, "setIcon", iconId)
        }
      }
      entryView
    }.getOrElse { error ->
      HookLog.w("Create host-style entry view failed, fallback to TextView", error)
      createFallbackEntryView(context)
    }
  }

  private fun createListFallbackEntryView(
    context: Context,
    anchorView: View,
    subtitle: String?,
    badge: String?,
    showIcon: Boolean
  ): View {
    return LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      setBackgroundColor(Color.WHITE)
      val padH = if (anchorView.paddingLeft > 0) anchorView.paddingLeft else dp(context, 20)
      val padV = if (anchorView.paddingTop > 0) anchorView.paddingTop else dp(context, 18)
      setPadding(padH, padV, padH, padV)
      minimumHeight = anchorView.height.takeIf { it > 0 } ?: dp(context, 72)
      if (showIcon) {
        val iconId = context.resources.getIdentifier(HOST_ICON_NAME, "drawable", context.packageName)
        if (iconId != 0) {
          addView(
            android.widget.ImageView(context).apply {
              setImageResource(iconId)
            },
            LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)).apply {
              marginEnd = dp(context, 16)
            }
          )
        }
      }
      val textColumn = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
      }
      textColumn.addView(
        TextView(context).apply {
          text = ENTRY_TITLE
          setTextColor(Color.parseColor("#111827"))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
      )
      if (!subtitle.isNullOrBlank()) {
        textColumn.addView(
          TextView(context).apply {
            text = subtitle
            setTextColor(Color.parseColor("#6B7280"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
          }
        )
      }
      addView(
        textColumn,
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
      )
      if (!badge.isNullOrBlank()) {
        addView(
          TextView(context).apply {
            text = badge
            setTextColor(Color.parseColor("#9CA3AF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
          },
          LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
          ).apply {
            marginStart = dp(context, 12)
            marginEnd = dp(context, 10)
          }
        )
      }
      addView(
        TextView(context).apply {
          text = ">"
          setTextColor(Color.parseColor("#D1D5DB"))
          setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        }
      )
    }
  }

  private fun createFallbackEntryView(context: Context): View {
    return TextView(context).apply {
      text = ENTRY_TITLE
      setTextColor(Color.WHITE)
      setBackgroundColor(Color.parseColor("#0F766E"))
      gravity = Gravity.CENTER_VERTICAL
      setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
      val padH = dp(context, 16)
      val padV = dp(context, 14)
      setPadding(padH, padV, padH, padV)
    }
  }

  private fun createHostEntryView(
    anchorView: View,
    tag: String,
    entrySource: String,
    failureMessage: String,
    clickStatus: String? = null,
    hostViewClassName: String = HOST_ENTRY_VIEW_CLASS,
    forceFallback: Boolean = false,
    subtitle: String? = ENTRY_SUBTITLE,
    badge: String? = ENTRY_BADGE,
    showIcon: Boolean = true
  ): View {
    return createEntryView(
      context = anchorView.context,
      anchorView = anchorView,
      hostViewClassName = hostViewClassName,
      forceFallback = forceFallback,
      subtitle = subtitle,
      badge = badge,
      showIcon = showIcon
    ).apply {
      id = View.generateViewId()
      this.tag = tag
      setOnClickListener {
        clickStatus?.let(::reportSettingsStatus)
        runCatching {
          openEmbeddedSettings(it, entrySource)
        }.onFailure { error ->
          HookLog.e(failureMessage, error)
        }
      }
    }
  }

  private fun syncEntryPresentation(anchorView: View, entryView: View) {
    if (anchorView.minimumHeight > 0) {
      entryView.minimumHeight = anchorView.minimumHeight
    } else if (anchorView.height > 0) {
      entryView.minimumHeight = anchorView.height
    }
    entryView.layoutDirection = anchorView.layoutDirection
    entryView.isFocusable = true
    entryView.isClickable = true
  }

  private fun invokeIfExists(target: Any, methodName: String, value: Any) {
    runCatching {
      val parameterType = when (value) {
        is Boolean -> Boolean::class.javaPrimitiveType
        is Int -> Int::class.javaPrimitiveType
        else -> value::class.java
      } ?: return
      val method = target.javaClass.getMethod(methodName, parameterType)
      method.invoke(target, value)
    }.onFailure {
      HookLog.w("Invoke $methodName failed on ${target.javaClass.name}", it)
    }
  }

  private fun invokeZeroArg(target: Any?, methodName: String): String? {
    if (target == null) {
      return null
    }
    return runCatching {
      target.javaClass.getMethod(methodName).invoke(target)?.toString()
    }.getOrNull()
  }

  private fun findTextAnchorView(rootView: View, text: String): View? {
    val textView = findTextView(rootView, text) ?: return null
    val directParent = textView.parent as? ViewGroup
    if (directParent != null) {
      val width = directParent.width.takeIf { it > 0 } ?: directParent.measuredWidth
      val height = directParent.height.takeIf { it > 0 } ?: directParent.measuredHeight
      if (
        directParent.childCount >= 3 &&
        width >= dp(textView.context, 240) &&
        height in dp(textView.context, 48)..dp(textView.context, 96)
      ) {
        return directParent
      }
    }
    return findPreferredMenuAnchor(textView) ?: findClickableAncestor(textView) ?: (textView.parent as? View)
  }

  private fun findTextView(root: View, expectedText: String): TextView? {
    if (root is TextView) {
      val actualText = root.text?.toString()?.trim().orEmpty()
      if (actualText == expectedText || actualText.contains(expectedText)) {
        return root
      }
    }
    if (root !is ViewGroup) {
      return null
    }
    for (index in 0 until root.childCount) {
      val child = root.getChildAt(index)
      val result = findTextView(child, expectedText)
      if (result != null) {
        return result
      }
    }
    return null
  }

  private fun findTextViewByEntryName(
    root: View,
    expectedEntryName: String,
    expectedText: String
  ): TextView? {
    if (root is TextView) {
      val entryName = runCatching {
        if (root.id != View.NO_ID) {
          root.resources.getResourceEntryName(root.id)
        } else {
          null
        }
      }.getOrNull()
      val actualText = root.text?.toString()?.trim().orEmpty()
      if (entryName == expectedEntryName && actualText == expectedText) {
        return root
      }
    }
    if (root !is ViewGroup) {
      return null
    }
    for (index in 0 until root.childCount) {
      val child = root.getChildAt(index)
      val result = findTextViewByEntryName(child, expectedEntryName, expectedText)
      if (result != null) {
        return result
      }
    }
    return null
  }

  private fun findViewByResourceEntryName(root: View, expectedEntryName: String): View? {
    val entryName = runCatching {
      if (root.id != View.NO_ID) {
        root.resources.getResourceEntryName(root.id)
      } else {
        null
      }
    }.getOrNull()
    if (entryName == expectedEntryName) {
      return root
    }
    if (root !is ViewGroup) {
      return null
    }
    for (index in 0 until root.childCount) {
      val child = root.getChildAt(index)
      val result = findViewByResourceEntryName(child, expectedEntryName)
      if (result != null) {
        return result
      }
    }
    return null
  }

  private fun findClickableAncestor(view: View): View? {
    var current: View? = view
    repeat(8) {
      current = current?.parent as? View
      val candidate = current ?: return null
      if (candidate.isClickable || candidate.isFocusable) {
        return candidate
      }
    }
    return null
  }

  private fun findPreferredMenuAnchor(view: View): View? {
    var current: View? = view
    var fallback: View? = null
    var best: View? = null
    repeat(10) {
      current = current?.parent as? View ?: return@repeat
      val candidate = current ?: return@repeat
      if (!candidate.isClickable && !candidate.isFocusable) {
        return@repeat
      }
      if (fallback == null) {
        fallback = candidate
      }
      val width = candidate.width.takeIf { it > 0 } ?: candidate.measuredWidth
      val height = candidate.height.takeIf { it > 0 } ?: candidate.measuredHeight
      val looksLikeMenuRow =
        width >= dp(candidate.context, 240) &&
          height in dp(candidate.context, 48)..dp(candidate.context, 96)
      val parentChildCount = (candidate.parent as? ViewGroup)?.childCount ?: 0
      if (looksLikeMenuRow && parentChildCount >= 2) {
        best = candidate
      }
    }
    return best ?: fallback
  }

  private fun isEntryAttached(rootView: View, fieldName: String): Boolean {
    return XposedHelpers.getAdditionalInstanceField(rootView.rootView, fieldName) == true
  }

  private fun markEntryAttached(rootView: View, fieldName: String) {
    XposedHelpers.setAdditionalInstanceField(rootView.rootView, fieldName, true)
  }

  private fun reportEntryVisibility(
    path: String,
    rootView: View,
    anchorView: View,
    parent: ViewGroup,
    entryView: View
  ) {
    val anchorName = resolveViewName(rootView, anchorView)
    val index = parent.indexOfChild(entryView)
    HookLog.i(
      "Entry visible_check path=$path anchor=$anchorName parent=${parent.javaClass.name} " +
        "index=$index isShown=${entryView.isShown} visibility=${visibilityName(entryView.visibility)} " +
        "bounds=${describeViewBounds(entryView)} alpha=${entryView.alpha} chain=${buildParentChainSummary(entryView)}"
    )
    entryView.post {
      HookLog.i(
        "Entry visible_check_post path=$path anchor=$anchorName parent=${parent.javaClass.name} " +
          "index=${parent.indexOfChild(entryView)} isShown=${entryView.isShown} " +
          "visibility=${visibilityName(entryView.visibility)} bounds=${describeViewBounds(entryView)} " +
          "alpha=${entryView.alpha} chain=${buildParentChainSummary(entryView)}"
      )
    }
  }

  private fun describeViewBounds(view: View): String {
    val location = IntArray(2)
    return runCatching {
      view.getLocationOnScreen(location)
      "${location[0]},${location[1]},${view.width}x${view.height}"
    }.getOrDefault("unknown")
  }

  private fun buildParentChainSummary(view: View): String {
    val chain = ArrayList<String>()
    var current: View? = view
    repeat(6) {
      val target = current ?: return@repeat
      chain.add("${target.javaClass.simpleName}:${visibilityName(target.visibility)}:${target.isShown}")
      current = target.parent as? View
    }
    return chain.joinToString(" > ")
  }

  private fun visibilityName(visibility: Int): String {
    return when (visibility) {
      View.VISIBLE -> "VISIBLE"
      View.INVISIBLE -> "INVISIBLE"
      View.GONE -> "GONE"
      else -> visibility.toString()
    }
  }

  private fun insertEntryView(
    rootView: View,
    parent: ViewGroup,
    anchorView: View,
    entryView: View,
    path: String
  ): Boolean {
    val anchorName = resolveViewName(rootView, anchorView)
    if (parent is ConstraintLayout) {
      val anchorParams = anchorView.layoutParams as? ConstraintLayout.LayoutParams
      if (anchorParams != null) {
        val entryParams = ConstraintLayout.LayoutParams(anchorParams).apply {
          topToTop = anchorParams.topToTop
          topToBottom = anchorParams.topToBottom
          bottomToTop = anchorView.id
          bottomToBottom = ConstraintLayout.LayoutParams.UNSET
        }
        val updatedAnchorParams = ConstraintLayout.LayoutParams(anchorParams).apply {
          topToTop = ConstraintLayout.LayoutParams.UNSET
          topToBottom = entryView.id
        }
        val insertIndex = parent.indexOfChild(anchorView).coerceAtLeast(0)
        parent.addView(entryView, insertIndex, entryParams)
        anchorView.layoutParams = updatedAnchorParams
        HookLog.i(
          "Inserted host settings entry path=$path anchor=$anchorName " +
            "parent=${parent.javaClass.name} layout=constraint"
        )
        return true
      }
    }
    val insertIndex = parent.indexOfChild(anchorView).coerceAtLeast(0)
    val copiedParams = cloneLayoutParams(parent, anchorView.layoutParams, anchorView)
    parent.addView(entryView, insertIndex, copiedParams)
    if (parent.javaClass.name.contains("React", ignoreCase = true)) {
      entryView.layoutParams = copiedParams
      entryView.minimumWidth = anchorView.width.coerceAtLeast(entryView.minimumWidth)
      if (entryView.minimumHeight <= 0) {
        entryView.minimumHeight = anchorView.height.coerceAtLeast(dp(entryView.context, 72))
      }
      entryView.requestLayout()
      parent.requestLayout()
      parent.invalidate()
    }
    HookLog.i(
      "Inserted host settings entry path=$path anchor=$anchorName " +
        "parent=${parent.javaClass.name} layout=standard"
    )
    return true
  }

  private fun findTaggedView(root: View, tag: String): View? {
    if (root.tag == tag) {
      return root
    }
    if (root !is ViewGroup) {
      return null
    }
    for (index in 0 until root.childCount) {
      val match = findTaggedView(root.getChildAt(index), tag)
      if (match != null) {
        return match
      }
    }
    return null
  }

  private fun cloneLayoutParams(
    parent: ViewGroup,
    layoutParams: ViewGroup.LayoutParams?,
    referenceView: View
  ): ViewGroup.LayoutParams {
    val copied = createCompatibleLayoutParams(parent, layoutParams)
    val referenceWidth = referenceView.width.takeIf { it > 0 }
      ?: referenceView.measuredWidth.takeIf { it > 0 }
    val referenceHeight = referenceView.height.takeIf { it > 0 }
      ?: referenceView.measuredHeight.takeIf { it > 0 }
    if (copied.width <= 0) {
      copied.width = referenceWidth ?: ViewGroup.LayoutParams.MATCH_PARENT
    }
    if (copied.height <= 0) {
      copied.height = referenceHeight ?: dp(referenceView.context, 72)
    }
    return copied
  }

  private fun createCompatibleLayoutParams(
    parent: ViewGroup,
    source: ViewGroup.LayoutParams?
  ): ViewGroup.LayoutParams {
    if (source == null) {
      return ViewGroup.MarginLayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      )
    }
    runCatching {
      val method = ViewGroup::class.java.getDeclaredMethod(
        "generateLayoutParams",
        ViewGroup.LayoutParams::class.java
      )
      method.isAccessible = true
      val generated = method.invoke(parent, source) as? ViewGroup.LayoutParams
      if (generated != null) {
        return generated
      }
    }.onFailure {
      HookLog.d("generateLayoutParams fallback parent=${parent.javaClass.name} error=${it.javaClass.simpleName}")
    }
    runCatching {
      val ctor = source.javaClass.getConstructor(source.javaClass)
      val copied = ctor.newInstance(source) as? ViewGroup.LayoutParams
      if (copied != null) {
        return copied
      }
    }.onFailure {
      HookLog.d("copy layoutParams ctor fallback class=${source.javaClass.name} error=${it.javaClass.simpleName}")
    }
    return when (source) {
      is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(source)
      else -> ViewGroup.LayoutParams(source)
    }
  }

  private fun resolveViewName(rootView: View, target: View): String {
    val textView = findDescendantTextView(target)
    if (textView != null) {
      val text = textView.text?.toString()?.trim().orEmpty()
      if (text.isNotBlank()) {
        return "text:$text"
      }
    }
    return runCatching { target.resources.getResourceEntryName(target.id) }
      .getOrDefault(target.javaClass.simpleName)
  }

  private fun findDescendantTextView(root: View): TextView? {
    if (root is TextView && !root.text.isNullOrBlank()) {
      return root
    }
    if (root !is ViewGroup) {
      return null
    }
    for (index in 0 until root.childCount) {
      val child = root.getChildAt(index)
      val result = findDescendantTextView(child)
      if (result != null) {
        return result
      }
    }
    return null
  }

  private fun dp(context: Context, value: Int): Int {
    return (value * context.resources.displayMetrics.density).toInt()
  }

  private fun reportSettingsStatus(message: String) {
    lastDebugStatus = message
    ModuleRuntimeStatusStore.reportFromHookProcess(
      packageName = TargetPackages.MI_HEALTH,
      processName = TargetPackages.MI_HEALTH,
      installedHooks = 1,
      message = message.take(120),
      matchedTarget = true
    )
  }

  companion object {
    const val ABOUT_ENTRY_TAG = "xiaomei_about_settings_entry"
    const val ENTRY_TITLE = "小美助手"
    const val ENTRY_SUBTITLE = "自定义 LLM 小爱"
    const val ENTRY_BADGE = "模块入口"
    const val CUSTOM_ITEM_TYPE = 20001
    const val TRACK_ITEM_TYPE = 1
    const val APP_SETTING_ITEM_TYPE = 3
    const val HOST_ICON_NAME = "mine_setting_icon"
    const val HOST_ENTRY_VIEW_CLASS = "com.xiaomi.fitness.widget.RightArrowBindingTwoLineTextView"
    const val HOST_SINGLE_LINE_ENTRY_VIEW_CLASS =
      "com.xiaomi.fitness.widget.RightArrowBindingSingleLineTextView"
    const val MAIN_ACTIVITY_CLASS = "com.xiaomi.fitness.main.MainActivity"
    const val MAIN_CONTENT_ENTRY_NAME = "main_fl_content"
    const val ABOUT_ACTIVITY_CLASS = "com.xiaomi.fitness.about.AboutActivity"
    const val MINE_FRAGMENT_CLASS = "com.xiaomi.fitness.mine.MineFragment"
    const val MINE_V4_VIEW_MODEL_CLASS = "com.xiaomi.fitness.mine.v4.MineV4ViewModel"
    const val MINE_ITEM_CLASS = "com.xiaomi.fitness.mine.v4.MineItemListModel\$MineItem"
    const val MINE_ITEM_ADAPTER_CLASS = "com.xiaomi.fitness.mine.v4.MineItemListAdapter"
    const val MINE_ITEM_VIEW_HOLDER_CLASS =
      "com.xiaomi.fitness.mine.v4.MineItemListAdapter\$ItemViewHolder"
    const val MAIN_TAB_TEXT_ENTRY_NAME = "main_tv_tab_name"
    const val MAIN_TAB_LABEL_MINE = "我的"
    const val MINE_APP_SETTING_BINDING_FIELD = "d0"
    const val MINE_RN_ENTRY_TAG = "xiaomei_mine_rn_settings_entry"
    const val MINE_RN_BUNDLE_FILE = "index.android.bundle"
    const val MINE_RN_PATCH_LOOKBACK = 260
    val MINE_RN_APP_SETTINGS_PATTERN: Pattern = Pattern.compile(
      "n\\.default\\.createElement\\([a-zA-Z_$][\\w$]*\\.SettingItem,\\{name:[a-zA-Z_$][\\w$]*\\.default\\.trans\\(\\\"mine\\.app\\.item\\.settings\\\"\\).*?\\}\\}\\),"
    )
    val MINE_RN_XIAOMEI_OLD_PATTERN: Pattern = Pattern.compile(
      "n\\.default\\.createElement\\([a-zA-Z_$][\\w$]*\\.SettingItem,\\{name:\\\"小美助手\\\".*?\\}\\}\\),"
    )
    val MINE_RN_SETTING_ITEM_VAR_PATTERN: Pattern = Pattern.compile(
      "createElement\\(([a-zA-Z_$][\\w$]*)\\.SettingItem"
    )
    val MINE_RN_IMAGE_ASSETS_VAR_PATTERN: Pattern = Pattern.compile(
      "source:([a-zA-Z_$][\\w$]*)\\.ImageAssets\\.menu\\.appSetting"
    )
    const val MINE_NATIVE_ENTRY_TAG = "xiaomei_mine_native_settings_entry"
    const val ABOUT_ACTIVITY_ENTRY_OBSERVER_FIELD = "xiaomei_about_activity_entry_observer"
    const val MINE_RN_ENTRY_OBSERVER_FIELD = "xiaomei_mine_rn_entry_observer"
    const val MINE_RN_SCROLL_OBSERVER_FIELD = "xiaomei_mine_rn_scroll_observer"
    const val ABOUT_ACTIVITY_RETRY_PREFIX = "xiaomei_about_activity_retry"
    const val MINE_RN_RETRY_PREFIX = "xiaomei_mine_rn_retry"
    const val ABOUT_ENTRY_ATTACHED_FIELD = "xiaomei_about_entry_attached"
    const val MINE_RN_ENTRY_ATTACHED_FIELD = "xiaomei_mine_rn_entry_attached"
    const val MINE_NATIVE_ENTRY_ATTACHED_FIELD = "xiaomei_mine_native_entry_attached"
    const val ADAPTER_PENDING_FIELD = "xiaomei_mine_v4_adapter_pending"
    @Volatile var lastDebugStatus: String = ""
    val MINE_RN_TEXT_ANCHORS = arrayOf("App 设置", "关于")
    val MINE_RN_PAGE_MARKERS = arrayOf("我的活动", "我的课程", "我的订单", "我的亲友", "小习惯")
    val ABOUT_VIEW_ID_ANCHORS = arrayOf("common_privacy", "common_agreement", "common_privacy_brief")
    val ABOUT_TEXT_ANCHORS = arrayOf("用户隐私政策", "用户隐私政策摘要", "用户隐私", "隐私政策", "用户协议")
  }
}








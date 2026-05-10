package com.xiaomei.assistant.xposed

data class HookLoadParams(
  val packageName: String,
  val processName: String? = null,
  val classLoader: ClassLoader? = null,
  val modulePath: String? = null,
  val hostDataDir: String? = null
)

data class HookResult(
  val installedHooks: Int,
  val matchedTarget: Boolean,
  val message: String
)

class HookEntry(
  private val dispatcher: HookDispatcher = HookDispatcher()
) {
  fun handleLoad(params: HookLoadParams): HookResult {
    return dispatcher.dispatch(params)
  }
}

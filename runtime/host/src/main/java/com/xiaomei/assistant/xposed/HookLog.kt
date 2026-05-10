package com.xiaomei.assistant.xposed

import android.util.Log
import com.xiaomei.assistant.runtime.StartupInfo

internal object HookLog {
  private const val TAG = "XiaoMeiHook"

  fun d(message: String) {
    Log.d(TAG, message)
    StartupInfo.log(message)
  }

  fun i(message: String) {
    Log.i(TAG, message)
    StartupInfo.log(message)
  }

  fun w(message: String, throwable: Throwable? = null) {
    Log.w(TAG, message, throwable)
    StartupInfo.log(message)
    throwable?.let(StartupInfo::log)
  }

  fun e(message: String, throwable: Throwable? = null) {
    Log.e(TAG, message, throwable)
    StartupInfo.log(message)
    throwable?.let(StartupInfo::log)
  }
}

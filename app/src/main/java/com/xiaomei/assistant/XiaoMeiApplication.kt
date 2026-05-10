package com.xiaomei.assistant

import android.app.Application
import com.xiaomei.assistant.runtime.RuntimeContainer
import com.xiaomei.assistant.sync.XposedServiceRegistry

class XiaoMeiApplication : Application() {
  lateinit var container: RuntimeContainer
    private set

  override fun onCreate() {
    super.onCreate()
    container = RuntimeContainer(this)
    XposedServiceRegistry.init(this)
  }
}

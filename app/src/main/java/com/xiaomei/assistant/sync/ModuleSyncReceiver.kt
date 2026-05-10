package com.xiaomei.assistant.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.xiaomei.assistant.bridge.ModuleSyncContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ModuleSyncReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    if (intent == null) return
    val pending = goAsync()
    val action = intent.getStringExtra(ModuleSyncContract.extraAction).orEmpty().ifBlank {
      intent.action.orEmpty()
    }
    val payload = intent.getStringExtra(ModuleSyncContract.extraPayload).orEmpty()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      runCatching {
        ModuleSyncHandler.handle(context.applicationContext ?: context, action, payload)
      }.onSuccess {
        Log.i("XiaoMeiHook", "ModuleSyncReceiver handled action=$action")
      }.onFailure {
        Log.w("XiaoMeiHook", "ModuleSyncReceiver failed action=$action", it)
      }
      pending.finish()
    }
  }
}

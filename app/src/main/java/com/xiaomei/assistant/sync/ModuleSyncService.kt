package com.xiaomei.assistant.sync

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log
import com.xiaomei.assistant.bridge.ModuleSyncContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ModuleSyncService : Service() {
  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val messenger = Messenger(IncomingHandler())

  override fun onBind(intent: Intent?): IBinder = messenger.binder

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent == null) {
      stopSelfResult(startId)
      return START_NOT_STICKY
    }
    serviceScope.launch {
      runCatching {
        handleSyncIntent(intent)
      }.onFailure {
        Log.w("XiaoMeiHook", "ModuleSyncService failed action=${intent.action}", it)
      }
      stopSelfResult(startId)
    }
    return START_NOT_STICKY
  }

  private suspend fun handleSyncIntent(intent: Intent) {
    ModuleSyncHandler.handle(
      context = applicationContext,
      action = intent.getStringExtra(ModuleSyncContract.extraAction).orEmpty().ifBlank { intent.action.orEmpty() },
      payload = intent.getStringExtra(ModuleSyncContract.extraPayload).orEmpty()
    )
  }

  private inner class IncomingHandler : Handler(Looper.getMainLooper()) {
    override fun handleMessage(msg: Message) {
      if (msg.what != ModuleSyncContract.messageWhatSync) {
        super.handleMessage(msg)
        return
      }
      val data = msg.data ?: Bundle.EMPTY
      val action = data.getString(ModuleSyncContract.extraAction).orEmpty()
      val payload = data.getString(ModuleSyncContract.extraPayload).orEmpty()
      Log.i("XiaoMeiHook", "ModuleSyncService messenger received action=$action payloadBytes=${payload.length}")
      serviceScope.launch {
        runCatching {
          ModuleSyncHandler.handle(applicationContext, action, payload)
          Log.i("XiaoMeiHook", "ModuleSyncService messenger handled action=$action")
        }.onFailure {
          Log.w("XiaoMeiHook", "ModuleSyncService messenger failed action=$action", it)
        }
      }
    }
  }
}



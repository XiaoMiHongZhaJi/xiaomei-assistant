package com.xiaomei.assistant.bridge

import com.xiaomei.assistant.model.LlmConfig

object ModuleRemoteStoreBridge {
  interface SyncDelegate {
    fun writeLlmConfig(config: LlmConfig): Boolean

    fun writeSessionHistory(snapshotJson: String): Boolean

    fun writeConversations(snapshotJson: String): Boolean

    fun writeMemory(snapshotJson: String): Boolean
  }

  @Volatile
  private var delegate: SyncDelegate? = null

  fun install(delegate: SyncDelegate?) {
    this.delegate = delegate
  }

  fun writeLlmConfig(config: LlmConfig): Boolean {
    return delegate?.writeLlmConfig(config) ?: false
  }

  fun writeSessionHistory(snapshotJson: String): Boolean {
    return delegate?.writeSessionHistory(snapshotJson) ?: false
  }

  fun writeConversations(snapshotJson: String): Boolean {
    return delegate?.writeConversations(snapshotJson) ?: false
  }

  fun writeMemory(snapshotJson: String): Boolean {
    return delegate?.writeMemory(snapshotJson) ?: false
  }
}


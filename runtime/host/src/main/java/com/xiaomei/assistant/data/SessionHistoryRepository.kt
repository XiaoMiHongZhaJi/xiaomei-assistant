package com.xiaomei.assistant.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.ParcelFileDescriptor
import com.xiaomei.assistant.bridge.ModuleRemoteStoreBridge
import com.xiaomei.assistant.bridge.ModuleSyncServiceClient
import com.xiaomei.assistant.model.SessionRecord
import com.xiaomei.assistant.runtime.StartupInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@SuppressLint("UnsafeOptInUsageError")
@Serializable
private data class HistorySnapshot(
  val records: List<SessionRecord> = emptyList()
)

class SessionHistoryRepository(
  context: Context,
  appScope: CoroutineScope
) {
  private val appContext = context.applicationContext
  private val file = File(appContext.filesDir, "session-history.json")
  private val mutex = Mutex()
  private val _records = MutableStateFlow<List<SessionRecord>>(emptyList())

  val records: StateFlow<List<SessionRecord>> = _records.asStateFlow()

  init {
    appScope.launch {
      _records.value = readRecords()
    }
  }

  suspend fun upsert(record: SessionRecord) {
    if (isInHostProcess()) {
      upsertLocal(record)
      if (!ModuleSyncServiceClient.upsertSessionRecord(appContext, record)) {
        StartupInfo.log("Session history kept in host local store because module sync service is unavailable")
      }
      return
    }
    upsertLocal(record)
  }

  private suspend fun upsertLocal(record: SessionRecord) {
    mutex.withLock {
      val merged = listOf(record) + _records.value.filterNot { it.sessionId == record.sessionId }
      val trimmed = merged.sortedByDescending { it.updatedAt }.take(50)
      val snapshotJson = writeRecords(trimmed)
      _records.value = trimmed
      ModuleRemoteStoreBridge.writeSessionHistory(snapshotJson)
    }
  }

  suspend fun markFavorite(sessionId: String) {
    mutex.withLock {
      val updated = _records.value.map { record ->
        if (record.sessionId == sessionId) {
          record.copy(favorite = true, updatedAt = System.currentTimeMillis())
        } else {
          record
        }
      }.sortedByDescending { it.updatedAt }
      val snapshotJson = writeRecords(updated)
      _records.value = updated
      ModuleRemoteStoreBridge.writeSessionHistory(snapshotJson)
    }
  }

  fun find(sessionId: String): SessionRecord? = _records.value.firstOrNull { it.sessionId == sessionId }

  fun recent(limit: Int): List<SessionRecord> = _records.value.take(limit)

  suspend fun recentSnapshot(limit: Int): List<SessionRecord> {
    if (isInHostProcess()) {
      val localRecords = mutex.withLock {
        if (_records.value.isEmpty() && file.exists()) {
          _records.value = readRecords()
        }
        _records.value
      }
      val remoteRecords = readRemoteRecords(limit)
      return mergeRecords(localRecords, remoteRecords).take(limit)
    }
    return mutex.withLock {
      if (_records.value.isEmpty() && file.exists()) {
        _records.value = readRecords()
      }
      _records.value.take(limit)
    }
  }

  private fun isInHostProcess(): Boolean {
    return runCatching { StartupInfo.isInHostProcess() }.getOrDefault(false)
  }

  private suspend fun readRecords(): List<SessionRecord> = withContext(Dispatchers.IO) {
    if (!file.exists()) {
      return@withContext emptyList()
    }
    decodeRecords(file.readText())
  }

  private suspend fun writeRecords(records: List<SessionRecord>): String = withContext(Dispatchers.IO) {
    val parent = file.parentFile
    if (parent != null && !parent.exists()) {
      parent.mkdirs()
    }
    val snapshotJson = encodeRecords(records)
    file.writeText(snapshotJson)
    snapshotJson
  }

  companion object {
    const val REMOTE_HISTORY_FILE = "session-history.json"

    suspend fun readRecent(context: Context, limit: Int): List<SessionRecord> {
      val repository = SessionHistoryRepository(context, kotlinx.coroutines.CoroutineScope(Dispatchers.IO))
      return repository.recentSnapshot(limit)
    }

    suspend fun upsertRecord(context: Context, record: SessionRecord) {
      val repository = SessionHistoryRepository(context, kotlinx.coroutines.CoroutineScope(Dispatchers.IO))
      repository.upsert(record)
    }

    suspend fun syncLocalToRemote(context: Context): Boolean = withContext(Dispatchers.IO) {
      val file = File(context.applicationContext.filesDir, REMOTE_HISTORY_FILE)
      val snapshotJson = if (file.exists()) {
        file.readText()
      } else {
        encodeRecords(emptyList())
      }
      ModuleRemoteStoreBridge.writeSessionHistory(snapshotJson)
    }

    private fun encodeRecords(records: List<SessionRecord>): String {
      return Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
      }.encodeToString(HistorySnapshot(records))
    }

    private fun decodeRecords(snapshotJson: String): List<SessionRecord> {
      return runCatching {
        Json {
          ignoreUnknownKeys = true
          encodeDefaults = true
          prettyPrint = true
        }.decodeFromString<HistorySnapshot>(snapshotJson).records.sortedByDescending { it.updatedAt }
      }.getOrDefault(emptyList())
    }

    private fun mergeRecords(
      primary: List<SessionRecord>,
      secondary: List<SessionRecord>
    ): List<SessionRecord> {
      val seen = HashSet<String>()
      return (primary + secondary)
        .sortedByDescending { it.updatedAt }
        .filter { seen.add(it.sessionId) }
    }

    private fun readRemoteRecords(limit: Int): List<SessionRecord> {
      val module = StartupInfo.getModule()
      if (module == null) {
        StartupInfo.log("Remote history unavailable because module entry is null")
        return emptyList()
      }
      return runCatching {
        val descriptor = module.openRemoteFile(REMOTE_HISTORY_FILE)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
          decodeRecords(reader.readText()).take(limit)
        }
      }.onFailure {
        StartupInfo.log("Remote history read failed: ${it.message ?: it.javaClass.simpleName}")
      }.getOrDefault(emptyList())
    }
  }
}

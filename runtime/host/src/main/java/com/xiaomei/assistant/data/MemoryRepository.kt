package com.xiaomei.assistant.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.ParcelFileDescriptor
import com.xiaomei.assistant.bridge.ModuleRemoteStoreBridge
import com.xiaomei.assistant.bridge.ModuleSyncServiceClient
import com.xiaomei.assistant.model.MemoryRecord
import com.xiaomei.assistant.model.MemorySource
import com.xiaomei.assistant.model.MemoryToolAction
import com.xiaomei.assistant.model.MemoryToolActionType
import com.xiaomei.assistant.runtime.StartupInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@SuppressLint("UnsafeOptInUsageError")
@Serializable
private data class MemorySnapshot(
  val records: List<MemoryRecord> = emptyList()
)

class MemoryRepository(
  context: Context,
  appScope: CoroutineScope
) {
  private val appContext = context.applicationContext
  private val file = File(appContext.filesDir, REMOTE_MEMORY_FILE)
  private val mutex = Mutex()
  private val _records = MutableStateFlow<List<MemoryRecord>>(emptyList())

  val records: StateFlow<List<MemoryRecord>> = _records.asStateFlow()

  init {
    appScope.launch {
      _records.value = readRecords()
    }
  }

  suspend fun add(content: String, enabled: Boolean = true, source: String = MemorySource.MANUAL): MemoryRecord {
    val normalized = content.trim()
    require(normalized.isNotBlank()) { "memory content is blank" }
    val now = System.currentTimeMillis()
    val record = MemoryRecord(
      id = UUID.randomUUID().toString(),
      content = normalized,
      enabled = enabled,
      source = source,
      createdAt = now,
      updatedAt = now
    )
    upsert(record)
    return record
  }

  suspend fun upsert(record: MemoryRecord) {
    val normalized = record.content.trim()
    if (normalized.isBlank()) return
    if (isInHostProcess()) {
      upsertLocal(record.copy(content = normalized))
      if (!ModuleSyncServiceClient.upsertMemoryRecord(appContext, record.copy(content = normalized))) {
        StartupInfo.log("Memory record kept in host local store because module sync service is unavailable")
      }
      return
    }
    upsertLocal(record.copy(content = normalized))
  }

  private suspend fun upsertLocal(record: MemoryRecord) {
    val normalized = record.content.trim()
    mutate { current ->
      val now = System.currentTimeMillis()
      val incoming = record.copy(content = normalized, updatedAt = if (record.updatedAt > 0L) record.updatedAt else now)
      (listOf(incoming) + current.filterNot { it.id == incoming.id })
        .deduplicate()
        .sortedByDescending { it.updatedAt }
        .take(MAX_RECORDS)
    }
  }

  suspend fun updateContent(id: String, content: String) {
    val normalized = content.trim()
    if (normalized.isBlank()) return
    mutate { current ->
      current.map { record ->
        if (record.id == id) record.copy(content = normalized, updatedAt = System.currentTimeMillis()) else record
      }.deduplicate().sortedByDescending { it.updatedAt }
    }
  }

  suspend fun setEnabled(id: String, enabled: Boolean) {
    mutate { current ->
      current.map { record ->
        if (record.id == id) record.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) else record
      }.sortedByDescending { it.updatedAt }
    }
  }

  suspend fun delete(id: String) {
    mutate { current -> current.filterNot { it.id == id } }
  }

  suspend fun applyToolActions(actions: List<MemoryToolAction>, sourceSessionId: String = ""): Int {
    val normalizedActions = actions.mapNotNull(::normalizeAction)
    if (normalizedActions.isEmpty()) return 0
    if (isInHostProcess()) {
      val changed = applyToolActionsLocal(normalizedActions, sourceSessionId)
      if (!ModuleSyncServiceClient.applyMemoryToolActions(appContext, normalizedActions, sourceSessionId)) {
        StartupInfo.log("Memory tool actions kept in host local store because module sync service is unavailable")
      }
      return changed
    }
    return applyToolActionsLocal(normalizedActions, sourceSessionId)
  }

  private suspend fun applyToolActionsLocal(
    normalizedActions: List<MemoryToolAction>,
    sourceSessionId: String
  ): Int {
    var changed = 0
    mutate { current ->
      var records = current
      normalizedActions.forEach { action ->
        when (action.action) {
          MemoryToolActionType.CREATE -> {
            val content = action.content.orEmpty().trim()
            if (content.isNotBlank() && records.none { sameMemory(it.content, content) }) {
              val now = System.currentTimeMillis()
              records = listOf(
                MemoryRecord(
                  id = UUID.randomUUID().toString(),
                  content = content,
                  enabled = true,
                  source = MemorySource.AUTO,
                  sourceSessionId = sourceSessionId,
                  createdAt = now,
                  updatedAt = now
                )
              ) + records
              changed++
            }
          }
          MemoryToolActionType.EDIT -> {
            val id = action.id.orEmpty()
            val content = action.content.orEmpty().trim()
            var edited = false
            records = records.map { record ->
              if (record.id == id && content.isNotBlank()) {
                edited = true
                record.copy(content = content, source = MemorySource.AUTO, sourceSessionId = sourceSessionId, updatedAt = System.currentTimeMillis())
              } else {
                record
              }
            }
            if (edited) changed++
          }
          MemoryToolActionType.DELETE -> {
            val id = action.id.orEmpty()
            val before = records.size
            records = records.filterNot { it.id == id }
            if (records.size != before) changed++
          }
        }
      }
      records.deduplicate().sortedByDescending { it.updatedAt }.take(MAX_RECORDS)
    }
    return changed
  }

  suspend fun enabledSnapshot(limit: Int): List<MemoryRecord> {
    if (isInHostProcess()) {
      val localRecords = mutex.withLock {
        if (_records.value.isEmpty() && file.exists()) {
          _records.value = readRecords()
        }
        _records.value
      }
      val remoteRecords = readRemoteRecords(limit)
      return mergeRecords(localRecords, remoteRecords).filter { it.enabled }.take(limit)
    }
    return mutex.withLock {
      if (_records.value.isEmpty() && file.exists()) {
        _records.value = readRecords()
      }
      _records.value.filter { it.enabled }.take(limit)
    }
  }

  private suspend fun mutate(transform: (List<MemoryRecord>) -> List<MemoryRecord>) {
    mutex.withLock {
      val updated = transform(_records.value).sortedByDescending { it.updatedAt }.take(MAX_RECORDS)
      val snapshotJson = writeRecords(updated)
      _records.value = updated
      ModuleRemoteStoreBridge.writeMemory(snapshotJson)
    }
  }

  private fun normalizeAction(action: MemoryToolAction): MemoryToolAction? {
    val type = action.action.trim().lowercase()
    return when (type) {
      MemoryToolActionType.CREATE -> {
        val content = action.content?.trim().orEmpty()
        if (content.isBlank()) null else MemoryToolAction(type, content = content)
      }
      MemoryToolActionType.EDIT -> {
        val id = action.id?.trim().orEmpty()
        val content = action.content?.trim().orEmpty()
        if (id.isBlank() || content.isBlank()) null else MemoryToolAction(type, id = id, content = content)
      }
      MemoryToolActionType.DELETE -> {
        val id = action.id?.trim().orEmpty()
        if (id.isBlank()) null else MemoryToolAction(type, id = id)
      }
      else -> null
    }
  }

  private fun List<MemoryRecord>.deduplicate(): List<MemoryRecord> {
    val seen = HashSet<String>()
    return filter { record ->
      val key = normalizeMemory(record.content)
      key.isNotBlank() && seen.add(key)
    }
  }

  private fun sameMemory(left: String, right: String): Boolean = normalizeMemory(left) == normalizeMemory(right)

  private fun normalizeMemory(value: String): String {
    return value.lowercase()
      .replace(Regex("\\s+"), "")
      .replace("。", "")
      .replace(".", "")
      .trim()
  }

  private fun isInHostProcess(): Boolean {
    return runCatching { StartupInfo.isInHostProcess() }.getOrDefault(false)
  }

  private suspend fun readRecords(): List<MemoryRecord> = withContext(Dispatchers.IO) {
    if (!file.exists()) return@withContext emptyList()
    decodeRecords(file.readText())
  }

  private suspend fun writeRecords(records: List<MemoryRecord>): String = withContext(Dispatchers.IO) {
    file.parentFile?.takeIf { !it.exists() }?.mkdirs()
    val snapshotJson = encodeRecords(records)
    file.writeText(snapshotJson)
    snapshotJson
  }

  companion object {
    const val REMOTE_MEMORY_FILE = "assistant-memory.json"
    const val GLOBAL_MEMORY_ID = "__global__"
    private const val MAX_RECORDS = 100

    suspend fun syncLocalToRemote(context: Context): Boolean = withContext(Dispatchers.IO) {
      val file = File(context.applicationContext.filesDir, REMOTE_MEMORY_FILE)
      val snapshotJson = if (file.exists()) file.readText() else encodeRecords(emptyList())
      ModuleRemoteStoreBridge.writeMemory(snapshotJson)
    }

    private fun encodeRecords(records: List<MemoryRecord>): String {
      return Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
        .encodeToString(MemorySnapshot(records))
    }

    private fun decodeRecords(snapshotJson: String): List<MemoryRecord> {
      return runCatching {
        Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
          .decodeFromString<MemorySnapshot>(snapshotJson).records.sortedByDescending { it.updatedAt }
      }.getOrDefault(emptyList())
    }

    private fun mergeRecords(
      primary: List<MemoryRecord>,
      secondary: List<MemoryRecord>
    ): List<MemoryRecord> {
      val seen = HashSet<String>()
      return (primary + secondary)
        .sortedByDescending { it.updatedAt }
        .filter { seen.add(it.id) }
    }

    private fun readRemoteRecords(limit: Int): List<MemoryRecord> {
      val module = StartupInfo.getModule()
      if (module == null) {
        StartupInfo.log("Remote memory unavailable because module entry is null")
        return emptyList()
      }
      return runCatching {
        val descriptor = module.openRemoteFile(REMOTE_MEMORY_FILE)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
          decodeRecords(reader.readText()).take(limit)
        }
      }.onFailure {
        StartupInfo.log("Remote memory read failed: ${it.message ?: it.javaClass.simpleName}")
      }.getOrDefault(emptyList())
    }
  }
}

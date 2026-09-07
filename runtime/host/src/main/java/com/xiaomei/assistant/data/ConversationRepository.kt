package com.xiaomei.assistant.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.ParcelFileDescriptor
import com.xiaomei.assistant.bridge.ModuleRemoteStoreBridge
import com.xiaomei.assistant.bridge.ModuleSyncServiceClient
import com.xiaomei.assistant.model.ConversationMessageRecord
import com.xiaomei.assistant.model.ConversationMessageRole
import com.xiaomei.assistant.model.ConversationRecord
import com.xiaomei.assistant.model.ConversationState
import com.xiaomei.assistant.model.SessionRecord
import com.xiaomei.assistant.runtime.StartupInfo
import java.io.File
import java.util.UUID
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

@SuppressLint("UnsafeOptInUsageError")
@Serializable
private data class LegacyHistorySnapshot(
  val records: List<SessionRecord> = emptyList()
)

class ConversationRepository(
  context: Context,
  appScope: CoroutineScope
) {
  private val appContext = context.applicationContext
  private val file = File(appContext.filesDir, REMOTE_CONVERSATIONS_FILE)
  private val legacyHistoryFile = File(appContext.filesDir, SessionHistoryRepository.REMOTE_HISTORY_FILE)
  private val mutex = Mutex()
  private val _state = MutableStateFlow(ConversationState())

  val state: StateFlow<ConversationState> = _state.asStateFlow()

  init {
    appScope.launch {
      mutex.withLock {
        val initial = readLocalState()
        _state.value = initial
        if (!file.exists() || initial.migratedLegacyHistory) {
          writeLocalState(initial)
        }
      }
    }
  }

  suspend fun stateSnapshot(): ConversationState {
    val local = mutex.withLock { currentLocalStateLocked() }
    if (!isInHostProcess()) {
      return local
    }
    val remote = readRemoteState()
    return if (remote.hasConversationData()) {
      mergeStates(primary = remote, secondary = local)
    } else {
      local
    }
  }

  suspend fun refreshFromStorage(): ConversationState {
    return mutex.withLock {
      readLocalState().also { _state.value = it }
    }
  }

  suspend fun ensureActiveConversation(): ConversationRecord {
    val updated = mutateLocal { normalizeState(it) }
    return updated.activeConversation() ?: error("active conversation is unavailable")
  }

  suspend fun createConversation(title: String? = null): ConversationRecord {
    val now = System.currentTimeMillis()
    val record = ConversationRecord(
      id = UUID.randomUUID().toString(),
      title = title?.trim().takeUnless { it.isNullOrBlank() } ?: DEFAULT_CONVERSATION_TITLE,
      createdAt = now,
      updatedAt = now
    )
    val updated = mutateLocal { current ->
      val prepared = normalizeState(current)
      val activeId = prepared.activeConversationId.ifBlank { record.id }
      normalizeState(
        prepared.copy(
          activeConversationId = activeId,
          activeConversationUpdatedAt = if (prepared.activeConversationId.isBlank()) now else prepared.activeConversationUpdatedAt,
          conversations = listOf(record) + prepared.conversations
        )
      )
    }
    if (isInHostProcess()) {
      reportSyncResult(
        ModuleSyncServiceClient.upsertConversation(appContext, record) &&
          if (updated.activeConversationId == record.id) {
            ModuleSyncServiceClient.setActiveConversation(appContext, record.id)
          } else {
            true
          },
        "create_conversation"
      )
    }
    return record
  }

  suspend fun upsertConversation(record: ConversationRecord) {
    if (record.id.isBlank()) return
    val normalized = record.copy(
      title = record.title.trim().ifBlank { DEFAULT_CONVERSATION_TITLE },
      createdAt = record.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
      updatedAt = record.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis()
    )
    mutateLocal { current ->
      normalizeState(
        current.copy(
          conversations = listOf(normalized) + current.conversations.filterNot { it.id == normalized.id }
        )
      )
    }
    if (isInHostProcess()) {
      reportSyncResult(ModuleSyncServiceClient.upsertConversation(appContext, normalized), "upsert_conversation")
    }
  }

  suspend fun renameConversation(conversationId: String, title: String) {
    val normalizedTitle = title.trim()
    if (conversationId.isBlank() || normalizedTitle.isBlank()) return
    var updatedRecord: ConversationRecord? = null
    mutateLocal { current ->
      normalizeState(
        current.copy(
          conversations = current.conversations.map { conversation ->
            if (conversation.id == conversationId) {
              conversation.copy(title = normalizedTitle, updatedAt = System.currentTimeMillis()).also {
                updatedRecord = it
              }
            } else {
              conversation
            }
          }
        )
      )
    }
    val record = updatedRecord ?: return
    if (isInHostProcess()) {
      reportSyncResult(ModuleSyncServiceClient.upsertConversation(appContext, record), "rename_conversation")
    }
  }

  suspend fun setActiveConversation(conversationId: String) {
    if (conversationId.isBlank()) return
    val now = System.currentTimeMillis()
    mutateLocal { current ->
      if (current.conversations.none { it.id == conversationId }) {
        normalizeState(current)
      } else {
        normalizeState(
          current.copy(
            activeConversationId = conversationId,
            activeConversationUpdatedAt = now
          )
        )
      }
    }
    if (isInHostProcess()) {
      reportSyncResult(ModuleSyncServiceClient.setActiveConversation(appContext, conversationId), "set_active_conversation")
    }
  }

  suspend fun deleteConversation(conversationId: String) {
    if (conversationId.isBlank()) return
    val now = System.currentTimeMillis()
    mutateLocal { current ->
      val conversations = current.conversations.filterNot { it.id == conversationId }
      val messages = current.messages.filterNot { it.conversationId == conversationId }
      val nextActive = if (current.activeConversationId == conversationId) {
        conversations.maxByOrNull { it.updatedAt }?.id.orEmpty()
      } else {
        current.activeConversationId
      }
      normalizeState(
        current.copy(
          activeConversationId = nextActive,
          activeConversationUpdatedAt = if (current.activeConversationId == conversationId) now else current.activeConversationUpdatedAt,
          conversations = conversations,
          messages = messages,
          deletedConversationIds = (current.deletedConversationIds + conversationId).distinct()
        )
      )
    }
    if (isInHostProcess()) {
      reportSyncResult(ModuleSyncServiceClient.deleteConversation(appContext, conversationId), "delete_conversation")
    }
  }

  suspend fun clearMessages(conversationId: String) {
    if (conversationId.isBlank()) return
    var updatedRecord: ConversationRecord? = null
    mutateLocal { current ->
      val now = System.currentTimeMillis()
      normalizeState(
        current.copy(
          messages = current.messages.filterNot { it.conversationId == conversationId },
          conversations = current.conversations.map { conversation ->
            if (conversation.id == conversationId) {
              conversation.copy(updatedAt = now, messageCount = 0, lastMessagePreview = "").also {
                updatedRecord = it
              }
            } else {
              conversation
            }
          }
        )
      )
    }
    if (isInHostProcess()) {
      reportSyncResult(
        ModuleSyncServiceClient.clearConversationMessages(appContext, conversationId) &&
          (updatedRecord?.let { ModuleSyncServiceClient.upsertConversation(appContext, it) } ?: true),
        "clear_conversation_messages"
      )
    }
  }

  suspend fun recentMessages(limit: Int): List<ConversationMessageRecord> {
    val snapshot = stateSnapshot()
    val activeId = snapshot.activeConversationId
    if (activeId.isBlank()) return emptyList()
    return snapshot.messages
      .filter { it.conversationId == activeId && it.content.isNotBlank() }
      .sortedBy { it.createdAt }
      .takeLast(limit.coerceAtLeast(0))
  }

  suspend fun appendExchange(question: String, answer: String): ConversationRecord {
    val normalizedQuestion = question.trim()
    val normalizedAnswer = answer.trim()
    require(normalizedQuestion.isNotBlank()) { "question is blank" }
    require(normalizedAnswer.isNotBlank()) { "answer is blank" }

    lateinit var appendedMessages: List<ConversationMessageRecord>
    lateinit var activeConversation: ConversationRecord
    val now = System.currentTimeMillis()
    val updated = mutateLocal { current ->
      val prepared = normalizeState(current)
      activeConversation = prepared.activeConversation() ?: createDefaultConversation(now)
      appendedMessages = listOf(
        ConversationMessageRecord(
          id = UUID.randomUUID().toString(),
          conversationId = activeConversation.id,
          role = ConversationMessageRole.USER,
          content = normalizedQuestion,
          createdAt = now
        ),
        ConversationMessageRecord(
          id = UUID.randomUUID().toString(),
          conversationId = activeConversation.id,
          role = ConversationMessageRole.ASSISTANT,
          content = normalizedAnswer,
          createdAt = now + 1
        )
      )
      applyMessages(prepared, appendedMessages)
    }
    activeConversation = updated.conversations.first { it.id == activeConversation.id }

    if (isInHostProcess()) {
      reportSyncResult(
        ModuleSyncServiceClient.appendConversationMessages(appContext, appendedMessages) &&
          ModuleSyncServiceClient.setActiveConversation(appContext, activeConversation.id),
        "append_conversation_messages"
      )
    }
    return activeConversation
  }

  suspend fun appendMessages(messages: List<ConversationMessageRecord>) {
    val normalizedMessages = messages
      .mapNotNull { message ->
        val content = message.content.trim()
        val role = normalizeRole(message.role) ?: return@mapNotNull null
        val conversationId = message.conversationId.trim()
        if (conversationId.isBlank() || content.isBlank()) return@mapNotNull null
        message.copy(
          conversationId = conversationId,
          role = role,
          content = content,
          createdAt = message.createdAt.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
      }
    if (normalizedMessages.isEmpty()) return
    mutateLocal { current -> applyMessages(normalizeState(current), normalizedMessages) }
    if (isInHostProcess()) {
      reportSyncResult(ModuleSyncServiceClient.appendConversationMessages(appContext, normalizedMessages), "append_conversation_messages")
    }
  }

  private suspend fun mutateLocal(transform: (ConversationState) -> ConversationState): ConversationState {
    return mutex.withLock {
      val base = currentLocalStateLocked()
      val updated = normalizeState(transform(base))
      val snapshotJson = writeLocalState(updated)
      _state.value = updated
      ModuleRemoteStoreBridge.writeConversations(snapshotJson)
      updated
    }
  }

  private suspend fun currentLocalStateLocked(): ConversationState {
    if (_state.value.conversations.isEmpty() && file.exists()) {
      _state.value = readLocalState()
    } else if (_state.value.conversations.isEmpty()) {
      _state.value = normalizeState(_state.value)
    }
    return _state.value
  }

  private suspend fun readLocalState(): ConversationState = withContext(Dispatchers.IO) {
    val stored = if (file.exists()) {
      decodeState(file.readText())
    } else {
      ConversationState()
    }
    val migrated = if (!isInHostProcess() && !stored.migratedLegacyHistory) {
      migrateLegacyHistory(stored)
    } else {
      stored
    }
    normalizeState(migrated)
  }

  private suspend fun writeLocalState(state: ConversationState): String = withContext(Dispatchers.IO) {
    file.parentFile?.takeIf { !it.exists() }?.mkdirs()
    val snapshotJson = encodeState(state)
    file.writeText(snapshotJson)
    snapshotJson
  }

  private fun migrateLegacyHistory(state: ConversationState): ConversationState {
    if (state.deletedConversationIds.contains(LEGACY_IMPORT_CONVERSATION_ID)) {
      return state.copy(migratedLegacyHistory = true)
    }
    if (!legacyHistoryFile.exists()) {
      return state.copy(migratedLegacyHistory = true)
    }
    val legacyRecords = runCatching {
      json.decodeFromString(LegacyHistorySnapshot.serializer(), legacyHistoryFile.readText()).records
        .sortedBy { it.createdAt }
    }.getOrDefault(emptyList())
    if (legacyRecords.isEmpty()) {
      return state.copy(migratedLegacyHistory = true)
    }
    val now = System.currentTimeMillis()
    val conversationId = LEGACY_IMPORT_CONVERSATION_ID
    val importedMessages = legacyRecords.flatMap { record ->
      listOf(
        ConversationMessageRecord(
          id = "${record.sessionId}-user",
          conversationId = conversationId,
          role = ConversationMessageRole.USER,
          content = record.question,
          createdAt = record.createdAt
        ),
        ConversationMessageRecord(
          id = "${record.sessionId}-assistant",
          conversationId = conversationId,
          role = ConversationMessageRole.ASSISTANT,
          content = record.answer,
          createdAt = record.createdAt + 1
        )
      )
    }.filter { it.content.isNotBlank() }
    val latest = legacyRecords.maxOfOrNull { it.updatedAt } ?: now
    val importedConversation = ConversationRecord(
      id = conversationId,
      title = "历史导入",
      createdAt = legacyRecords.minOfOrNull { it.createdAt } ?: now,
      updatedAt = latest,
      messageCount = importedMessages.size,
      lastMessagePreview = importedMessages.maxByOrNull { it.createdAt }?.content.orEmpty().preview()
    )
    return state.copy(
      activeConversationId = state.activeConversationId.ifBlank { conversationId },
      activeConversationUpdatedAt = state.activeConversationUpdatedAt.takeIf { it > 0L } ?: latest,
      conversations = listOf(importedConversation) + state.conversations.filterNot { it.id == conversationId },
      messages = importedMessages + state.messages.filterNot { it.conversationId == conversationId },
      migratedLegacyHistory = true
    )
  }

  private fun applyMessages(
    state: ConversationState,
    appendedMessages: List<ConversationMessageRecord>
  ): ConversationState {
    val existingIds = state.messages.mapTo(HashSet()) { it.id }
    val mergedMessages = (state.messages + appendedMessages.filter { existingIds.add(it.id) })
      .sortedBy { it.createdAt }
      .takeLast(MAX_MESSAGES)
    val touchedConversationIds = appendedMessages.mapTo(HashSet()) { it.conversationId }
    val existingConversations = state.conversations.associateBy { it.id }
    val updatedConversations = (state.conversations + touchedConversationIds.mapNotNull { conversationId ->
      if (existingConversations.containsKey(conversationId)) return@mapNotNull null
      val firstMessage = appendedMessages.firstOrNull { it.conversationId == conversationId }
      ConversationRecord(
        id = conversationId,
        title = DEFAULT_CONVERSATION_TITLE,
        createdAt = firstMessage?.createdAt ?: System.currentTimeMillis(),
        updatedAt = firstMessage?.createdAt ?: System.currentTimeMillis()
      )
    }).distinctBy { it.id }.map { conversation ->
      if (!touchedConversationIds.contains(conversation.id)) return@map conversation
      val messages = mergedMessages.filter { it.conversationId == conversation.id }
      conversation.copy(
        title = conversation.title.ifBlank { DEFAULT_CONVERSATION_TITLE },
        updatedAt = messages.maxOfOrNull { it.createdAt } ?: conversation.updatedAt,
        messageCount = messages.size,
        lastMessagePreview = messages.maxByOrNull { it.createdAt }?.content.orEmpty().preview()
      )
    }
    val activeId = state.activeConversationId.ifBlank { appendedMessages.firstOrNull()?.conversationId.orEmpty() }
    return state.copy(
      activeConversationId = activeId,
      conversations = updatedConversations,
      messages = mergedMessages
    )
  }

  private fun normalizeState(raw: ConversationState): ConversationState {
    val deletedIds = raw.deletedConversationIds
      .map { it.trim() }
      .filter { it.isNotBlank() }
      .distinct()
      .take(MAX_DELETED_CONVERSATIONS)
    val deletedIdSet = deletedIds.toHashSet()
    val normalizedMessages = raw.messages
      .mapNotNull { message ->
        val role = normalizeRole(message.role) ?: return@mapNotNull null
        val content = message.content.trim()
        val conversationId = message.conversationId.trim()
        if (conversationId.isBlank() || content.isBlank()) return@mapNotNull null
        if (deletedIdSet.contains(conversationId)) return@mapNotNull null
        message.copy(conversationId = conversationId, role = role, content = content)
      }
      .distinctBy { it.id }
      .sortedBy { it.createdAt }
      .takeLast(MAX_MESSAGES)

    val messageConversationIds = normalizedMessages.mapTo(HashSet()) { it.conversationId }
    val conversationIds = raw.conversations
      .filterNot { deletedIdSet.contains(it.id) }
      .mapTo(HashSet()) { it.id }
    val generatedConversations = messageConversationIds
      .filterNot { conversationIds.contains(it) }
      .map { conversationId ->
        val messages = normalizedMessages.filter { it.conversationId == conversationId }
        createConversationFromMessages(conversationId, messages)
      }

    var conversations = (raw.conversations.filterNot { deletedIdSet.contains(it.id) } + generatedConversations)
      .filter { it.id.isNotBlank() }
      .distinctBy { it.id }
      .map { conversation ->
        val messages = normalizedMessages.filter { it.conversationId == conversation.id }
        if (messages.isEmpty()) {
          conversation.copy(
            title = conversation.title.ifBlank { DEFAULT_CONVERSATION_TITLE },
            messageCount = 0,
            lastMessagePreview = conversation.lastMessagePreview.preview()
          )
        } else {
          conversation.copy(
            title = conversation.title.ifBlank { DEFAULT_CONVERSATION_TITLE },
            updatedAt = maxOf(conversation.updatedAt, messages.maxOf { it.createdAt }),
            messageCount = messages.size,
            lastMessagePreview = messages.maxBy { it.createdAt }.content.preview()
          )
        }
      }
      .sortedByDescending { it.updatedAt }
      .take(MAX_CONVERSATIONS)

    if (conversations.isEmpty()) {
      conversations = listOf(createDefaultConversation(System.currentTimeMillis()))
    }
    val activeId = raw.activeConversationId.takeIf { candidate -> conversations.any { it.id == candidate } }
      ?: conversations.maxBy { it.updatedAt }.id
    val activeUpdatedAt = raw.activeConversationUpdatedAt.takeIf { it > 0L }
      ?: conversations.firstOrNull { it.id == activeId }?.updatedAt
      ?: System.currentTimeMillis()
    return raw.copy(
      activeConversationId = activeId,
      activeConversationUpdatedAt = activeUpdatedAt,
      conversations = conversations,
      messages = normalizedMessages,
      deletedConversationIds = deletedIds
    )
  }

  private fun createConversationFromMessages(
    conversationId: String,
    messages: List<ConversationMessageRecord>
  ): ConversationRecord {
    val now = System.currentTimeMillis()
    return ConversationRecord(
      id = conversationId,
      title = DEFAULT_CONVERSATION_TITLE,
      createdAt = messages.minOfOrNull { it.createdAt } ?: now,
      updatedAt = messages.maxOfOrNull { it.createdAt } ?: now,
      messageCount = messages.size,
      lastMessagePreview = messages.maxByOrNull { it.createdAt }?.content.orEmpty().preview()
    )
  }

  private fun createDefaultConversation(now: Long): ConversationRecord {
    return ConversationRecord(
      id = UUID.randomUUID().toString(),
      title = DEFAULT_CONVERSATION_TITLE,
      createdAt = now,
      updatedAt = now
    )
  }

  private fun ConversationState.activeConversation(): ConversationRecord? {
    return conversations.firstOrNull { it.id == activeConversationId }
  }

  private fun mergeStates(primary: ConversationState, secondary: ConversationState): ConversationState {
    val deletedIds = (primary.deletedConversationIds + secondary.deletedConversationIds)
      .map { it.trim() }
      .filter { it.isNotBlank() }
      .distinct()
      .take(MAX_DELETED_CONVERSATIONS)
    val deletedIdSet = deletedIds.toHashSet()
    val cleanedPrimary = primary.withoutDeletedConversations(deletedIdSet)
    val cleanedSecondary = secondary.withoutDeletedConversations(deletedIdSet)
    val conversations = (cleanedPrimary.conversations + cleanedSecondary.conversations)
      .sortedByDescending { it.updatedAt }
      .distinctBy { it.id }
    val messages = (cleanedPrimary.messages + cleanedSecondary.messages)
      .sortedBy { it.createdAt }
      .distinctBy { it.id }
    val activeId = resolveMergedActiveConversationId(cleanedPrimary, cleanedSecondary)
    val activeUpdatedAt = activeTimestampFor(cleanedPrimary, activeId)
      .coerceAtLeast(activeTimestampFor(cleanedSecondary, activeId))
    return normalizeState(
      ConversationState(
        activeConversationId = activeId,
        activeConversationUpdatedAt = activeUpdatedAt,
        conversations = conversations,
        messages = messages,
        migratedLegacyHistory = primary.migratedLegacyHistory || secondary.migratedLegacyHistory,
        deletedConversationIds = deletedIds
      )
    )
  }

  private fun resolveMergedActiveConversationId(
    primary: ConversationState,
    secondary: ConversationState
  ): String {
    val primaryActive = primary.conversations.firstOrNull { it.id == primary.activeConversationId }
    val secondaryActive = secondary.conversations.firstOrNull { it.id == secondary.activeConversationId }
    val primaryUpdatedAt = activeTimestampFor(primary, primary.activeConversationId)
    val secondaryUpdatedAt = activeTimestampFor(secondary, secondary.activeConversationId)
    return when {
      primaryActive == null -> secondary.activeConversationId
      secondaryActive == null -> primary.activeConversationId
      secondaryUpdatedAt > primaryUpdatedAt -> secondary.activeConversationId
      else -> primary.activeConversationId
    }
  }

  private fun ConversationState.withoutDeletedConversations(deletedIdSet: Set<String>): ConversationState {
    if (deletedIdSet.isEmpty()) return this
    val activeId = activeConversationId.takeUnless { deletedIdSet.contains(it) }.orEmpty()
    return copy(
      activeConversationId = activeId,
      activeConversationUpdatedAt = if (activeId.isBlank()) 0L else activeConversationUpdatedAt,
      conversations = conversations.filterNot { deletedIdSet.contains(it.id) },
      messages = messages.filterNot { deletedIdSet.contains(it.conversationId) }
    )
  }

  private fun activeTimestampFor(state: ConversationState, activeId: String): Long {
    if (activeId.isBlank()) return 0L
    return state.activeConversationUpdatedAt.takeIf { state.activeConversationId == activeId && it > 0L }
      ?: state.conversations.firstOrNull { it.id == activeId }?.updatedAt
      ?: 0L
  }

  private fun ConversationState.hasConversationData(): Boolean {
    return conversations.isNotEmpty() || messages.isNotEmpty()
  }

  private fun normalizeRole(role: String): String? {
    return when (role.trim().lowercase()) {
      ConversationMessageRole.USER -> ConversationMessageRole.USER
      ConversationMessageRole.ASSISTANT -> ConversationMessageRole.ASSISTANT
      else -> null
    }
  }

  private fun isInHostProcess(): Boolean {
    return runCatching { StartupInfo.isInHostProcess() }.getOrDefault(false)
  }

  private fun reportSyncResult(success: Boolean, action: String) {
    if (!success) {
      StartupInfo.log("Module conversation sync best-effort failed action=$action")
    }
  }

  companion object {
    const val REMOTE_CONVERSATIONS_FILE = "assistant-conversations.json"
    const val LEGACY_IMPORT_CONVERSATION_ID = "__legacy_history_import__"
    private const val DEFAULT_CONVERSATION_TITLE = "默认会话"
    private const val MAX_CONVERSATIONS = 80
    private const val MAX_MESSAGES = 800
    private const val MAX_DELETED_CONVERSATIONS = 200

    private val json = Json {
      ignoreUnknownKeys = true
      encodeDefaults = true
      prettyPrint = true
    }

    suspend fun syncLocalToRemote(context: Context): Boolean = withContext(Dispatchers.IO) {
      val file = File(context.applicationContext.filesDir, REMOTE_CONVERSATIONS_FILE)
      val snapshotJson = if (file.exists()) file.readText() else encodeState(ConversationState())
      ModuleRemoteStoreBridge.writeConversations(snapshotJson)
    }

    private fun encodeState(state: ConversationState): String {
      return json.encodeToString(ConversationState.serializer(), state)
    }

    private fun decodeState(snapshotJson: String): ConversationState {
      return runCatching {
        json.decodeFromString(ConversationState.serializer(), snapshotJson)
      }.getOrDefault(ConversationState())
    }

    private fun readRemoteState(): ConversationState {
      val module = StartupInfo.getModule()
      if (module == null) {
        StartupInfo.log("Remote conversations unavailable because module entry is null")
        return ConversationState()
      }
      return runCatching {
        val descriptor = module.openRemoteFile(REMOTE_CONVERSATIONS_FILE)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
          decodeState(reader.readText())
        }
      }.onFailure {
        StartupInfo.log("Remote conversations read failed: ${it.message ?: it.javaClass.simpleName}")
      }.getOrDefault(ConversationState())
    }

    private fun String.preview(maxLength: Int = 80): String {
      val normalized = replace('\n', ' ').trim()
      return if (normalized.length <= maxLength) normalized else normalized.take(maxLength - 3) + "..."
    }
  }
}

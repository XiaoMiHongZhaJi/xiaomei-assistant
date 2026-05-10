package com.xiaomei.assistant.runtime

import android.content.Context
import com.xiaomei.assistant.data.AppConfigRepository
import com.xiaomei.assistant.data.ConversationRepository
import com.xiaomei.assistant.data.MemoryRepository
import com.xiaomei.assistant.data.SessionHistoryRepository
import com.xiaomei.assistant.llm.OpenAiCompatClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class RuntimeContainer(context: Context) {
  private val appContext = context.applicationContext

  val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  val configRepository = AppConfigRepository(appContext, appScope)
  val historyRepository = SessionHistoryRepository(appContext, appScope)
  val conversationRepository = ConversationRepository(appContext, appScope)
  val memoryRepository = MemoryRepository(appContext, appScope)
  val llmClient = OpenAiCompatClient()
}


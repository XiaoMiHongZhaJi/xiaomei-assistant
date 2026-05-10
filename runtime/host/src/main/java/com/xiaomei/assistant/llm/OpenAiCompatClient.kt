package com.xiaomei.assistant.llm

import com.xiaomei.assistant.model.LlmConfig
import com.xiaomei.assistant.model.LlmApiMode
import com.xiaomei.assistant.model.LlmProvider
import com.xiaomei.assistant.model.MemoryRecord
import com.xiaomei.assistant.model.MemoryToolAction
import com.xiaomei.assistant.model.ConversationMessageRecord
import com.xiaomei.assistant.model.ConversationMessageRole
import com.xiaomei.assistant.runtime.StartupInfo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDate

@Serializable
data class ChatMessage(
  val role: String,
  val content: String? = null,
  @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null,
  @SerialName("tool_call_id") val toolCallId: String? = null
)

@Serializable
data class OpenAiChatRequest(
  val model: String,
  val messages: List<ChatMessage>,
  val temperature: Float? = null,
  @SerialName("max_tokens") val maxTokens: Int? = null,
  @SerialName("max_completion_tokens") val maxCompletionTokens: Int? = null,
  val tools: List<OpenAiTool>? = null,
  @SerialName("tool_choice") val toolChoice: JsonElement? = null
)

@Serializable
data class OpenAiTool(
  val type: String = "function",
  val function: OpenAiToolFunction
)

@Serializable
data class OpenAiToolFunction(
  val name: String,
  val description: String,
  val parameters: JsonObject
)

@Serializable
data class OpenAiToolCall(
  val id: String,
  val type: String = "function",
  val function: OpenAiToolCallFunction
)

@Serializable
data class OpenAiToolCallFunction(
  val name: String,
  val arguments: String
)

@Serializable
data class OpenAiChatResponse(
  val choices: List<OpenAiChoice> = emptyList()
)

@Serializable
data class OpenAiChoice(
  val message: ChatMessage
)

@Serializable
private data class MemoryExtractionPayload(
  val actions: List<MemoryToolAction> = emptyList()
)

data class LlmCompletionResult(
  val answer: String,
  val summary: String
)

@OptIn(ExperimentalSerializationApi::class)
class OpenAiCompatClient(
  private val client: OkHttpClient = OkHttpClient(),
  private val json: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
  }
) {
  suspend fun probe(config: LlmConfig): String {
    val result = complete(
      config = config,
      messages = emptyList(),
      memories = emptyList(),
      userMessage = "请只回复“LLM连接正常”，不要补充其它内容。"
    )
    return result.answer
  }

  suspend fun complete(
    config: LlmConfig,
    messages: List<ConversationMessageRecord>,
    memories: List<MemoryRecord> = emptyList(),
    userMessage: String
  ): LlmCompletionResult = withContext(Dispatchers.IO) {
    val answer = if (usesOpenAiResponses(config)) {
      executeResponsesRequest(config, buildResponsesRequest(config, messages, memories, userMessage))
    } else {
      val requestBody = buildRequest(config, messages, memories, userMessage)
      val payload = executeChatRequest(config, requestBody)
      payload.choices.firstOrNull()?.message?.content?.trim().orEmpty()
    }
    if (answer.isBlank()) {
      throw IOException("LLM returned an empty answer")
    }
    LlmCompletionResult(answer = answer, summary = summarize(answer))
  }

  suspend fun extractMemoryActions(
    config: LlmConfig,
    question: String,
    answer: String,
    existingMemories: List<MemoryRecord>
  ): List<MemoryToolAction> = withContext(Dispatchers.IO) {
    if (question.isBlank() || answer.isBlank()) return@withContext emptyList()
    when {
      usesOpenAiResponses(config) -> runCatching {
        parseResponsesToolActions(
          executeResponsesJsonRequest(config, buildResponsesMemoryToolRequest(config, question, answer, existingMemories))
        )
      }.getOrElse {
        parseFallbackActionsFromText(
          parseResponsesText(
            executeResponsesJsonRequest(config, buildResponsesMemoryFallbackRequest(config, question, answer, existingMemories))
          )
        )
      }
      usesOpenAiChat(config) -> {
        val toolRequest = buildMemoryToolRequest(config, question, answer, existingMemories)
        runCatching {
          parseToolActions(executeChatRequest(config, toolRequest))
        }.getOrElse {
          parseFallbackActions(executeChatRequest(config, buildMemoryFallbackRequest(config, question, answer, existingMemories)))
        }
      }
      else -> parseFallbackActions(
        executeChatRequest(config, buildMemoryFallbackRequest(config, question, answer, existingMemories))
      )
    }
  }

  fun buildRequest(
    config: LlmConfig,
    messages: List<ConversationMessageRecord>,
    memories: List<MemoryRecord> = emptyList(),
    userMessage: String
  ): OpenAiChatRequest {
    val chatMessages = buildList {
      add(ChatMessage(role = "system", content = buildSystemPrompt(config, memories)))
      messages
        .filter { it.content.isNotBlank() && (it.role == ConversationMessageRole.USER || it.role == ConversationMessageRole.ASSISTANT) }
        .sortedBy { it.createdAt }
        .forEach { item ->
          add(ChatMessage(role = item.role, content = item.content))
      }
      add(ChatMessage(role = "user", content = userMessage))
    }
    return OpenAiChatRequest(
      model = config.model,
      messages = chatMessages,
      temperature = config.temperature.takeIf { isModelAllowTemperature(config.model) },
      maxTokens = if (usesMimoChat(config)) null else config.maxTokens,
      maxCompletionTokens = if (usesMimoChat(config)) config.maxTokens else null
    )
  }

  fun buildResponsesRequest(
    config: LlmConfig,
    messages: List<ConversationMessageRecord>,
    memories: List<MemoryRecord> = emptyList(),
    userMessage: String
  ): JsonObject {
    val input = buildJsonArray {
      messages
        .filter { it.content.isNotBlank() && (it.role == ConversationMessageRole.USER || it.role == ConversationMessageRole.ASSISTANT) }
        .sortedBy { it.createdAt }
        .forEach { item ->
          add(responsesContentItem(item.role, item.content))
        }
      add(responsesContentItem("user", userMessage))
    }
    return buildResponsesRequestBody(
      config = config,
      input = input,
      instructions = buildSystemPrompt(config, memories),
      maxTokens = config.maxTokens,
      temperature = config.temperature
    )
  }

  private fun buildMemoryToolRequest(
    config: LlmConfig,
    question: String,
    answer: String,
    existingMemories: List<MemoryRecord>
  ): OpenAiChatRequest {
    return OpenAiChatRequest(
      model = config.model,
      messages = listOf(
        ChatMessage(role = "system", content = memoryToolSystemPrompt(existingMemories)),
        ChatMessage(role = "user", content = memoryExtractionUserPrompt(question, answer, existingMemories))
      ),
      temperature = 0.1f.takeIf { isModelAllowTemperature(config.model) },
      maxTokens = 512,
      tools = listOf(memoryTool()),
      toolChoice = JsonPrimitive("auto")
    )
  }

  private fun buildResponsesMemoryToolRequest(
    config: LlmConfig,
    question: String,
    answer: String,
    existingMemories: List<MemoryRecord>
  ): JsonObject {
    return buildResponsesRequestBody(
      config = config,
      input = buildJsonArray {
        add(responsesContentItem("user", memoryExtractionUserPrompt(question, answer, existingMemories)))
      },
      instructions = memoryToolSystemPrompt(existingMemories),
      maxTokens = 512,
      temperature = 0.1f,
      tools = listOf(memoryToolForResponses())
    )
  }

  private fun buildResponsesMemoryFallbackRequest(
    config: LlmConfig,
    question: String,
    answer: String,
    existingMemories: List<MemoryRecord>
  ): JsonObject {
    return buildResponsesRequestBody(
      config = config,
      input = buildJsonArray {
        add(
          responsesContentItem(
            "user",
            memoryExtractionUserPrompt(question, answer, existingMemories) +
              "\n输出格式：{\"actions\":[{\"action\":\"create\",\"content\":\"...\"}]}"
          )
        )
      },
      instructions = memoryToolSystemPrompt(existingMemories) + "\n你必须只输出 JSON，不要输出 Markdown。",
      maxTokens = 512,
      temperature = 0.1f
    )
  }

  private fun buildMemoryFallbackRequest(
    config: LlmConfig,
    question: String,
    answer: String,
    existingMemories: List<MemoryRecord>
  ): OpenAiChatRequest {
    return OpenAiChatRequest(
      model = config.model,
      messages = listOf(
        ChatMessage(role = "system", content = memoryToolSystemPrompt(existingMemories) + "\n你必须只输出 JSON，不要输出 Markdown。"),
        ChatMessage(role = "user", content = memoryExtractionUserPrompt(question, answer, existingMemories) + "\n输出格式：{\"actions\":[{\"action\":\"create\",\"content\":\"...\"}]}")
      ),
      temperature = 0.1f.takeIf { isModelAllowTemperature(config.model) },
      maxTokens = if (usesMimoChat(config)) null else 512,
      maxCompletionTokens = if (usesMimoChat(config)) 512 else null
    )
  }

  private fun executeChatRequest(config: LlmConfig, requestBody: OpenAiChatRequest): OpenAiChatResponse {
    val url = chatCompletionsUrl(config.baseUrl)
    logRequestShape(config, url, listOfNotNull(
      "model",
      "messages",
      requestBody.temperature?.let { "temperature" },
      requestBody.maxTokens?.let { "max_tokens" },
      requestBody.maxCompletionTokens?.let { "max_completion_tokens" },
      requestBody.tools?.let { "tools" },
      requestBody.toolChoice?.let { "tool_choice" }
    ))
    val requestBuilder = Request.Builder()
      .url(url)
      .header("Content-Type", "application/json")
      .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))

    applyAuthHeader(requestBuilder, config)

    val response = client.newCall(requestBuilder.build()).execute()
    if (!response.isSuccessful) {
      throw IOException("HTTP ${response.code}: ${response.body?.string().orEmpty()}")
    }
    val body = response.body?.string().orEmpty()
    return json.decodeFromString<OpenAiChatResponse>(body)
  }

  private fun executeResponsesRequest(config: LlmConfig, requestBody: JsonObject): String {
    return parseResponsesText(executeResponsesJsonRequest(config, requestBody))
  }

  private fun executeResponsesJsonRequest(config: LlmConfig, requestBody: JsonObject): JsonObject {
    val url = responsesUrl(config.baseUrl)
    logRequestShape(config, url, requestBody.keys.sorted())
    val requestBuilder = Request.Builder()
      .url(url)
      .header("Content-Type", "application/json")
      .post(json.encodeToString(requestBody).toRequestBody("application/json".toMediaType()))

    applyAuthHeader(requestBuilder, config)

    val response = client.newCall(requestBuilder.build()).execute()
    if (!response.isSuccessful) {
      throw IOException("HTTP ${response.code}: ${response.body?.string().orEmpty()}")
    }
    val body = response.body?.string().orEmpty()
    return json.parseToJsonElement(body).jsonObject
  }

  private fun applyAuthHeader(requestBuilder: Request.Builder, config: LlmConfig) {
    if (config.apiKey.isBlank()) return
    if (usesMimoChat(config)) {
      requestBuilder.header("api-key", config.apiKey)
    } else {
      requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
    }
  }

  private fun parseToolActions(response: OpenAiChatResponse): List<MemoryToolAction> {
    return response.choices.firstOrNull()?.message?.toolCalls.orEmpty()
      .filter { it.function.name == MEMORY_TOOL_NAME }
      .mapNotNull { call ->
        runCatching { json.decodeFromString(MemoryToolAction.serializer(), call.function.arguments) }.getOrNull()
      }
  }

  private fun parseFallbackActions(response: OpenAiChatResponse): List<MemoryToolAction> {
    val content = response.choices.firstOrNull()?.message?.content?.trim().orEmpty()
    return parseFallbackActionsFromText(content)
  }

  private fun parseFallbackActionsFromText(content: String): List<MemoryToolAction> {
    if (content.isBlank()) return emptyList()
    val normalized = content.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
    return runCatching { json.decodeFromString<MemoryExtractionPayload>(normalized).actions }
      .getOrElse {
        runCatching { listOf(json.decodeFromString(MemoryToolAction.serializer(), normalized)) }.getOrDefault(emptyList())
      }
  }

  private fun parseResponsesText(response: JsonObject): String {
    response["output_text"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
    val output = response["output"]?.jsonArray ?: return ""
    val text = StringBuilder()
    output.forEach { item ->
      val itemObject = item.jsonObject
      if (itemObject["type"]?.jsonPrimitive?.contentOrNull != "message") return@forEach
      val content = itemObject["content"]?.jsonArray
      if (content != null) {
        content.forEach { contentItem ->
          val contentObject = contentItem.jsonObject
          if (contentObject["type"]?.jsonPrimitive?.contentOrNull == "output_text") {
            text.append(contentObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty())
          }
        }
        text.append('\n')
      }
    }
    return text.toString().trim()
  }

  private fun parseResponsesToolActions(response: JsonObject): List<MemoryToolAction> {
    val output = response["output"]?.jsonArray ?: return emptyList()
    return output.mapNotNull { item ->
      val itemObject = item.jsonObject
      val type = itemObject["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
      val name = itemObject["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
      val arguments = itemObject["arguments"]?.jsonPrimitive?.contentOrNull.orEmpty()
      if (type != "function_call" || name != MEMORY_TOOL_NAME || arguments.isBlank()) return@mapNotNull null
      runCatching { json.decodeFromString(MemoryToolAction.serializer(), arguments) }.getOrNull()
    }
  }

  private fun memoryTool(): OpenAiTool {
    return OpenAiTool(
      function = OpenAiToolFunction(
        name = MEMORY_TOOL_NAME,
        description = memoryToolDescription(),
        parameters = memoryToolParameters()
      )
    )
  }

  private fun memoryToolForResponses(): JsonObject {
    return buildJsonObject {
      put("type", "function")
      put("name", MEMORY_TOOL_NAME)
      put("description", memoryToolDescription())
      put("parameters", memoryToolParameters())
    }
  }

  private fun memoryToolParameters(): JsonObject {
    return buildJsonObject {
      put("type", "object")
      put("properties", buildJsonObject {
        put("action", buildJsonObject {
          put("type", "string")
          putJsonArray("enum") {
            add(JsonPrimitive("create"))
            add(JsonPrimitive("edit"))
            add(JsonPrimitive("delete"))
          }
          put("description", "Operation to perform: create, edit, or delete")
        })
        put("id", buildJsonObject {
          put("type", "string")
          put("description", "The id of the memory record, required for edit/delete")
        })
        put("content", buildJsonObject {
          put("type", "string")
          put("description", "The memory content, required for create/edit")
        })
      })
      putJsonArray("required") { add(JsonPrimitive("action")) }
      put("additionalProperties", JsonPrimitive(false))
    }
  }

  private fun memoryToolSystemPrompt(existingMemories: List<MemoryRecord>): String {
    val existing = existingMemories.take(30).joinToString("\n") { "- id=${it.id}: ${it.content}" }.ifBlank { "无" }
    return memoryToolDescription() + "\n\nExisting memories:\n$existing"
  }

  private fun memoryToolDescription(): String {
    return """
      The memory tool stores long-term information across conversations.
      Use action to control the operation: create, edit, delete.
      No relevant record: create + content.
      Existing relevant record: edit + id + content.
      Outdated or irrelevant record: delete + id.
      Memories will automatically appear in the <memories> tag in later conversations.
      Do not store sensitive information such as ethnicity, religion, sexual orientation, political views, sex life, criminal records, health identifiers, or financial secrets.
      You may store preferred name, language, response style, stable preferences, plans, work-related notes, device/module preferences, and durable facts useful later.
      Do not store one-off requests, temporary tasks, or facts only relevant to the current answer.
      Similar memories should be merged; prefer editing existing records.
      Do not mention memory operations to the user unless explicitly asked.
      Today is ${LocalDate.now()}.
    """.trimIndent()
  }

  private fun memoryExtractionUserPrompt(question: String, answer: String, existingMemories: List<MemoryRecord>): String {
    val existing = existingMemories.take(30).joinToString("\n") { "- id=${it.id}: ${it.content}" }.ifBlank { "无" }
    return """
      Analyze this finished assistant interaction and maintain long-term memories.

      Existing memories:
      $existing

      User question:
      $question

      Assistant answer shown to user:
      $answer
    """.trimIndent()
  }

  private fun buildSystemPrompt(config: LlmConfig, memories: List<MemoryRecord>): String {
    val enabled = memories.filter { it.enabled && it.content.isNotBlank() }.take(20)
    if (enabled.isEmpty()) return config.systemPrompt
    val memoryText = enabled.joinToString("\n") { record -> "- ${record.content.trim()}" }
    return config.systemPrompt.trimEnd() +
      "\n\n<memories>\n" + memoryText +
      "\n</memories>\n请把 <memories> 作为长期用户偏好与事实背景使用；除非用户询问，不要直接说明你读取了记忆。"
  }

  private fun buildResponsesRequestBody(
    config: LlmConfig,
    input: JsonArray,
    instructions: String,
    maxTokens: Int,
    temperature: Float,
    tools: List<JsonObject>? = null
  ): JsonObject {
    return buildJsonObject {
      put("model", config.model)
      put("stream", false)
      put("store", false)
      if (isModelAllowTemperature(config.model)) {
        put("temperature", temperature)
      }
      put("max_output_tokens", maxTokens)
      if (instructions.isNotBlank()) {
        put("instructions", instructions)
      }
      put("input", input)
      if (!tools.isNullOrEmpty()) {
        putJsonArray("tools") {
          tools.forEach(::add)
        }
      }
    }
  }

  private fun responsesContentItem(role: String, content: String): JsonObject {
    return buildJsonObject {
      put("role", role)
      put("content", content)
    }
  }

  private fun logRequestShape(config: LlmConfig, url: String, bodyKeys: Collection<String>) {
    StartupInfo.log(
      "LLM request provider=${LlmProvider.normalize(config.provider)} " +
        "apiMode=${LlmApiMode.normalize(config.provider, config.apiMode)} " +
        "url=$url model=${config.model} bodyKeys=${bodyKeys.joinToString(",")}"
    )
  }

  fun chatCompletionsUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return if (trimmed.endsWith("/v1")) "$trimmed/chat/completions" else "$trimmed/v1/chat/completions"
  }

  fun responsesUrl(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    return if (trimmed.endsWith("/v1")) "$trimmed/responses" else "$trimmed/v1/responses"
  }

  private fun usesOpenAiChat(config: LlmConfig): Boolean {
    return LlmProvider.normalize(config.provider) == LlmProvider.OPENAI &&
      LlmApiMode.normalize(config.provider, config.apiMode) == LlmApiMode.OPENAI_CHAT_COMPLETIONS
  }

  private fun usesOpenAiResponses(config: LlmConfig): Boolean {
    return LlmProvider.normalize(config.provider) == LlmProvider.OPENAI &&
      LlmApiMode.normalize(config.provider, config.apiMode) == LlmApiMode.OPENAI_RESPONSES
  }

  private fun usesMimoChat(config: LlmConfig): Boolean {
    return LlmProvider.normalize(config.provider) == LlmProvider.XIAOMI_MIMO
  }

  private fun isModelAllowTemperature(model: String): Boolean {
    val normalized = model.trim().lowercase()
    return !normalized.startsWith("gpt-5") && !normalized.startsWith("o")
  }

  private fun summarize(answer: String): String {
    val normalized = answer.replace('\n', ' ').trim()
    return if (normalized.length <= 96) normalized else normalized.take(93) + "..."
  }

  private companion object {
    const val MEMORY_TOOL_NAME = "memory_tool"
  }
}

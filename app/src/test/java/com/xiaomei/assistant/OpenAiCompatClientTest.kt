package com.xiaomei.assistant

import com.google.common.truth.Truth.assertThat
import com.xiaomei.assistant.llm.OpenAiCompatClient
import com.xiaomei.assistant.model.ConversationMessageRecord
import com.xiaomei.assistant.model.ConversationMessageRole
import com.xiaomei.assistant.model.LlmApiMode
import com.xiaomei.assistant.model.LlmConfig
import com.xiaomei.assistant.model.LlmProvider
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class OpenAiCompatClientTest {
  private val client = OpenAiCompatClient()

  @Test
  fun `chat completion url appends v1 path when needed`() {
    assertThat(client.chatCompletionsUrl("https://example.com")).isEqualTo(
      "https://example.com/v1/chat/completions"
    )
    assertThat(client.chatCompletionsUrl("https://example.com/v1/")).isEqualTo(
      "https://example.com/v1/chat/completions"
    )
  }

  @Test
  fun `responses url appends v1 path when needed`() {
    assertThat(client.responsesUrl("https://example.com")).isEqualTo(
      "https://example.com/v1/responses"
    )
    assertThat(client.responsesUrl("https://example.com/v1/")).isEqualTo(
      "https://example.com/v1/responses"
    )
  }

  @Test
  fun `build request includes system prompt history and user message`() {
    val config = LlmConfig(systemPrompt = "system prompt")
    val request = client.buildRequest(
      config = config,
      messages = listOf(
        ConversationMessageRecord(
          id = "1",
          conversationId = "conversation-1",
          role = ConversationMessageRole.USER,
          content = "你好",
          createdAt = 1L
        ),
        ConversationMessageRecord(
          id = "2",
          conversationId = "conversation-1",
          role = ConversationMessageRole.ASSISTANT,
          content = "你好，我是小美",
          createdAt = 2L
        )
      ),
      userMessage = "今天天气如何"
    )

    assertThat(request.messages.map { it.role }).containsExactly(
      "system",
      "user",
      "assistant",
      "user"
    ).inOrder()
    assertThat(request.messages.last().content).isEqualTo("今天天气如何")
  }

  @Test
  fun `mimo chat request uses max completion tokens field`() {
    val request = client.buildRequest(
      config = LlmConfig(
        provider = LlmProvider.XIAOMI_MIMO,
        apiMode = LlmApiMode.MIMO_CHAT_COMPLETIONS,
        maxTokens = 256
      ),
      messages = emptyList(),
      userMessage = "你好"
    )

    assertThat(request.maxTokens).isNull()
    assertThat(request.maxCompletionTokens).isEqualTo(256)
  }

  @Test
  fun `openai chat request uses max tokens field`() {
    val request = client.buildRequest(
      config = LlmConfig(
        provider = LlmProvider.OPENAI,
        apiMode = LlmApiMode.OPENAI_CHAT_COMPLETIONS,
        maxTokens = 256
      ),
      messages = emptyList(),
      userMessage = "你好"
    )

    assertThat(request.maxTokens).isEqualTo(256)
    assertThat(request.maxCompletionTokens).isNull()
  }

  @Test
  fun `openai responses request uses max output tokens and instructions`() {
    val request = client.buildResponsesRequest(
      config = LlmConfig(
        provider = LlmProvider.OPENAI,
        apiMode = LlmApiMode.OPENAI_RESPONSES,
        systemPrompt = "system prompt",
        maxTokens = 256
      ),
      messages = emptyList(),
      userMessage = "你好"
    )

    assertThat(request["instructions"]?.jsonPrimitive?.content).isEqualTo("system prompt")
    assertThat(request["max_output_tokens"]?.jsonPrimitive?.int).isEqualTo(256)
    assertThat(request["stream"]?.jsonPrimitive?.boolean).isFalse()
    assertThat(request["store"]?.jsonPrimitive?.boolean).isFalse()
    val input = request["input"] as JsonArray
    assertThat(input.last().jsonObject["content"]?.jsonPrimitive?.content).isEqualTo("你好")
  }

  @Test
  fun `gpt 5 chat request omits temperature`() {
    val request = client.buildRequest(
      config = LlmConfig(
        provider = LlmProvider.OPENAI,
        apiMode = LlmApiMode.OPENAI_CHAT_COMPLETIONS,
        model = "gpt-5-mini",
        temperature = 0.7f
      ),
      messages = emptyList(),
      userMessage = "你好"
    )

    assertThat(request.temperature).isNull()
  }

  @Test
  fun `o series responses request omits temperature`() {
    val request = client.buildResponsesRequest(
      config = LlmConfig(
        provider = LlmProvider.OPENAI,
        apiMode = LlmApiMode.OPENAI_RESPONSES,
        model = "o3-mini",
        temperature = 0.7f
      ),
      messages = emptyList(),
      userMessage = "你好"
    )

    assertThat(request.containsKey("temperature")).isFalse()
  }
}

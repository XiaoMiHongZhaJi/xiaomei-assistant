package com.xiaomei.assistant

import com.google.common.truth.Truth.assertThat
import com.xiaomei.assistant.model.AivsAsrBlacklistMatcher
import com.xiaomei.assistant.model.AivsAsrBlacklistRule
import com.xiaomei.assistant.model.LlmConfig
import org.junit.Test

class AivsAsrBlacklistMatcherTest {
  @Test
  fun `keyword contains matches asr text`() {
    val rule = rule(pattern = "天气")
    val config = LlmConfig(asrBlacklistEnabled = true, asrBlacklistRules = listOf(rule))

    assertThat(AivsAsrBlacklistMatcher.firstMatch("今天北京天气怎么样", config)).isEqualTo(rule)
  }

  @Test
  fun `keyword exact requires trimmed full text`() {
    val rule = rule(pattern = "打开空调", contains = false)
    val config = LlmConfig(asrBlacklistEnabled = true, asrBlacklistRules = listOf(rule))

    assertThat(AivsAsrBlacklistMatcher.firstMatch(" 打开空调 ", config)).isEqualTo(rule)
    assertThat(AivsAsrBlacklistMatcher.firstMatch("帮我打开空调", config)).isNull()
  }

  @Test
  fun `regex contains and exact use selected mode`() {
    val containsRule = rule(pattern = "第\\d+个闹钟", regex = true, contains = true)
    val exactRule = rule(pattern = "第\\d+个闹钟", regex = true, contains = false)

    assertThat(AivsAsrBlacklistMatcher.matches("关闭第2个闹钟", containsRule)).isTrue()
    assertThat(AivsAsrBlacklistMatcher.matches("关闭第2个闹钟", exactRule)).isFalse()
    assertThat(AivsAsrBlacklistMatcher.matches("第2个闹钟", exactRule)).isTrue()
  }

  @Test
  fun `disabled switch rules and invalid regex do not match`() {
    val disabledConfig = LlmConfig(
      asrBlacklistEnabled = false,
      asrBlacklistRules = listOf(rule(pattern = "天气"))
    )
    val invalidRegexRule = rule(pattern = "(", regex = true)

    assertThat(AivsAsrBlacklistMatcher.firstMatch("天气", disabledConfig)).isNull()
    assertThat(AivsAsrBlacklistMatcher.matches("天气", invalidRegexRule)).isFalse()
    assertThat(AivsAsrBlacklistMatcher.validate("(", regex = true)).isNotNull()
  }

  private fun rule(
    pattern: String,
    enabled: Boolean = true,
    regex: Boolean = false,
    contains: Boolean = true
  ): AivsAsrBlacklistRule {
    return AivsAsrBlacklistRule(
      id = pattern,
      pattern = pattern,
      enabled = enabled,
      regex = regex,
      contains = contains,
      createdAt = 1L,
      updatedAt = 1L
    )
  }
}

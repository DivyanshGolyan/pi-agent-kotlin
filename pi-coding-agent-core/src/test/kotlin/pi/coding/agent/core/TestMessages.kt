package pi.coding.agent.core

import pi.ai.core.AssistantMessage
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.Usage
import pi.ai.core.UsageCost
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent

internal fun userMsg(
    text: String,
    timestamp: Long = 1L,
): UserMessage = UserMessage(content = UserMessageContent.Text(text), timestamp = timestamp)

internal fun assistantMsg(
    text: String,
    timestamp: Long = 2L,
): AssistantMessage =
    AssistantMessage(
        content = mutableListOf(TextContent(text)),
        api = "anthropic-messages",
        provider = "anthropic",
        model = "test",
        usage =
            Usage(
                input = 1,
                output = 1,
                cacheRead = 0,
                cacheWrite = 0,
                totalTokens = 2,
                cost = UsageCost(),
            ),
        stopReason = StopReason.STOP,
        timestamp = timestamp,
    )

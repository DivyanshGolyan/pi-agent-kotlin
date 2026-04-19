package pi.agent.core

import pi.ai.core.AssistantMessage
import pi.ai.core.Message
import pi.ai.core.ToolResultMessage
import pi.ai.core.UserMessage

/**
 * Marker interface for app-specific agent messages.
 *
 * Kotlin does not have TypeScript-style declaration merging, so custom agent
 * messages should implement this interface directly. Because it extends
 * [Message], custom messages can live in [AgentMessage] collections alongside
 * the base pi-ai message types.
 */
public interface CustomAgentMessage : Message

/**
 * Converts one custom agent message into an LLM-compatible message, or returns
 * null when the message should be excluded from LLM context.
 */
public typealias CustomMessageToLlm = suspend (CustomAgentMessage) -> Message?

/**
 * Converts a single [AgentMessage] into an LLM-compatible [Message].
 *
 * Base pi-ai messages pass through unchanged. Custom messages are delegated to
 * [customMessageToLlm] when provided, otherwise they are filtered out.
 */
public suspend fun defaultMessageToLlm(
    message: AgentMessage,
    customMessageToLlm: CustomMessageToLlm? = null,
): Message? =
    when (message) {
        is UserMessage,
        is AssistantMessage,
        is ToolResultMessage,
        -> message
        is CustomAgentMessage -> customMessageToLlm?.invoke(message)
        else -> null
    }

/**
 * Default AgentMessage-to-LLM conversion used by [Agent].
 *
 * This mirrors the upstream separation between `AgentMessage` and LLM messages:
 * base LLM messages pass through unchanged, while app-specific messages must be
 * explicitly converted or are excluded from the LLM context.
 */
public suspend fun defaultConvertToLlm(
    messages: List<AgentMessage>,
    customMessageToLlm: CustomMessageToLlm? = null,
): List<Message> {
    val llmMessages = mutableListOf<Message>()
    for (message in messages) {
        val converted = defaultMessageToLlm(message, customMessageToLlm)
        if (converted != null) {
            llmMessages += converted
        }
    }
    return llmMessages
}

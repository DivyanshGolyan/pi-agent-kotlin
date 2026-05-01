package pi.ai.core.providers

import pi.ai.core.AssistantContentBlock
import pi.ai.core.AssistantMessage
import pi.ai.core.ImageContent
import pi.ai.core.InputModality
import pi.ai.core.Message
import pi.ai.core.Model
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.ThinkingContent
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultContentPart
import pi.ai.core.ToolResultMessage
import pi.ai.core.UserContentPart
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent

internal const val NON_VISION_USER_IMAGE_PLACEHOLDER = "(image omitted: model does not support images)"
internal const val NON_VISION_TOOL_IMAGE_PLACEHOLDER = "(tool image omitted: model does not support images)"

internal fun downgradeUnsupportedImages(
    messages: List<Message>,
    model: Model<String>,
): List<Message> {
    if (model.input.contains(InputModality.IMAGE)) {
        return messages
    }

    return messages.map { message ->
        when (message) {
            is UserMessage ->
                when (val content = message.content) {
                    is UserMessageContent.Text -> message
                    is UserMessageContent.Structured ->
                        message.copy(
                            content =
                                UserMessageContent.Structured(
                                    replaceUserImagesWithPlaceholder(content.parts, NON_VISION_USER_IMAGE_PLACEHOLDER),
                                ),
                        )
                }
            is ToolResultMessage ->
                message.copy(content = replaceToolImagesWithPlaceholder(message.content, NON_VISION_TOOL_IMAGE_PLACEHOLDER))
            else -> message
        }
    }
}

internal fun transformMessages(
    messages: List<Message>,
    model: Model<String>,
    normalizeToolCallId: ((String, Model<String>, AssistantMessage) -> String)? = null,
): List<Message> {
    val toolCallIdMap = linkedMapOf<String, String>()
    val transformed =
        downgradeUnsupportedImages(messages, model).map { message ->
            when (message) {
                is UserMessage -> message
                is ToolResultMessage -> {
                    val normalizedId = toolCallIdMap[message.toolCallId]
                    if (normalizedId != null && normalizedId != message.toolCallId) {
                        message.copy(toolCallId = normalizedId)
                    } else {
                        message
                    }
                }
                is AssistantMessage -> {
                    val isSameModel =
                        message.provider == model.provider &&
                            message.api == model.api &&
                            message.model == model.id
                    val transformedContent =
                        message.content.flatMap { block ->
                            transformAssistantBlock(block, message, model, isSameModel, normalizeToolCallId, toolCallIdMap)
                        }
                    message.copy(content = transformedContent.toMutableList())
                }
                else -> message
            }
        }

    val result = mutableListOf<Message>()
    var pendingToolCalls = emptyList<ToolCall>()
    var existingToolResultIds = emptySet<String>()

    fun insertSyntheticToolResults() {
        pendingToolCalls.forEach { toolCall ->
            if (!existingToolResultIds.contains(toolCall.id)) {
                result +=
                    ToolResultMessage(
                        toolCallId = toolCall.id,
                        toolName = toolCall.name,
                        content = listOf(TextContent("No result provided")),
                        isError = true,
                        timestamp = System.currentTimeMillis(),
                    )
            }
        }
        pendingToolCalls = emptyList()
        existingToolResultIds = emptySet()
    }

    transformed.forEach { message ->
        when (message) {
            is AssistantMessage -> {
                insertSyntheticToolResults()
                if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.ABORTED) {
                    return@forEach
                }
                val toolCalls = message.content.filterIsInstance<ToolCall>()
                if (toolCalls.isNotEmpty()) {
                    pendingToolCalls = toolCalls
                    existingToolResultIds = emptySet()
                }
                result += message
            }
            is ToolResultMessage -> {
                existingToolResultIds = existingToolResultIds + message.toolCallId
                result += message
            }
            is UserMessage -> {
                insertSyntheticToolResults()
                result += message
            }
            else -> result += message
        }
    }

    insertSyntheticToolResults()
    return result
}

private fun transformAssistantBlock(
    block: AssistantContentBlock,
    source: AssistantMessage,
    model: Model<String>,
    isSameModel: Boolean,
    normalizeToolCallId: ((String, Model<String>, AssistantMessage) -> String)?,
    toolCallIdMap: MutableMap<String, String>,
): List<AssistantContentBlock> =
    when (block) {
        is ThinkingContent ->
            when {
                block.redacted -> if (isSameModel) listOf(block) else emptyList()
                isSameModel && block.thinkingSignature != null -> listOf(block)
                block.thinking.isBlank() -> emptyList()
                isSameModel -> listOf(block)
                else -> listOf(TextContent(block.thinking))
            }
        is TextContent -> listOf(if (isSameModel) block else TextContent(block.text))
        is ToolCall -> {
            var normalized = if (!isSameModel && block.thoughtSignature != null) block.copy(thoughtSignature = null) else block
            if (!isSameModel && normalizeToolCallId != null) {
                val normalizedId = normalizeToolCallId(block.id, model, source)
                if (normalizedId != block.id) {
                    toolCallIdMap[block.id] = normalizedId
                    normalized = normalized.copy(id = normalizedId)
                }
            }
            listOf(normalized)
        }
        else -> listOf(block)
    }

private fun replaceUserImagesWithPlaceholder(
    content: List<UserContentPart>,
    placeholder: String,
): List<UserContentPart> {
    val result = mutableListOf<UserContentPart>()
    var previousWasPlaceholder = false
    content.forEach { part ->
        if (part is ImageContent) {
            if (!previousWasPlaceholder) {
                result += TextContent(placeholder)
            }
            previousWasPlaceholder = true
        } else {
            result += part
            previousWasPlaceholder = part is TextContent && part.text == placeholder
        }
    }
    return result
}

private fun replaceToolImagesWithPlaceholder(
    content: List<ToolResultContentPart>,
    placeholder: String,
): List<ToolResultContentPart> {
    val result = mutableListOf<ToolResultContentPart>()
    var previousWasPlaceholder = false
    content.forEach { part ->
        if (part is ImageContent) {
            if (!previousWasPlaceholder) {
                result += TextContent(placeholder)
            }
            previousWasPlaceholder = true
        } else {
            result += part
            previousWasPlaceholder = part is TextContent && part.text == placeholder
        }
    }
    return result
}

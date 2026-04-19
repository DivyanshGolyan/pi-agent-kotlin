package pi.coding.agent.core.compaction

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import pi.agent.core.AgentMessage
import pi.ai.core.AssistantMessage
import pi.ai.core.Message
import pi.ai.core.TextContent
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultMessage
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent

public data class FileOperations(
    val read: MutableSet<String> = linkedSetOf(),
    val written: MutableSet<String> = linkedSetOf(),
    val edited: MutableSet<String> = linkedSetOf(),
)

public fun createFileOps(): FileOperations = FileOperations()

public fun extractFileOpsFromMessage(
    message: AgentMessage,
    fileOps: FileOperations,
) {
    if (message !is AssistantMessage) {
        return
    }

    message.content.filterIsInstance<ToolCall>().forEach { toolCall ->
        val path = toolCall.arguments["path"]?.jsonPrimitive?.contentOrNull ?: return@forEach
        when (toolCall.name) {
            "read" -> fileOps.read += path
            "write" -> fileOps.written += path
            "edit" -> fileOps.edited += path
        }
    }
}

public data class FileLists(
    val readFiles: List<String>,
    val modifiedFiles: List<String>,
)

public fun computeFileLists(fileOps: FileOperations): FileLists {
    val modified = (fileOps.edited + fileOps.written).toSortedSet()
    val readOnly = fileOps.read.filterNot(modified::contains).sorted()
    return FileLists(readFiles = readOnly, modifiedFiles = modified.toList())
}

public fun formatFileOperations(
    readFiles: List<String>,
    modifiedFiles: List<String>,
): String {
    val sections = mutableListOf<String>()
    if (readFiles.isNotEmpty()) {
        sections += "<read-files>\n${readFiles.joinToString("\n")}\n</read-files>"
    }
    if (modifiedFiles.isNotEmpty()) {
        sections += "<modified-files>\n${modifiedFiles.joinToString("\n")}\n</modified-files>"
    }
    return if (sections.isEmpty()) "" else "\n\n${sections.joinToString("\n\n")}"
}

private const val TOOL_RESULT_MAX_CHARS: Int = 2000

internal fun truncateForSummary(
    text: String,
    maxChars: Int,
): String {
    if (text.length <= maxChars) {
        return text
    }

    val truncatedChars = text.length - maxChars
    return "${text.take(maxChars)}\n\n[... $truncatedChars more characters truncated]"
}

public fun serializeConversation(messages: List<Message>): String {
    val parts = mutableListOf<String>()

    messages.forEach { message ->
        when (message) {
            is UserMessage -> {
                val content =
                    when (val userContent = message.content) {
                        is UserMessageContent.Text -> userContent.value
                        is UserMessageContent.Structured ->
                            userContent.parts
                                .filterIsInstance<TextContent>()
                                .joinToString(separator = "") { it.text }
                    }
                if (content.isNotEmpty()) {
                    parts += "[User]: $content"
                }
            }

            is AssistantMessage -> {
                val textParts = message.content.filterIsInstance<TextContent>().map { it.text }
                val thinkingParts = message.content.filterIsInstance<pi.ai.core.ThinkingContent>().map { it.thinking }
                val toolCalls =
                    message.content.filterIsInstance<ToolCall>().map { toolCall ->
                        val args =
                            toolCall.arguments.entries.joinToString(separator = ", ") { (key, value) ->
                                "$key=$value"
                            }
                        "${toolCall.name}($args)"
                    }

                if (thinkingParts.isNotEmpty()) {
                    parts += "[Assistant thinking]: ${thinkingParts.joinToString("\n")}"
                }
                if (textParts.isNotEmpty()) {
                    parts += "[Assistant]: ${textParts.joinToString("\n")}"
                }
                if (toolCalls.isNotEmpty()) {
                    parts += "[Assistant tool calls]: ${toolCalls.joinToString("; ")}"
                }
            }

            is ToolResultMessage -> {
                val content =
                    message.content
                        .filterIsInstance<TextContent>()
                        .joinToString(separator = "") { it.text }
                if (content.isNotEmpty()) {
                    parts += "[Tool result]: ${truncateForSummary(content, TOOL_RESULT_MAX_CHARS)}"
                }
            }
        }
    }

    return parts.joinToString(separator = "\n\n")
}

public const val SUMMARIZATION_SYSTEM_PROMPT: String =
    "You are a context summarization assistant. Your task is to read a conversation between a user and an AI coding assistant, then produce a structured summary following the exact format specified.\n\nDo NOT continue the conversation. Do NOT respond to any questions in the conversation. ONLY output the structured summary."

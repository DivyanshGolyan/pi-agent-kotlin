package pi.coding.agent.core

import kotlinx.serialization.json.JsonElement
import pi.agent.core.AgentMessage
import pi.agent.core.CustomAgentMessage
import pi.agent.core.defaultConvertToLlm
import pi.ai.core.Message
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent

public const val COMPACTION_SUMMARY_PREFIX: String =
    "The conversation history before this point was compacted into the following summary:\n\n<summary>\n"
public const val COMPACTION_SUMMARY_SUFFIX: String = "\n</summary>"

public const val BRANCH_SUMMARY_PREFIX: String =
    "The following is a summary of a branch that this conversation came back from:\n\n<summary>\n"
public const val BRANCH_SUMMARY_SUFFIX: String = "</summary>"

public data class BashExecutionMessage(
    val command: String,
    val output: String,
    val exitCode: Int? = null,
    val cancelled: Boolean,
    val truncated: Boolean,
    val fullOutputPath: String? = null,
    override val timestamp: Long,
    val excludeFromContext: Boolean = false,
) : CustomAgentMessage {
    override val role: String = "bashExecution"
}

public data class CustomMessage(
    val customType: String,
    val content: UserMessageContent,
    val display: Boolean,
    val details: JsonElement? = null,
    override val timestamp: Long,
) : CustomAgentMessage {
    override val role: String = "custom"
}

public data class BranchSummaryMessage(
    val summary: String,
    val fromId: String,
    override val timestamp: Long,
) : CustomAgentMessage {
    override val role: String = "branchSummary"
}

public data class CompactionSummaryMessage(
    val summary: String,
    val tokensBefore: Int,
    override val timestamp: Long,
) : CustomAgentMessage {
    override val role: String = "compactionSummary"
}

public fun bashExecutionToText(message: BashExecutionMessage): String {
    val builder = StringBuilder("Ran `${message.command}`\n")
    if (message.output.isNotEmpty()) {
        builder.append("```\n")
        builder.append(message.output)
        builder.append("\n```")
    } else {
        builder.append("(no output)")
    }

    when {
        message.cancelled -> builder.append("\n\n(command cancelled)")
        message.exitCode != null && message.exitCode != 0 -> builder.append("\n\nCommand exited with code ${message.exitCode}")
    }

    if (message.truncated && !message.fullOutputPath.isNullOrBlank()) {
        builder.append("\n\n[Output truncated. Full output: ${message.fullOutputPath}]")
    }

    return builder.toString()
}

public fun createBranchSummaryMessage(
    summary: String,
    fromId: String,
    timestamp: String,
): BranchSummaryMessage = BranchSummaryMessage(summary = summary, fromId = fromId, timestamp = parseIsoTimestamp(timestamp))

public fun createCompactionSummaryMessage(
    summary: String,
    tokensBefore: Int,
    timestamp: String,
): CompactionSummaryMessage =
    CompactionSummaryMessage(summary = summary, tokensBefore = tokensBefore, timestamp = parseIsoTimestamp(timestamp))

public fun createCustomMessage(
    customType: String,
    content: UserMessageContent,
    display: Boolean,
    details: JsonElement? = null,
    timestamp: String,
): CustomMessage =
    CustomMessage(
        customType = customType,
        content = content,
        display = display,
        details = details,
        timestamp = parseIsoTimestamp(timestamp),
    )

public fun createCustomMessage(
    customType: String,
    content: String,
    display: Boolean,
    details: JsonElement? = null,
    timestamp: String,
): CustomMessage =
    createCustomMessage(
        customType = customType,
        content = UserMessageContent.Text(content),
        display = display,
        details = details,
        timestamp = timestamp,
    )

public suspend fun convertToLlm(messages: List<AgentMessage>): List<Message> = defaultConvertToLlm(messages, ::codingMessageToLlm)

public suspend fun codingMessageToLlm(message: CustomAgentMessage): Message? =
    when (message) {
        is BashExecutionMessage -> {
            if (message.excludeFromContext) {
                null
            } else {
                UserMessage(
                    content = UserMessageContent.Text(bashExecutionToText(message)),
                    timestamp = message.timestamp,
                )
            }
        }
        is CustomMessage -> UserMessage(content = message.content, timestamp = message.timestamp)
        is BranchSummaryMessage ->
            UserMessage(
                content = UserMessageContent.Text(BRANCH_SUMMARY_PREFIX + message.summary + BRANCH_SUMMARY_SUFFIX),
                timestamp = message.timestamp,
            )
        is CompactionSummaryMessage ->
            UserMessage(
                content = UserMessageContent.Text(COMPACTION_SUMMARY_PREFIX + message.summary + COMPACTION_SUMMARY_SUFFIX),
                timestamp = message.timestamp,
            )
        else -> null
    }

internal fun parseIsoTimestamp(timestamp: String): Long =
    java.time.Instant
        .parse(timestamp)
        .toEpochMilli()

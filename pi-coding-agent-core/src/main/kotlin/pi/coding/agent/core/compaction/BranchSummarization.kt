package pi.coding.agent.core.compaction

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pi.agent.core.AgentMessage
import pi.ai.core.Context
import pi.ai.core.Model
import pi.ai.core.SimpleStreamOptions
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import pi.ai.core.completeSimple
import pi.coding.agent.core.BranchSummaryEntry
import pi.coding.agent.core.CompactionEntry
import pi.coding.agent.core.CustomMessageEntry
import pi.coding.agent.core.ModelChangeEntry
import pi.coding.agent.core.ReadonlySessionManager
import pi.coding.agent.core.SessionEntry
import pi.coding.agent.core.SessionMessageEntry
import pi.coding.agent.core.ThinkingLevelChangeEntry
import pi.coding.agent.core.convertToLlm
import pi.coding.agent.core.createBranchSummaryMessage
import pi.coding.agent.core.createCompactionSummaryMessage
import pi.coding.agent.core.createCustomMessage

public data class BranchSummaryResult(
    val summary: String? = null,
    val readFiles: List<String>? = null,
    val modifiedFiles: List<String>? = null,
    val aborted: Boolean = false,
    val error: String? = null,
)

public data class BranchSummaryDetails(
    val readFiles: List<String>,
    val modifiedFiles: List<String>,
)

public data class BranchSummarySettings(
    val reserveTokens: Int = 16384,
)

public val DEFAULT_BRANCH_SUMMARY_SETTINGS: BranchSummarySettings = BranchSummarySettings()

public data class BranchPreparation(
    val messages: List<AgentMessage>,
    val fileOps: FileOperations,
    val totalTokens: Int,
)

public data class CollectEntriesResult(
    val entries: List<SessionEntry>,
    val commonAncestorId: String?,
)

public data class GenerateBranchSummaryOptions(
    val model: Model<*>,
    val apiKey: String,
    val headers: Map<String, String> = emptyMap(),
    val signal: pi.ai.core.AbortSignal? = null,
    val customInstructions: String? = null,
    val replaceInstructions: Boolean = false,
    val reserveTokens: Int = 16384,
)

public fun collectEntriesForBranchSummary(
    session: ReadonlySessionManager,
    oldLeafId: String?,
    targetId: String,
): CollectEntriesResult {
    if (oldLeafId == null) {
        return CollectEntriesResult(entries = emptyList(), commonAncestorId = null)
    }

    val oldPath = session.getBranch(oldLeafId).mapTo(linkedSetOf()) { it.id }
    val targetPath = session.getBranch(targetId)

    var commonAncestorId: String? = null
    for (index in targetPath.indices.reversed()) {
        if (oldPath.contains(targetPath[index].id)) {
            commonAncestorId = targetPath[index].id
            break
        }
    }

    val entries = mutableListOf<SessionEntry>()
    var currentId: String? = oldLeafId
    while (currentId != null && currentId != commonAncestorId) {
        val entry = session.getEntry(currentId) ?: break
        entries += entry
        currentId = entry.parentId
    }
    entries.reverse()

    return CollectEntriesResult(entries = entries, commonAncestorId = commonAncestorId)
}

public fun prepareBranchEntries(
    entries: List<SessionEntry>,
    tokenBudget: Int = 0,
): BranchPreparation {
    val messages = mutableListOf<AgentMessage>()
    val fileOps = createFileOps()
    var totalTokens = 0

    entries.forEach { entry ->
        if (entry is BranchSummaryEntry && entry.fromHook != true && entry.details != null) {
            val details = entry.details.jsonObject
            details["readFiles"]
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.forEach { fileOps.read += it }
            details["modifiedFiles"]
                ?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.forEach { fileOps.edited += it }
        }
    }

    for (index in entries.indices.reversed()) {
        val entry = entries[index]
        val message = getMessageFromBranchEntry(entry) ?: continue

        extractFileOpsFromMessage(message, fileOps)
        val tokens = estimateTokens(message)

        if (tokenBudget > 0 && totalTokens + tokens > tokenBudget) {
            if ((entry is CompactionEntry || entry is BranchSummaryEntry) && totalTokens < tokenBudget * 0.9) {
                messages.add(0, message)
                totalTokens += tokens
            }
            break
        }

        messages.add(0, message)
        totalTokens += tokens
    }

    return BranchPreparation(messages = messages, fileOps = fileOps, totalTokens = totalTokens)
}

private const val BRANCH_SUMMARY_PREAMBLE: String =
    "The user explored a different conversation branch before returning here.\nSummary of that exploration:\n\n"

private const val BRANCH_SUMMARY_PROMPT: String =
    "Create a structured summary of this conversation branch for context when returning later.\n\nUse this EXACT format:\n\n## Goal\n[What was the user trying to accomplish in this branch?]\n\n## Constraints & Preferences\n- [Any constraints, preferences, or requirements mentioned]\n- [Or \"(none)\" if none were mentioned]\n\n## Progress\n### Done\n- [x] [Completed tasks/changes]\n\n### In Progress\n- [ ] [Work that was started but not finished]\n\n### Blocked\n- [Issues preventing progress, if any]\n\n## Key Decisions\n- **[Decision]**: [Brief rationale]\n\n## Next Steps\n1. [What should happen next to continue this work]\n\nKeep each section concise. Preserve exact file paths, function names, and error messages."

public suspend fun generateBranchSummary(
    entries: List<SessionEntry>,
    options: GenerateBranchSummaryOptions,
): BranchSummaryResult {
    val contextWindow = options.model.contextWindow
    val tokenBudget = contextWindow - options.reserveTokens
    val preparation = prepareBranchEntries(entries, tokenBudget)

    if (preparation.messages.isEmpty()) {
        return BranchSummaryResult(summary = "No content to summarize")
    }

    val llmMessages = convertToLlm(preparation.messages)
    val conversationText = serializeConversation(llmMessages)

    val instructions =
        when {
            options.replaceInstructions && !options.customInstructions.isNullOrBlank() -> options.customInstructions
            !options.customInstructions.isNullOrBlank() -> "$BRANCH_SUMMARY_PROMPT\n\nAdditional focus: ${options.customInstructions}"
            else -> BRANCH_SUMMARY_PROMPT
        }

    val promptText = "<conversation>\n$conversationText\n</conversation>\n\n$instructions"
    val response =
        completeSimple(
            model = options.model,
            context =
                Context(
                    systemPrompt = SUMMARIZATION_SYSTEM_PROMPT,
                    messages =
                        listOf(
                            UserMessage(
                                content = UserMessageContent.Text(promptText),
                                timestamp = System.currentTimeMillis(),
                            ),
                        ),
                ),
            options =
                SimpleStreamOptions(
                    apiKey = options.apiKey,
                    headers = options.headers,
                    signal = options.signal,
                    maxTokens = 2048,
                ),
        )

    return when (response.stopReason) {
        StopReason.ABORTED -> BranchSummaryResult(aborted = true)
        StopReason.ERROR -> BranchSummaryResult(error = response.errorMessage ?: "Summarization failed")
        else -> {
            var summary =
                response.content
                    .filterIsInstance<TextContent>()
                    .joinToString(separator = "\n") { it.text }
            summary = BRANCH_SUMMARY_PREAMBLE + summary

            val fileLists = computeFileLists(preparation.fileOps)
            summary += formatFileOperations(fileLists.readFiles, fileLists.modifiedFiles)

            BranchSummaryResult(
                summary = summary.ifBlank { "No summary generated" },
                readFiles = fileLists.readFiles,
                modifiedFiles = fileLists.modifiedFiles,
            )
        }
    }
}

private fun getMessageFromBranchEntry(entry: SessionEntry): AgentMessage? =
    when (entry) {
        is SessionMessageEntry -> {
            if (entry.message is pi.ai.core.ToolResultMessage) {
                null
            } else {
                entry.message
            }
        }
        is CustomMessageEntry ->
            createCustomMessage(
                customType = entry.customType,
                content = entry.content,
                display = entry.display,
                details = entry.details,
                timestamp = entry.timestamp,
            )
        is BranchSummaryEntry -> createBranchSummaryMessage(entry.summary, entry.fromId, entry.timestamp)
        is CompactionEntry -> createCompactionSummaryMessage(entry.summary, entry.tokensBefore, entry.timestamp)
        is ThinkingLevelChangeEntry,
        is ModelChangeEntry,
        -> null
        else -> null
    }

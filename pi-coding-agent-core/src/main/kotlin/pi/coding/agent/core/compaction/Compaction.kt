package pi.coding.agent.core.compaction

import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import pi.agent.core.AgentMessage
import pi.ai.core.AssistantMessage
import pi.ai.core.Context
import pi.ai.core.Model
import pi.ai.core.SimpleStreamOptions
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.ThinkingLevel
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultMessage
import pi.ai.core.Usage
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import pi.ai.core.completeSimple
import pi.coding.agent.core.BashExecutionMessage
import pi.coding.agent.core.BranchSummaryEntry
import pi.coding.agent.core.BranchSummaryMessage
import pi.coding.agent.core.CompactionEntry
import pi.coding.agent.core.CompactionSummaryMessage
import pi.coding.agent.core.CustomMessage
import pi.coding.agent.core.CustomMessageEntry
import pi.coding.agent.core.ModelChangeEntry
import pi.coding.agent.core.SessionEntry
import pi.coding.agent.core.SessionMessageEntry
import pi.coding.agent.core.ThinkingLevelChangeEntry
import pi.coding.agent.core.buildSessionContext
import pi.coding.agent.core.convertToLlm
import pi.coding.agent.core.createBranchSummaryMessage
import pi.coding.agent.core.createCompactionSummaryMessage
import pi.coding.agent.core.createCustomMessage

public data class CompactionDetails(
    val readFiles: List<String>,
    val modifiedFiles: List<String>,
)

public data class CompactionResult<T>(
    val summary: String,
    val firstKeptEntryId: String,
    val tokensBefore: Int,
    val details: T? = null,
)

public data class CompactionSettings(
    val enabled: Boolean = true,
    val reserveTokens: Int = 16384,
    val keepRecentTokens: Int = 20000,
)

public val DEFAULT_COMPACTION_SETTINGS: CompactionSettings = CompactionSettings()

public fun calculateContextTokens(usage: Usage): Int =
    usage.totalTokens.takeIf { it > 0 } ?: (usage.input + usage.output + usage.cacheRead + usage.cacheWrite)

internal fun getAssistantUsage(message: AgentMessage): Usage? {
    if (message !is AssistantMessage) {
        return null
    }
    return when (message.stopReason) {
        StopReason.ABORTED,
        StopReason.ERROR,
        -> null
        else -> message.usage
    }
}

public fun getLastAssistantUsage(entries: List<SessionEntry>): Usage? =
    entries
        .asReversed()
        .filterIsInstance<SessionMessageEntry>()
        .mapNotNull { getAssistantUsage(it.message) }
        .firstOrNull()

public data class ContextUsageEstimate(
    val tokens: Int,
    val usageTokens: Int,
    val trailingTokens: Int,
    val lastUsageIndex: Int?,
)

private fun getLastAssistantUsageInfo(messages: List<AgentMessage>): Pair<Usage, Int>? {
    for (index in messages.indices.reversed()) {
        val usage = getAssistantUsage(messages[index])
        if (usage != null) {
            return usage to index
        }
    }
    return null
}

public fun estimateContextTokens(messages: List<AgentMessage>): ContextUsageEstimate {
    val usageInfo = getLastAssistantUsageInfo(messages)
    if (usageInfo == null) {
        val estimated = messages.sumOf(::estimateTokens)
        return ContextUsageEstimate(tokens = estimated, usageTokens = 0, trailingTokens = estimated, lastUsageIndex = null)
    }

    val (usage, usageIndex) = usageInfo
    val usageTokens = calculateContextTokens(usage)
    val trailingTokens = messages.drop(usageIndex + 1).sumOf(::estimateTokens)
    return ContextUsageEstimate(
        tokens = usageTokens + trailingTokens,
        usageTokens = usageTokens,
        trailingTokens = trailingTokens,
        lastUsageIndex = usageIndex,
    )
}

public fun shouldCompact(
    contextTokens: Int,
    contextWindow: Int,
    settings: CompactionSettings,
): Boolean = settings.enabled && contextTokens > contextWindow - settings.reserveTokens

public fun estimateTokens(message: AgentMessage): Int =
    when (message) {
        is UserMessage -> {
            val chars =
                when (val content = message.content) {
                    is UserMessageContent.Text -> content.value.length
                    is UserMessageContent.Structured ->
                        content.parts.sumOf { part ->
                            when (part) {
                                is TextContent -> part.text.length
                                is pi.ai.core.ImageContent -> 4800
                            }
                        }
                }
            divideChars(chars)
        }

        is AssistantMessage -> {
            val chars =
                message.content.sumOf { block ->
                    when (block) {
                        is TextContent -> block.text.length
                        is pi.ai.core.ThinkingContent -> block.thinking.length
                        is ToolCall -> block.name.length + block.arguments.toString().length
                        else -> 0
                    }
                }
            divideChars(chars)
        }

        is ToolResultMessage -> {
            val chars =
                message.content.sumOf { block ->
                    when (block) {
                        is TextContent -> block.text.length
                        is pi.ai.core.ImageContent -> 4800
                    }
                }
            divideChars(chars)
        }

        is CustomMessage -> {
            val chars =
                when (val content = message.content) {
                    is UserMessageContent.Text -> content.value.length
                    is UserMessageContent.Structured ->
                        content.parts.sumOf { part ->
                            when (part) {
                                is TextContent -> part.text.length
                                is pi.ai.core.ImageContent -> 4800
                            }
                        }
                }
            divideChars(chars)
        }

        is BashExecutionMessage -> divideChars(message.command.length + message.output.length)
        is BranchSummaryMessage -> divideChars(message.summary.length)
        is CompactionSummaryMessage -> divideChars(message.summary.length)
        else -> 0
    }

private fun divideChars(chars: Int): Int = kotlin.math.ceil(chars / 4.0).toInt()

private fun findValidCutPoints(
    entries: List<SessionEntry>,
    startIndex: Int,
    endIndex: Int,
): List<Int> {
    val cutPoints = mutableListOf<Int>()
    for (index in startIndex until endIndex) {
        when (val entry = entries[index]) {
            is SessionMessageEntry -> {
                when (entry.message) {
                    is BashExecutionMessage,
                    is CustomMessage,
                    is BranchSummaryMessage,
                    is CompactionSummaryMessage,
                    is UserMessage,
                    is AssistantMessage,
                    -> cutPoints += index
                    is ToolResultMessage -> Unit
                }
            }

            is ThinkingLevelChangeEntry,
            is ModelChangeEntry,
            is CompactionEntry,
            -> Unit

            is BranchSummaryEntry,
            is CustomMessageEntry,
            -> cutPoints += index

            else -> Unit
        }
    }
    return cutPoints
}

public fun findTurnStartIndex(
    entries: List<SessionEntry>,
    entryIndex: Int,
    startIndex: Int,
): Int {
    for (index in entryIndex downTo startIndex) {
        when (val entry = entries[index]) {
            is BranchSummaryEntry,
            is CustomMessageEntry,
            -> return index

            is SessionMessageEntry -> {
                val message = entry.message
                if (message is UserMessage || message is BashExecutionMessage) {
                    return index
                }
            }

            else -> Unit
        }
    }
    return -1
}

public data class CutPointResult(
    val firstKeptEntryIndex: Int,
    val turnStartIndex: Int,
    val isSplitTurn: Boolean,
)

public fun findCutPoint(
    entries: List<SessionEntry>,
    startIndex: Int,
    endIndex: Int,
    keepRecentTokens: Int,
): CutPointResult {
    val cutPoints = findValidCutPoints(entries, startIndex, endIndex)
    if (cutPoints.isEmpty()) {
        return CutPointResult(firstKeptEntryIndex = startIndex, turnStartIndex = -1, isSplitTurn = false)
    }

    var accumulatedTokens = 0
    var cutIndex = cutPoints.first()

    for (index in (endIndex - 1) downTo startIndex) {
        val entry = entries[index]
        if (entry !is SessionMessageEntry) {
            continue
        }

        accumulatedTokens += estimateTokens(entry.message)
        if (accumulatedTokens >= keepRecentTokens) {
            cutIndex = cutPoints.firstOrNull { it >= index } ?: cutIndex
            break
        }
    }

    while (cutIndex > startIndex) {
        val previousEntry = entries[cutIndex - 1]
        if (previousEntry is CompactionEntry || previousEntry is SessionMessageEntry) {
            break
        }
        cutIndex -= 1
    }

    val cutEntry = entries[cutIndex]
    val isUserMessage = cutEntry is SessionMessageEntry && cutEntry.message is UserMessage
    val turnStartIndex = if (isUserMessage) -1 else findTurnStartIndex(entries, cutIndex, startIndex)

    return CutPointResult(
        firstKeptEntryIndex = cutIndex,
        turnStartIndex = turnStartIndex,
        isSplitTurn = !isUserMessage && turnStartIndex != -1,
    )
}

private const val SUMMARIZATION_PROMPT: String =
    "The messages above are a conversation to summarize. Create a structured context checkpoint summary that another LLM will use to continue the work.\n\nUse this EXACT format:\n\n## Goal\n[What is the user trying to accomplish? Can be multiple items if the session covers different tasks.]\n\n## Constraints & Preferences\n- [Any constraints, preferences, or requirements mentioned by user]\n- [Or \"(none)\" if none were mentioned]\n\n## Progress\n### Done\n- [x] [Completed tasks/changes]\n\n### In Progress\n- [ ] [Current work]\n\n### Blocked\n- [Issues preventing progress, if any]\n\n## Key Decisions\n- **[Decision]**: [Brief rationale]\n\n## Next Steps\n1. [Ordered list of what should happen next]\n\n## Critical Context\n- [Any data, examples, or references needed to continue]\n- [Or \"(none)\" if not applicable]\n\nKeep each section concise. Preserve exact file paths, function names, and error messages."

private const val UPDATE_SUMMARIZATION_PROMPT: String =
    "The messages above are NEW conversation messages to incorporate into the existing summary provided in <previous-summary> tags.\n\nUpdate the existing structured summary with new information. RULES:\n- PRESERVE all existing information from the previous summary\n- ADD new progress, decisions, and context from the new messages\n- UPDATE the Progress section: move items from \"In Progress\" to \"Done\" when completed\n- UPDATE \"Next Steps\" based on what was accomplished\n- PRESERVE exact file paths, function names, and error messages\n- If something is no longer relevant, you may remove it\n\nUse this EXACT format:\n\n## Goal\n[Preserve existing goals, add new ones if the task expanded]\n\n## Constraints & Preferences\n- [Preserve existing, add new ones discovered]\n\n## Progress\n### Done\n- [x] [Include previously done items AND newly completed items]\n\n### In Progress\n- [ ] [Current work - update based on progress]\n\n### Blocked\n- [Current blockers - remove if resolved]\n\n## Key Decisions\n- **[Decision]**: [Brief rationale] (preserve all previous, add new)\n\n## Next Steps\n1. [Update based on current state]\n\n## Critical Context\n- [Preserve important context, add new if needed]\n\nKeep each section concise. Preserve exact file paths, function names, and error messages."

private const val TURN_PREFIX_SUMMARIZATION_PROMPT: String =
    "This is the PREFIX of a turn that was too large to keep. The SUFFIX (recent work) is retained.\n\nSummarize the prefix to provide context for the retained suffix:\n\n## Original Request\n[What did the user ask for in this turn?]\n\n## Early Progress\n- [Key decisions and work done in the prefix]\n\n## Context for Suffix\n- [Information needed to understand the retained recent work]\n\nBe concise. Focus on what's needed to understand the kept suffix."

public suspend fun generateSummary(
    currentMessages: List<AgentMessage>,
    model: Model<*>,
    reserveTokens: Int,
    apiKey: String,
    headers: Map<String, String> = emptyMap(),
    signal: pi.ai.core.AbortSignal? = null,
    customInstructions: String? = null,
    previousSummary: String? = null,
): String {
    val maxTokens = kotlin.math.floor(0.8 * reserveTokens).toInt()

    var prompt = if (previousSummary != null) UPDATE_SUMMARIZATION_PROMPT else SUMMARIZATION_PROMPT
    if (!customInstructions.isNullOrBlank()) {
        prompt += "\n\nAdditional focus: $customInstructions"
    }

    val llmMessages = convertToLlm(currentMessages)
    val conversationText = serializeConversation(llmMessages)
    val builder = StringBuilder("<conversation>\n").append(conversationText).append("\n</conversation>\n\n")
    if (previousSummary != null) {
        builder.append("<previous-summary>\n").append(previousSummary).append("\n</previous-summary>\n\n")
    }
    builder.append(prompt)

    val response =
        completeSimple(
            model = model,
            context =
                Context(
                    systemPrompt = SUMMARIZATION_SYSTEM_PROMPT,
                    messages =
                        listOf(
                            UserMessage(
                                content = UserMessageContent.Text(builder.toString()),
                                timestamp = System.currentTimeMillis(),
                            ),
                        ),
                ),
            options =
                SimpleStreamOptions(
                    reasoning = if (model.reasoning) ThinkingLevel.HIGH else null,
                    maxTokens = maxTokens,
                    signal = signal,
                    apiKey = apiKey,
                    headers = headers,
                ),
        )

    check(response.stopReason != StopReason.ERROR) {
        "Summarization failed: ${response.errorMessage ?: "Unknown error"}"
    }

    return response.content.filterIsInstance<TextContent>().joinToString(separator = "\n") { it.text }
}

public data class CompactionPreparation(
    val firstKeptEntryId: String,
    val messagesToSummarize: List<AgentMessage>,
    val turnPrefixMessages: List<AgentMessage>,
    val isSplitTurn: Boolean,
    val tokensBefore: Int,
    val previousSummary: String? = null,
    val fileOps: FileOperations,
    val settings: CompactionSettings,
)

public fun prepareCompaction(
    pathEntries: List<SessionEntry>,
    settings: CompactionSettings,
): CompactionPreparation? {
    if (pathEntries.lastOrNull() is CompactionEntry) {
        return null
    }

    val previousCompactionIndex = pathEntries.indexOfLast { it is CompactionEntry }
    val previousSummary: String?
    val boundaryStart: Int
    if (previousCompactionIndex >= 0) {
        val previousCompaction = pathEntries[previousCompactionIndex] as CompactionEntry
        previousSummary = previousCompaction.summary
        val firstKeptEntryIndex = pathEntries.indexOfFirst { it.id == previousCompaction.firstKeptEntryId }
        boundaryStart = if (firstKeptEntryIndex >= 0) firstKeptEntryIndex else previousCompactionIndex + 1
    } else {
        previousSummary = null
        boundaryStart = 0
    }
    val boundaryEnd = pathEntries.size

    val tokensBefore = estimateContextTokens(buildSessionContext(pathEntries).messages).tokens
    val cutPoint = findCutPoint(pathEntries, boundaryStart, boundaryEnd, settings.keepRecentTokens)
    val firstKeptEntryId = pathEntries.getOrNull(cutPoint.firstKeptEntryIndex)?.id ?: return null
    val historyEnd = if (cutPoint.isSplitTurn) cutPoint.turnStartIndex else cutPoint.firstKeptEntryIndex

    val messagesToSummarize =
        (boundaryStart until historyEnd).mapNotNull { getMessageFromEntryForCompaction(pathEntries[it]) }

    val turnPrefixMessages =
        if (cutPoint.isSplitTurn) {
            (cutPoint.turnStartIndex until cutPoint.firstKeptEntryIndex).mapNotNull { getMessageFromEntryForCompaction(pathEntries[it]) }
        } else {
            emptyList()
        }

    val fileOps = extractFileOperations(messagesToSummarize, pathEntries, previousCompactionIndex)
    if (cutPoint.isSplitTurn) {
        turnPrefixMessages.forEach { extractFileOpsFromMessage(it, fileOps) }
    }

    return CompactionPreparation(
        firstKeptEntryId = firstKeptEntryId,
        messagesToSummarize = messagesToSummarize,
        turnPrefixMessages = turnPrefixMessages,
        isSplitTurn = cutPoint.isSplitTurn,
        tokensBefore = tokensBefore,
        previousSummary = previousSummary,
        fileOps = fileOps,
        settings = settings,
    )
}

public suspend fun compact(
    preparation: CompactionPreparation,
    model: Model<*>,
    apiKey: String,
    headers: Map<String, String> = emptyMap(),
    customInstructions: String? = null,
    signal: pi.ai.core.AbortSignal? = null,
): CompactionResult<CompactionDetails> {
    val summary =
        if (preparation.isSplitTurn && preparation.turnPrefixMessages.isNotEmpty()) {
            val historySummary =
                if (preparation.messagesToSummarize.isNotEmpty()) {
                    generateSummary(
                        currentMessages = preparation.messagesToSummarize,
                        model = model,
                        reserveTokens = preparation.settings.reserveTokens,
                        apiKey = apiKey,
                        headers = headers,
                        signal = signal,
                        customInstructions = customInstructions,
                        previousSummary = preparation.previousSummary,
                    )
                } else {
                    "No prior history."
                }
            val prefixSummary =
                generateTurnPrefixSummary(
                    messages = preparation.turnPrefixMessages,
                    model = model,
                    reserveTokens = preparation.settings.reserveTokens,
                    apiKey = apiKey,
                    headers = headers,
                    signal = signal,
                )
            "$historySummary\n\n---\n\n**Turn Context (split turn):**\n\n$prefixSummary"
        } else {
            generateSummary(
                currentMessages = preparation.messagesToSummarize,
                model = model,
                reserveTokens = preparation.settings.reserveTokens,
                apiKey = apiKey,
                headers = headers,
                signal = signal,
                customInstructions = customInstructions,
                previousSummary = preparation.previousSummary,
            )
        }

    val fileLists = computeFileLists(preparation.fileOps)
    val finalSummary = summary + formatFileOperations(fileLists.readFiles, fileLists.modifiedFiles)
    check(preparation.firstKeptEntryId.isNotBlank()) {
        "First kept entry has no UUID - session may need migration"
    }

    return CompactionResult(
        summary = finalSummary,
        firstKeptEntryId = preparation.firstKeptEntryId,
        tokensBefore = preparation.tokensBefore,
        details = CompactionDetails(readFiles = fileLists.readFiles, modifiedFiles = fileLists.modifiedFiles),
    )
}

private fun extractFileOperations(
    messages: List<AgentMessage>,
    entries: List<SessionEntry>,
    previousCompactionIndex: Int,
): FileOperations {
    val fileOps = createFileOps()

    if (previousCompactionIndex >= 0) {
        val previousCompaction = entries[previousCompactionIndex] as CompactionEntry
        if (previousCompaction.fromHook != true) {
            val details = previousCompaction.details
            val readFiles =
                details
                    ?.jsonObject
                    ?.get("readFiles")
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()
            val modifiedFiles =
                details
                    ?.jsonObject
                    ?.get("modifiedFiles")
                    ?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()

            fileOps.read += readFiles
            fileOps.edited += modifiedFiles
        }
    }

    messages.forEach { extractFileOpsFromMessage(it, fileOps) }
    return fileOps
}

private fun getMessageFromEntry(entry: SessionEntry): AgentMessage? =
    when (entry) {
        is SessionMessageEntry -> entry.message
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
        else -> null
    }

private fun getMessageFromEntryForCompaction(entry: SessionEntry): AgentMessage? =
    if (entry is CompactionEntry) {
        null
    } else {
        getMessageFromEntry(entry)
    }

private suspend fun generateTurnPrefixSummary(
    messages: List<AgentMessage>,
    model: Model<*>,
    reserveTokens: Int,
    apiKey: String,
    headers: Map<String, String> = emptyMap(),
    signal: pi.ai.core.AbortSignal? = null,
): String {
    val maxTokens = kotlin.math.floor(0.5 * reserveTokens).toInt()
    val llmMessages = convertToLlm(messages)
    val conversationText = serializeConversation(llmMessages)
    val promptText = "<conversation>\n$conversationText\n</conversation>\n\n$TURN_PREFIX_SUMMARIZATION_PROMPT"

    val response =
        completeSimple(
            model = model,
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
                    maxTokens = maxTokens,
                    signal = signal,
                    apiKey = apiKey,
                    headers = headers,
                ),
        )

    check(response.stopReason != StopReason.ERROR) {
        "Turn prefix summarization failed: ${response.errorMessage ?: "Unknown error"}"
    }

    return response.content.filterIsInstance<TextContent>().joinToString(separator = "\n") { it.text }
}

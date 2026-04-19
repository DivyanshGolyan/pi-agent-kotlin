package pi.coding.agent.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import pi.agent.core.Agent
import pi.agent.core.AgentEvent
import pi.agent.core.AgentMessage
import pi.agent.core.AgentState
import pi.agent.core.AgentThinkingLevel
import pi.agent.core.QueueMode
import pi.ai.core.AssistantMessage
import pi.ai.core.ImageContent
import pi.ai.core.Model
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.ToolResultMessage
import pi.ai.core.UserContentPart
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import pi.ai.core.modelsAreEqual
import pi.ai.core.supportsXhigh
import pi.coding.agent.core.compaction.BranchSummaryResult
import pi.coding.agent.core.compaction.CompactionDetails
import pi.coding.agent.core.compaction.CompactionResult
import pi.coding.agent.core.compaction.calculateContextTokens
import pi.coding.agent.core.compaction.collectEntriesForBranchSummary
import pi.coding.agent.core.compaction.compact
import pi.coding.agent.core.compaction.estimateContextTokens
import pi.coding.agent.core.compaction.generateBranchSummary
import pi.coding.agent.core.compaction.prepareCompaction
import pi.coding.agent.core.compaction.shouldCompact

public data class ModelScope(
    val model: Model<*>,
    val thinkingLevel: AgentThinkingLevel? = null,
)

public data class PromptOptions(
    val expandPromptTemplates: Boolean = true,
    val images: List<ImageContent> = emptyList(),
    val streamingBehavior: StreamingBehavior? = null,
)

public enum class StreamingBehavior {
    STEER,
    FOLLOW_UP,
}

public sealed interface AgentSessionEvent {
    public val type: String

    public data class Agent(
        val event: AgentEvent,
    ) : AgentSessionEvent {
        override val type: String =
            when (event) {
                is AgentEvent.AgentEnd -> "agent_end"
                AgentEvent.AgentStart -> "agent_start"
                is AgentEvent.MessageEnd -> "message_end"
                is AgentEvent.MessageStart -> "message_start"
                is AgentEvent.MessageUpdate -> "message_update"
                is AgentEvent.ToolExecutionEnd -> "tool_execution_end"
                is AgentEvent.ToolExecutionStart -> "tool_execution_start"
                is AgentEvent.ToolExecutionUpdate -> "tool_execution_update"
                is AgentEvent.TurnEnd -> "turn_end"
                AgentEvent.TurnStart -> "turn_start"
            }
    }

    public data class QueueUpdate(
        val steering: List<String>,
        val followUp: List<String>,
    ) : AgentSessionEvent {
        override val type: String = "queue_update"
    }

    public data class CompactionStart(
        val reason: CompactionReason,
    ) : AgentSessionEvent {
        override val type: String = "compaction_start"
    }

    public data class CompactionEnd(
        val reason: CompactionReason,
        val result: CompactionResult<JsonElement>? = null,
        val aborted: Boolean = false,
        val errorMessage: String? = null,
    ) : AgentSessionEvent {
        override val type: String = "compaction_end"
    }
}

public enum class CompactionReason {
    MANUAL,
    THRESHOLD,
}

public data class ModelCycleResult(
    val model: Model<*>,
    val thinkingLevel: AgentThinkingLevel,
    val isScoped: Boolean,
)

public data class ContextUsage(
    val tokens: Int,
    val contextWindow: Int,
    val remainingTokens: Int,
    val usageTokens: Int,
    val trailingTokens: Int,
)

public data class SessionStats(
    val sessionFile: String?,
    val sessionId: String,
    val userMessages: Int,
    val assistantMessages: Int,
    val toolResults: Int,
    val totalMessages: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int,
    val cacheWriteTokens: Int,
    val totalTokens: Int,
    val totalCost: Double,
    val contextUsage: ContextUsage?,
)

public data class TreeNavigationOptions(
    val summarize: Boolean = false,
    val customInstructions: String? = null,
    val replaceInstructions: Boolean = false,
    val label: String? = null,
)

public data class TreeNavigationResult(
    val editorText: String? = null,
    val cancelled: Boolean,
    val aborted: Boolean = false,
    val summaryEntry: BranchSummaryEntry? = null,
)

public data class AgentSessionConfig(
    val agent: Agent,
    val sessionManager: SessionManager,
    val settingsManager: SettingsManager,
    val cwd: String,
    val resourceLoader: ResourceLoader,
    val modelRegistry: ModelRegistry,
    val scopedModels: List<ModelScope> = emptyList(),
)

@Suppress("LargeClass")
public class AgentSession(
    config: AgentSessionConfig,
) {
    public val agent: Agent = config.agent
    public val sessionManager: SessionManager = config.sessionManager
    public val settingsManager: SettingsManager = config.settingsManager
    public val resourceLoader: ResourceLoader = config.resourceLoader
    public val modelRegistry: ModelRegistry = config.modelRegistry

    private var scopedModelsBacking: List<ModelScope> = config.scopedModels
    private val listeners: MutableList<(AgentSessionEvent) -> Unit> = mutableListOf()
    private var unsubscribeAgent: (() -> Unit)? = null
    private val steeringMessages: MutableList<String> = mutableListOf()
    private val followUpMessages: MutableList<String> = mutableListOf()
    private val pendingNextTurnMessages: MutableList<CustomMessage> = mutableListOf()
    private var compactionAbortController: pi.ai.core.AbortController? = null
    private var branchSummaryAbortController: pi.ai.core.AbortController? = null
    private var autoCompactionEnabled: Boolean = settingsManager.getCompactionSettings().enabled

    init {
        agent.state.systemPrompt = resourceLoader.getSystemPrompt()
        unsubscribeAgent =
            agent.subscribe { event, _ ->
                handleAgentEvent(event)
            }
    }

    public val state: AgentState
        get() = agent.state

    public val model: Model<*>
        get() = state.model

    public val thinkingLevel: AgentThinkingLevel
        get() = state.thinkingLevel

    public val isStreaming: Boolean
        get() = state.isStreaming

    public val systemPrompt: String
        get() = state.systemPrompt

    public val messages: List<AgentMessage>
        get() = state.messages

    public val sessionFile: String?
        get() = sessionManager.getSessionFile()

    public val sessionId: String
        get() = sessionManager.getSessionId()

    public val sessionName: String?
        get() = sessionManager.getSessionName()

    public val steeringMode: QueueMode
        get() = agent.steeringMode

    public val followUpMode: QueueMode
        get() = agent.followUpMode

    public val isCompacting: Boolean
        get() = compactionAbortController != null || branchSummaryAbortController != null

    public val scopedModels: List<ModelScope>
        get() = scopedModelsBacking

    public fun setScopedModels(scopedModels: List<ModelScope>) {
        scopedModelsBacking = scopedModels
    }

    public fun subscribe(listener: (AgentSessionEvent) -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    public fun dispose() {
        unsubscribeAgent?.invoke()
        unsubscribeAgent = null
        listeners.clear()
    }

    private fun emit(event: AgentSessionEvent) {
        listeners.forEach { it(event) }
    }

    private fun emitQueueUpdate() {
        emit(AgentSessionEvent.QueueUpdate(steering = steeringMessages.toList(), followUp = followUpMessages.toList()))
    }

    private suspend fun handleAgentEvent(event: AgentEvent) {
        if (event is AgentEvent.MessageStart && event.message is UserMessage) {
            val text = extractUserMessageText((event.message as UserMessage).content)
            if (text.isNotBlank()) {
                val removedSteering = steeringMessages.remove(text)
                val removedFollowUp = followUpMessages.remove(text)
                if (removedSteering || removedFollowUp) {
                    emitQueueUpdate()
                }
            }
        }

        emit(AgentSessionEvent.Agent(event))

        if (event is AgentEvent.MessageEnd) {
            persistMessage(event.message)
        }

        if (event is AgentEvent.AgentEnd && autoCompactionEnabled) {
            val lastAssistant = state.messages.lastOrNull() as? AssistantMessage
            if (lastAssistant != null && lastAssistant.stopReason == StopReason.STOP) {
                maybeAutoCompact()
            }
        }
    }

    private fun persistMessage(message: AgentMessage) {
        when (message) {
            is UserMessage,
            is AssistantMessage,
            is ToolResultMessage,
            -> sessionManager.appendMessage(message)
            is CustomMessage ->
                sessionManager.appendCustomMessageEntry(
                    customType = message.customType,
                    content = message.content,
                    display = message.display,
                    details = message.details,
                )
            else -> Unit
        }
    }

    public suspend fun prompt(
        text: String,
        options: PromptOptions = PromptOptions(),
    ) {
        if (isStreaming) {
            when (options.streamingBehavior) {
                StreamingBehavior.STEER -> {
                    queueSteer(text, options.images)
                    return
                }
                StreamingBehavior.FOLLOW_UP -> {
                    queueFollowUp(text, options.images)
                    return
                }
                null -> error("Agent is already processing. Specify streamingBehavior to queue the message.")
            }
        }

        val messages = mutableListOf<AgentMessage>()
        messages += userMessage(text = text, images = options.images)
        messages += pendingNextTurnMessages
        pendingNextTurnMessages.clear()
        agent.prompt(messages)
    }

    public suspend fun steer(
        text: String,
        images: List<ImageContent> = emptyList(),
    ) {
        queueSteer(text, images)
    }

    public suspend fun followUp(
        text: String,
        images: List<ImageContent> = emptyList(),
    ) {
        queueFollowUp(text, images)
    }

    private fun queueSteer(
        text: String,
        images: List<ImageContent>,
    ) {
        steeringMessages += text
        emitQueueUpdate()
        agent.steer(userMessage(text, images))
    }

    private fun queueFollowUp(
        text: String,
        images: List<ImageContent>,
    ) {
        followUpMessages += text
        emitQueueUpdate()
        agent.followUp(userMessage(text, images))
    }

    public suspend fun sendCustomMessage(
        message: CustomMessage,
        triggerTurn: Boolean = false,
        deliverAs: StreamingBehavior? = null,
    ) {
        when {
            deliverAs == null && !isStreaming && !triggerTurn -> {
                state.messages = state.messages + message
                sessionManager.appendCustomMessageEntry(
                    customType = message.customType,
                    content = message.content,
                    display = message.display,
                    details = message.details,
                )
                emit(AgentSessionEvent.Agent(AgentEvent.MessageStart(message)))
                emit(AgentSessionEvent.Agent(AgentEvent.MessageEnd(message)))
            }
            deliverAs == StreamingBehavior.STEER -> agent.steer(message)
            deliverAs == StreamingBehavior.FOLLOW_UP -> agent.followUp(message)
            triggerTurn -> agent.prompt(message)
            else -> pendingNextTurnMessages += message
        }
    }

    public suspend fun sendUserMessage(
        content: String,
        deliverAs: StreamingBehavior? = null,
    ) {
        prompt(
            text = content,
            options =
                PromptOptions(
                    expandPromptTemplates = false,
                    streamingBehavior = deliverAs,
                ),
        )
    }

    public fun clearQueue(): Pair<List<String>, List<String>> {
        val steering = steeringMessages.toList()
        val followUp = followUpMessages.toList()
        steeringMessages.clear()
        followUpMessages.clear()
        agent.clearAllQueues()
        emitQueueUpdate()
        return steering to followUp
    }

    public val pendingMessageCount: Int
        get() = steeringMessages.size + followUpMessages.size

    public fun getSteeringMessages(): List<String> = steeringMessages.toList()

    public fun getFollowUpMessages(): List<String> = followUpMessages.toList()

    public suspend fun abort() {
        agent.abort()
        agent.waitForIdle()
    }

    public suspend fun setModel(model: Model<*>) {
        val thinkingLevel = getThinkingLevelForModelSwitch()
        state.model = model
        sessionManager.appendModelChange(model.provider, model.id)
        settingsManager.setDefaultModelAndProvider(model.provider, model.id)
        setThinkingLevel(thinkingLevel)
    }

    public suspend fun cycleModel(direction: String = "forward"): ModelCycleResult? {
        if (scopedModelsBacking.isNotEmpty()) {
            val configured = scopedModelsBacking.filter { modelRegistry.hasConfiguredAuth(it.model) }
            if (configured.size <= 1) {
                return null
            }

            val currentIndex = configured.indexOfFirst { modelsAreEqual(it.model, model) }.takeIf { it >= 0 } ?: 0
            val nextIndex =
                if (direction == "backward") {
                    (currentIndex - 1 + configured.size) % configured.size
                } else {
                    (currentIndex + 1) % configured.size
                }
            val next = configured[nextIndex]
            state.model = next.model
            sessionManager.appendModelChange(next.model.provider, next.model.id)
            settingsManager.setDefaultModelAndProvider(next.model.provider, next.model.id)
            setThinkingLevel(getThinkingLevelForModelSwitch(next.thinkingLevel))
            return ModelCycleResult(model = next.model, thinkingLevel = thinkingLevel, isScoped = true)
        }

        val available = modelRegistry.getAvailable()
        if (available.size <= 1) {
            return null
        }
        val currentIndex = available.indexOfFirst { modelsAreEqual(it, model) }.takeIf { it >= 0 } ?: 0
        val nextIndex =
            if (direction == "backward") {
                (currentIndex - 1 + available.size) % available.size
            } else {
                (currentIndex + 1) % available.size
            }
        val next = available[nextIndex]
        state.model = next
        sessionManager.appendModelChange(next.provider, next.id)
        settingsManager.setDefaultModelAndProvider(next.provider, next.id)
        setThinkingLevel(getThinkingLevelForModelSwitch())
        return ModelCycleResult(model = next, thinkingLevel = thinkingLevel, isScoped = false)
    }

    public fun setThinkingLevel(level: AgentThinkingLevel) {
        val effective = clampThinkingLevel(level)
        if (effective != state.thinkingLevel) {
            state.thinkingLevel = effective
            sessionManager.appendThinkingLevelChange(effective.name.lowercase())
            settingsManager.setDefaultThinkingLevel(effective)
        }
    }

    public fun cycleThinkingLevel(): AgentThinkingLevel? {
        val levels = getAvailableThinkingLevels()
        val currentIndex = levels.indexOf(thinkingLevel)
        val next = levels[(currentIndex + 1) % levels.size]
        setThinkingLevel(next)
        return next
    }

    public fun getAvailableThinkingLevels(): List<AgentThinkingLevel> =
        when {
            !supportsThinking() -> listOf(AgentThinkingLevel.OFF)
            supportsXhighThinking() ->
                listOf(
                    AgentThinkingLevel.OFF,
                    AgentThinkingLevel.MINIMAL,
                    AgentThinkingLevel.LOW,
                    AgentThinkingLevel.MEDIUM,
                    AgentThinkingLevel.HIGH,
                    AgentThinkingLevel.XHIGH,
                )
            else ->
                listOf(
                    AgentThinkingLevel.OFF,
                    AgentThinkingLevel.MINIMAL,
                    AgentThinkingLevel.LOW,
                    AgentThinkingLevel.MEDIUM,
                    AgentThinkingLevel.HIGH,
                )
        }

    public fun supportsXhighThinking(): Boolean = supportsThinking() && supportsXhigh(model)

    public fun supportsThinking(): Boolean = model.reasoning

    private fun getThinkingLevelForModelSwitch(explicitLevel: AgentThinkingLevel? = null): AgentThinkingLevel =
        explicitLevel ?: settingsManager.getDefaultThinkingLevel() ?: thinkingLevel

    private fun clampThinkingLevel(level: AgentThinkingLevel): AgentThinkingLevel {
        val available = getAvailableThinkingLevels()
        return if (available.contains(level)) {
            level
        } else {
            available.last()
        }
    }

    public fun setSteeringMode(mode: QueueMode) {
        agent.steeringMode = mode
        settingsManager.setSteeringMode(mode)
    }

    public fun setFollowUpMode(mode: QueueMode) {
        agent.followUpMode = mode
        settingsManager.setFollowUpMode(mode)
    }

    public suspend fun compact(customInstructions: String? = null): CompactionResult<JsonElement> {
        emit(AgentSessionEvent.CompactionStart(CompactionReason.MANUAL))
        compactionAbortController = pi.ai.core.AbortController()
        try {
            val preparation =
                prepareCompaction(
                    pathEntries = sessionManager.getBranch(),
                    settings = settingsManager.getCompactionSettings(),
                ) ?: error("Nothing to compact")
            val auth = modelRegistry.getApiKeyAndHeaders(model)
            require(auth.ok && auth.apiKey != null) { auth.error ?: "No API key configured for ${model.provider}" }
            val result =
                compact(
                    preparation = preparation,
                    model = model,
                    apiKey = auth.apiKey,
                    headers = auth.headers,
                    customInstructions = customInstructions,
                    signal = compactionAbortController?.signal,
                )
            val detailsJson = result.details?.let(::compactionDetailsToJson)
            sessionManager.appendCompaction(
                summary = result.summary,
                firstKeptEntryId = result.firstKeptEntryId,
                tokensBefore = result.tokensBefore,
                details = detailsJson,
            )
            refreshAgentMessages()
            val persisted =
                CompactionResult(
                    summary = result.summary,
                    firstKeptEntryId = result.firstKeptEntryId,
                    tokensBefore = result.tokensBefore,
                    details = detailsJson,
                )
            emit(AgentSessionEvent.CompactionEnd(CompactionReason.MANUAL, result = persisted))
            return persisted
        } catch (error: Throwable) {
            val aborted = compactionAbortController?.signal?.aborted == true
            emit(
                AgentSessionEvent.CompactionEnd(
                    reason = CompactionReason.MANUAL,
                    aborted = aborted,
                    errorMessage = if (aborted) null else (error.message ?: error.toString()),
                ),
            )
            throw error
        } finally {
            compactionAbortController = null
        }
    }

    private suspend fun maybeAutoCompact() {
        val usage = getContextUsage() ?: return
        val settings = settingsManager.getCompactionSettings()
        if (!shouldCompact(usage.tokens, usage.contextWindow, settings)) {
            return
        }

        emit(AgentSessionEvent.CompactionStart(CompactionReason.THRESHOLD))
        compactionAbortController = pi.ai.core.AbortController()
        try {
            val preparation = prepareCompaction(sessionManager.getBranch(), settings) ?: return
            val auth = modelRegistry.getApiKeyAndHeaders(model)
            if (!auth.ok || auth.apiKey == null) {
                return
            }
            val result =
                compact(
                    preparation = preparation,
                    model = model,
                    apiKey = auth.apiKey,
                    headers = auth.headers,
                    signal = compactionAbortController?.signal,
                )
            val detailsJson = result.details?.let(::compactionDetailsToJson)
            sessionManager.appendCompaction(
                summary = result.summary,
                firstKeptEntryId = result.firstKeptEntryId,
                tokensBefore = result.tokensBefore,
                details = detailsJson,
            )
            refreshAgentMessages()
            emit(
                AgentSessionEvent.CompactionEnd(
                    reason = CompactionReason.THRESHOLD,
                    result =
                        CompactionResult(
                            summary = result.summary,
                            firstKeptEntryId = result.firstKeptEntryId,
                            tokensBefore = result.tokensBefore,
                            details = detailsJson,
                        ),
                ),
            )
        } finally {
            compactionAbortController = null
        }
    }

    public fun abortCompaction() {
        compactionAbortController?.abort()
    }

    public fun abortBranchSummary() {
        branchSummaryAbortController?.abort()
    }

    public fun setSessionName(name: String) {
        sessionManager.appendSessionInfo(name)
    }

    public suspend fun navigateTree(
        targetId: String,
        options: TreeNavigationOptions = TreeNavigationOptions(),
    ): TreeNavigationResult {
        val oldLeafId = sessionManager.getLeafId()
        if (oldLeafId == targetId) {
            return TreeNavigationResult(cancelled = false)
        }

        val targetEntry = sessionManager.getEntry(targetId) ?: error("Entry $targetId not found")
        val (entriesToSummarize, _) = collectEntriesForBranchSummary(sessionManager, oldLeafId, targetId)

        var summaryText: String? = null
        var summaryDetails: JsonElement? = null
        if (options.summarize && entriesToSummarize.isNotEmpty()) {
            branchSummaryAbortController = pi.ai.core.AbortController()
            val auth = modelRegistry.getApiKeyAndHeaders(model)
            require(auth.ok && auth.apiKey != null) { auth.error ?: "No API key configured for ${model.provider}" }
            val result =
                generateBranchSummary(
                    entries = entriesToSummarize,
                    options =
                        pi.coding.agent.core.compaction.GenerateBranchSummaryOptions(
                            model = model,
                            apiKey = auth.apiKey,
                            headers = auth.headers,
                            signal = branchSummaryAbortController?.signal,
                            customInstructions = options.customInstructions,
                            replaceInstructions = options.replaceInstructions,
                            reserveTokens = settingsManager.getBranchSummarySettings().reserveTokens,
                        ),
                )
            if (result.aborted) {
                branchSummaryAbortController = null
                return TreeNavigationResult(cancelled = true, aborted = true)
            }
            if (result.error != null) {
                error(result.error)
            }
            summaryText = result.summary
            summaryDetails = branchSummaryDetailsToJson(result)
            branchSummaryAbortController = null
        }

        var newLeafId: String? = targetId
        var editorText: String? = null
        when (targetEntry) {
            is SessionMessageEntry -> {
                if (targetEntry.message is UserMessage) {
                    newLeafId = targetEntry.parentId
                    editorText = extractUserMessageText(targetEntry.message.content)
                }
            }
            is CustomMessageEntry -> {
                newLeafId = targetEntry.parentId
                editorText = extractUserMessageText(targetEntry.content)
            }
            else -> Unit
        }

        val summaryEntry =
            if (summaryText != null) {
                val summaryId = sessionManager.branchWithSummary(newLeafId, summaryText, summaryDetails, false)
                (sessionManager.getEntry(summaryId) as? BranchSummaryEntry)?.also { entry ->
                    options.label?.let { sessionManager.appendLabelChange(entry.id, it) }
                }
            } else {
                if (newLeafId == null) {
                    sessionManager.resetLeaf()
                } else {
                    sessionManager.branch(newLeafId)
                }
                options.label?.let { sessionManager.appendLabelChange(targetId, it) }
                null
            }

        refreshAgentMessages()
        return TreeNavigationResult(
            editorText = editorText,
            cancelled = false,
            summaryEntry = summaryEntry,
        )
    }

    public fun getUserMessagesForForking(): List<Pair<String, String>> =
        sessionManager
            .getEntries()
            .mapNotNull { entry ->
                when (entry) {
                    is SessionMessageEntry ->
                        (entry.message as? UserMessage)?.let { entry.id to extractUserMessageText(it.content) }
                    else -> null
                }
            }.filter { it.second.isNotBlank() }

    public fun getSessionStats(): SessionStats {
        val assistantMessages = messages.filterIsInstance<AssistantMessage>()
        val userMessages = messages.filterIsInstance<UserMessage>()
        val toolResults = messages.filterIsInstance<ToolResultMessage>()
        val totalInput = assistantMessages.sumOf { it.usage.input }
        val totalOutput = assistantMessages.sumOf { it.usage.output }
        val totalCacheRead = assistantMessages.sumOf { it.usage.cacheRead }
        val totalCacheWrite = assistantMessages.sumOf { it.usage.cacheWrite }
        val totalTokens = assistantMessages.sumOf { calculateContextTokens(it.usage) }
        val totalCost = assistantMessages.sumOf { it.usage.cost.total }
        return SessionStats(
            sessionFile = sessionFile,
            sessionId = sessionId,
            userMessages = userMessages.size,
            assistantMessages = assistantMessages.size,
            toolResults = toolResults.size,
            totalMessages = messages.size,
            inputTokens = totalInput,
            outputTokens = totalOutput,
            cacheReadTokens = totalCacheRead,
            cacheWriteTokens = totalCacheWrite,
            totalTokens = totalTokens,
            totalCost = totalCost,
            contextUsage = getContextUsage(),
        )
    }

    public fun getContextUsage(): ContextUsage? {
        val model = model
        if (model.contextWindow <= 0) {
            return null
        }
        val estimate = estimateContextTokens(messages)
        return ContextUsage(
            tokens = estimate.tokens,
            contextWindow = model.contextWindow,
            remainingTokens = (model.contextWindow - estimate.tokens).coerceAtLeast(0),
            usageTokens = estimate.usageTokens,
            trailingTokens = estimate.trailingTokens,
        )
    }

    public fun getLastAssistantText(): String? =
        messages
            .asReversed()
            .filterIsInstance<AssistantMessage>()
            .firstOrNull()
            ?.content
            ?.filterIsInstance<TextContent>()
            ?.joinToString(separator = "") { it.text }
            ?.ifBlank { null }

    public fun setAutoCompactionEnabled(enabled: Boolean) {
        autoCompactionEnabled = enabled
    }

    public val autoCompactionEnabledValue: Boolean
        get() = autoCompactionEnabled

    private fun refreshAgentMessages() {
        val context = sessionManager.buildSessionContext()
        state.messages = context.messages
    }

    private fun userMessage(
        text: String,
        images: List<ImageContent>,
    ): UserMessage {
        val content =
            UserMessageContent.Structured(
                buildList<UserContentPart> {
                    add(TextContent(text))
                    addAll(images)
                },
            )
        return UserMessage(content = content, timestamp = System.currentTimeMillis())
    }

    private fun extractUserMessageText(content: UserMessageContent): String =
        when (content) {
            is UserMessageContent.Text -> content.value
            is UserMessageContent.Structured -> content.parts.filterIsInstance<TextContent>().joinToString(separator = "") { it.text }
        }

    private fun branchSummaryDetailsToJson(result: BranchSummaryResult): JsonElement =
        buildJsonObject {
            put(
                "readFiles",
                buildJsonArray {
                    result.readFiles.orEmpty().forEach { add(JsonPrimitive(it)) }
                },
            )
            put(
                "modifiedFiles",
                buildJsonArray {
                    result.modifiedFiles.orEmpty().forEach { add(JsonPrimitive(it)) }
                },
            )
        }

    private fun compactionDetailsToJson(details: CompactionDetails): JsonElement =
        buildJsonObject {
            put(
                "readFiles",
                buildJsonArray {
                    details.readFiles.forEach { add(JsonPrimitive(it)) }
                },
            )
            put(
                "modifiedFiles",
                buildJsonArray {
                    details.modifiedFiles.forEach { add(JsonPrimitive(it)) }
                },
            )
        }
}

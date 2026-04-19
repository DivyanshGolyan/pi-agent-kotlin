package pi.agent.core

import kotlinx.coroutines.CompletableDeferred
import pi.ai.core.AbortController
import pi.ai.core.AbortSignal
import pi.ai.core.AssistantMessage
import pi.ai.core.CacheRetention
import pi.ai.core.ImageContent
import pi.ai.core.Message
import pi.ai.core.Model
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.ThinkingBudgets
import pi.ai.core.ToolResultMessage
import pi.ai.core.Transport
import pi.ai.core.Usage
import pi.ai.core.UsageCost
import pi.ai.core.streamSimple

private val emptyUsage: Usage =
    Usage(
        input = 0,
        output = 0,
        cacheRead = 0,
        cacheWrite = 0,
        totalTokens = 0,
        cost = UsageCost(0.0, 0.0, 0.0, 0.0, 0.0),
    )

private val defaultModel: Model<String> =
    Model(
        id = "unknown",
        name = "unknown",
        api = "unknown",
        provider = "unknown",
        baseUrl = "",
        reasoning = false,
        input = emptySet(),
        cost = pi.ai.core.ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 0,
        maxTokens = 0,
    )

private class MutableAgentState(
    initialState: InitialAgentState?,
) : AgentState {
    override var systemPrompt: String = initialState?.systemPrompt ?: ""
    override var model: Model<*> = initialState?.model ?: defaultModel
    override var thinkingLevel: AgentThinkingLevel = initialState?.thinkingLevel ?: AgentThinkingLevel.OFF

    private var toolsBacking: List<AgentTool<*>> = initialState?.tools?.toList() ?: emptyList()
    private var messagesBacking: List<AgentMessage> = initialState?.messages?.toList() ?: emptyList()

    override var tools: List<AgentTool<*>>
        get() = toolsBacking
        set(value) {
            toolsBacking = value.toList()
        }

    override var messages: List<AgentMessage>
        get() = messagesBacking
        set(value) {
            messagesBacking = value.toList()
        }

    override var isStreaming: Boolean = false
        private set
    override var streamingMessage: AgentMessage? = null
        private set
    override var pendingToolCalls: Set<String> = emptySet()
        private set
    override var errorMessage: String? = null
        private set

    fun setStreaming(value: Boolean) {
        isStreaming = value
    }

    fun setStreamingMessage(value: AgentMessage?) {
        streamingMessage = value
    }

    fun setPendingToolCalls(value: Set<String>) {
        pendingToolCalls = value
    }

    fun setErrorMessage(value: String?) {
        errorMessage = value
    }
}

private class PendingMessageQueue(
    public var mode: QueueMode,
) {
    private val messages: MutableList<AgentMessage> = mutableListOf()

    fun enqueue(message: AgentMessage) {
        messages += message
    }

    fun hasItems(): Boolean = messages.isNotEmpty()

    fun drain(): List<AgentMessage> =
        when (mode) {
            QueueMode.ALL -> messages.toList().also { messages.clear() }
            QueueMode.ONE_AT_A_TIME ->
                messages.firstOrNull()?.let { first ->
                    messages.removeAt(0)
                    listOf(first)
                } ?: emptyList()
        }

    fun clear() {
        messages.clear()
    }
}

private data class ActiveRun(
    val promise: CompletableDeferred<Unit>,
    val abortController: AbortController,
)

public class Agent
    @JvmOverloads
    constructor(
        options: AgentOptions = AgentOptions(),
    ) {
        private val stateBacking: MutableAgentState = MutableAgentState(options.initialState)
        private val listeners: MutableSet<suspend (AgentEvent, AbortSignal) -> Unit> = linkedSetOf()
        private val steeringQueue: PendingMessageQueue = PendingMessageQueue(options.steeringMode)
        private val followUpQueue: PendingMessageQueue = PendingMessageQueue(options.followUpMode)

        public var convertToLlm: suspend (List<AgentMessage>) -> List<Message> =
            options.convertToLlm ?: { messages -> defaultConvertToLlm(messages, options.customMessageToLlm) }
        public var transformContext: (suspend (List<AgentMessage>, AbortSignal?) -> List<AgentMessage>)? = options.transformContext
        public var streamFn: StreamFn = options.streamFn ?: ::streamSimple
        public var getApiKey: (suspend (String) -> String?)? = options.getApiKey
        public var onPayload: (suspend (payload: Any, model: Model<*>) -> Any?)? = options.onPayload
        public var onResponse: (suspend (response: pi.ai.core.ProviderResponse, model: Model<*>) -> Unit)? = options.onResponse
        public var beforeToolCall: (suspend (BeforeToolCallContext, AbortSignal?) -> BeforeToolCallResult?)? = options.beforeToolCall
        public var afterToolCall: (suspend (AfterToolCallContext, AbortSignal?) -> AfterToolCallResult?)? = options.afterToolCall
        public var cacheRetention: CacheRetention? = options.cacheRetention
        public var sessionId: String? = options.sessionId
        public var thinkingBudgets: ThinkingBudgets? = options.thinkingBudgets
        public var transport: Transport = options.transport
        public var maxRetryDelayMs: Long? = options.maxRetryDelayMs
        public var toolExecution: ToolExecutionMode = options.toolExecution

        private var activeRun: ActiveRun? = null

        public val state: AgentState
            get() = stateBacking

        public var steeringMode: QueueMode
            get() = steeringQueue.mode
            set(value) {
                steeringQueue.mode = value
            }

        public var followUpMode: QueueMode
            get() = followUpQueue.mode
            set(value) {
                followUpQueue.mode = value
            }

        public fun subscribe(listener: suspend (AgentEvent, AbortSignal) -> Unit): () -> Unit {
            listeners += listener
            return { listeners.remove(listener) }
        }

        public fun steer(message: AgentMessage): Unit = steeringQueue.enqueue(message)

        public fun followUp(message: AgentMessage): Unit = followUpQueue.enqueue(message)

        public fun clearSteeringQueue(): Unit = steeringQueue.clear()

        public fun clearFollowUpQueue(): Unit = followUpQueue.clear()

        public fun clearAllQueues() {
            clearSteeringQueue()
            clearFollowUpQueue()
        }

        public fun hasQueuedMessages(): Boolean = steeringQueue.hasItems() || followUpQueue.hasItems()

        public val signal: AbortSignal?
            get() = activeRun?.abortController?.signal

        public fun abort() {
            activeRun?.abortController?.abort()
        }

        public suspend fun waitForIdle() {
            activeRun?.promise?.await()
        }

        public fun reset() {
            stateBacking.messages = emptyList()
            stateBacking.setStreaming(false)
            stateBacking.setStreamingMessage(null)
            stateBacking.setPendingToolCalls(emptySet())
            stateBacking.setErrorMessage(null)
            clearAllQueues()
        }

        public suspend fun prompt(message: AgentMessage): Unit = runPromptMessages(listOf(message))

        public suspend fun prompt(messages: List<AgentMessage>): Unit = runPromptMessages(messages)

        public suspend fun prompt(
            input: String,
            images: List<ImageContent> = emptyList(),
        ): Unit = runPromptMessages(listOf(stringPromptMessage(input, images)))

        public suspend fun `continue`() {
            require(activeRun == null) { "Agent is already processing. Wait for completion before continuing." }

            val lastMessage = stateBacking.messages.lastOrNull() ?: error("No messages to continue from")
            if (lastMessage is AssistantMessage) {
                val queuedSteering = steeringQueue.drain()
                if (queuedSteering.isNotEmpty()) {
                    runPromptMessages(queuedSteering, skipInitialSteeringPoll = true)
                    return
                }

                val queuedFollowUps = followUpQueue.drain()
                if (queuedFollowUps.isNotEmpty()) {
                    runPromptMessages(queuedFollowUps)
                    return
                }

                error("Cannot continue from message role: assistant")
            }

            runContinuation()
        }

        private suspend fun runPromptMessages(
            messages: List<AgentMessage>,
            skipInitialSteeringPoll: Boolean = false,
        ): Unit =
            runWithLifecycle { signal ->
                runAgentLoop(
                    prompts = messages,
                    context = createContextSnapshot(),
                    config = createLoopConfig(skipInitialSteeringPoll),
                    emit = ::processEvents,
                    signal = signal,
                    streamFn = streamFn,
                )
            }

        private suspend fun runContinuation(): Unit =
            runWithLifecycle { signal ->
                runAgentLoopContinue(
                    context = createContextSnapshot(),
                    config = createLoopConfig(),
                    emit = ::processEvents,
                    signal = signal,
                    streamFn = streamFn,
                )
            }

        private fun createContextSnapshot(): AgentContext =
            AgentContext(
                systemPrompt = stateBacking.systemPrompt,
                messages = stateBacking.messages.toMutableList(),
                tools = stateBacking.tools.toList(),
            )

        private fun createLoopConfig(skipInitialSteeringPoll: Boolean = false): AgentLoopConfig {
            var shouldSkipInitialSteeringPoll = skipInitialSteeringPoll
            return AgentLoopConfig(
                model = stateBacking.model,
                reasoning = stateBacking.thinkingLevel,
                convertToLlm = convertToLlm,
                transformContext = transformContext,
                getApiKey = getApiKey,
                getSteeringMessages = {
                    if (shouldSkipInitialSteeringPoll) {
                        shouldSkipInitialSteeringPoll = false
                        emptyList()
                    } else {
                        steeringQueue.drain()
                    }
                },
                getFollowUpMessages = { followUpQueue.drain() },
                toolExecution = toolExecution,
                beforeToolCall = beforeToolCall,
                afterToolCall = afterToolCall,
                cacheRetention = cacheRetention,
                sessionId = sessionId,
                onPayload = onPayload,
                onResponse = onResponse,
                transport = transport,
                thinkingBudgets = thinkingBudgets,
                maxRetryDelayMs = maxRetryDelayMs,
            )
        }

        private suspend fun runWithLifecycle(executor: suspend (AbortSignal) -> Unit) {
            require(activeRun == null) { "Agent is already processing." }

            val active =
                ActiveRun(
                    promise = CompletableDeferred(),
                    abortController = AbortController(),
                )
            activeRun = active

            stateBacking.setStreaming(true)
            stateBacking.setStreamingMessage(null)
            stateBacking.setErrorMessage(null)

            try {
                executor(active.abortController.signal)
            } catch (error: Throwable) {
                handleRunFailure(error, active.abortController.signal.aborted)
            } finally {
                finishRun()
                active.promise.complete(Unit)
            }
        }

        private suspend fun handleRunFailure(
            error: Throwable,
            aborted: Boolean,
        ) {
            val failureMessage =
                AssistantMessage(
                    content = mutableListOf(TextContent("")),
                    api = stateBacking.model.api,
                    provider = stateBacking.model.provider,
                    model = stateBacking.model.id,
                    usage = emptyUsage.copy(cost = emptyUsage.cost.copy()),
                    stopReason = if (aborted) StopReason.ABORTED else StopReason.ERROR,
                    errorMessage = error.message ?: error.toString(),
                    timestamp = System.currentTimeMillis(),
                )
            stateBacking.messages = stateBacking.messages + failureMessage
            stateBacking.setErrorMessage(failureMessage.errorMessage)
            processEvents(AgentEvent.AgentEnd(listOf(failureMessage)))
        }

        private fun finishRun() {
            stateBacking.setStreaming(false)
            stateBacking.setStreamingMessage(null)
            stateBacking.setPendingToolCalls(emptySet())
            activeRun = null
        }

        private suspend fun processEvents(event: AgentEvent) {
            when (event) {
                is AgentEvent.MessageStart -> stateBacking.setStreamingMessage(event.message)
                is AgentEvent.MessageUpdate -> stateBacking.setStreamingMessage(event.message)
                is AgentEvent.MessageEnd -> {
                    stateBacking.setStreamingMessage(null)
                    stateBacking.messages = stateBacking.messages + event.message
                }

                is AgentEvent.ToolExecutionStart -> {
                    val pending = stateBacking.pendingToolCalls.toMutableSet()
                    pending += event.toolCallId
                    stateBacking.setPendingToolCalls(pending)
                }

                is AgentEvent.ToolExecutionEnd -> {
                    val pending = stateBacking.pendingToolCalls.toMutableSet()
                    pending -= event.toolCallId
                    stateBacking.setPendingToolCalls(pending)
                }

                is AgentEvent.ToolExecutionUpdate -> Unit

                is AgentEvent.TurnEnd -> {
                    val message = event.message
                    if (message is AssistantMessage && !message.errorMessage.isNullOrBlank()) {
                        stateBacking.setErrorMessage(message.errorMessage)
                    }
                }

                is AgentEvent.AgentEnd -> stateBacking.setStreamingMessage(null)
                AgentEvent.AgentStart, AgentEvent.TurnStart -> Unit
            }

            val signal = activeRun?.abortController?.signal ?: error("Agent listener invoked outside active run")
            listeners.forEach { listener -> listener(event, signal) }
        }
    }

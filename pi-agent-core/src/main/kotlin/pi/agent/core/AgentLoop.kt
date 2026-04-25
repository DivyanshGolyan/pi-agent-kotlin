package pi.agent.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import pi.ai.core.AbortSignal
import pi.ai.core.AssistantMessage
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.Context
import pi.ai.core.EventStream
import pi.ai.core.SimpleStreamOptions
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultMessage
import pi.ai.core.streamSimple
import pi.ai.core.validateToolArguments

public typealias AgentEventSink = suspend (AgentEvent) -> Unit

private val loopScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

public fun agentLoop(
    prompts: List<AgentMessage>,
    context: AgentContext,
    config: AgentLoopConfig,
    signal: AbortSignal? = null,
    streamFn: StreamFn? = null,
): EventStream<AgentEvent, List<AgentMessage>> {
    val stream = createAgentStream()
    loopScope.launchIgnoringChildren {
        val messages = runAgentLoop(prompts, context, config, stream::push, signal, streamFn)
        stream.end(messages)
    }
    return stream
}

public fun agentLoopContinue(
    context: AgentContext,
    config: AgentLoopConfig,
    signal: AbortSignal? = null,
    streamFn: StreamFn? = null,
): EventStream<AgentEvent, List<AgentMessage>> {
    require(context.messages.isNotEmpty()) { "Cannot continue: no messages in context" }
    require(context.messages.last() !is AssistantMessage) { "Cannot continue from message role: assistant" }

    val stream = createAgentStream()
    loopScope.launchIgnoringChildren {
        val messages = runAgentLoopContinue(context, config, stream::push, signal, streamFn)
        stream.end(messages)
    }
    return stream
}

public suspend fun runAgentLoop(
    prompts: List<AgentMessage>,
    context: AgentContext,
    config: AgentLoopConfig,
    emit: AgentEventSink,
    signal: AbortSignal? = null,
    streamFn: StreamFn? = null,
): List<AgentMessage> {
    val newMessages: MutableList<AgentMessage> = prompts.toMutableList()
    val currentContext = context.copy(messages = (context.messages + prompts).toMutableList())

    emit(AgentEvent.AgentStart)
    emit(AgentEvent.TurnStart)
    prompts.forEach { prompt ->
        emit(AgentEvent.MessageStart(prompt.copyForEvent()))
        emit(AgentEvent.MessageEnd(prompt))
    }

    runLoop(currentContext, newMessages, config, signal, emit, streamFn)
    return newMessages
}

public suspend fun runAgentLoopContinue(
    context: AgentContext,
    config: AgentLoopConfig,
    emit: AgentEventSink,
    signal: AbortSignal? = null,
    streamFn: StreamFn? = null,
): List<AgentMessage> {
    require(context.messages.isNotEmpty()) { "Cannot continue: no messages in context" }
    require(context.messages.last() !is AssistantMessage) { "Cannot continue from message role: assistant" }

    val newMessages: MutableList<AgentMessage> = mutableListOf()
    emit(AgentEvent.AgentStart)
    emit(AgentEvent.TurnStart)

    runLoop(context.copy(), newMessages, config, signal, emit, streamFn)
    return newMessages
}

private fun createAgentStream(): EventStream<AgentEvent, List<AgentMessage>> =
    EventStream(
        isComplete = { event -> event is AgentEvent.AgentEnd },
        extractResult = { event -> (event as AgentEvent.AgentEnd).messages },
    )

private suspend fun runLoop(
    currentContext: AgentContext,
    newMessages: MutableList<AgentMessage>,
    config: AgentLoopConfig,
    signal: AbortSignal?,
    emit: AgentEventSink,
    streamFn: StreamFn?,
) {
    var firstTurn = true
    var pendingMessages: List<AgentMessage> = config.getSteeringMessages?.invoke().orEmpty()

    while (true) {
        var hasMoreToolCalls = true

        while (hasMoreToolCalls || pendingMessages.isNotEmpty()) {
            if (!firstTurn) {
                emit(AgentEvent.TurnStart)
            } else {
                firstTurn = false
            }

            if (pendingMessages.isNotEmpty()) {
                pendingMessages.forEach { message ->
                    emit(AgentEvent.MessageStart(message.copyForEvent()))
                    emit(AgentEvent.MessageEnd(message))
                    currentContext.messages += message
                    newMessages += message
                }
                pendingMessages = emptyList()
            }

            val message = streamAssistantResponse(currentContext, config, signal, emit, streamFn)
            newMessages += message

            if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.ABORTED) {
                emit(AgentEvent.TurnEnd(message, emptyList()))
                emit(AgentEvent.AgentEnd(newMessages.toList()))
                return
            }

            val toolCalls = message.content.filterIsInstance<ToolCall>()
            hasMoreToolCalls = toolCalls.isNotEmpty()

            val toolExecution =
                if (hasMoreToolCalls) {
                    executeToolCalls(currentContext, message, config, signal, emit)
                } else {
                    ToolExecutionBatch(emptyList(), terminal = false)
                }

            toolExecution.messages.forEach { result ->
                currentContext.messages += result
                newMessages += result
            }

            emit(AgentEvent.TurnEnd(message, toolExecution.messages))
            if (toolExecution.terminal) {
                emit(AgentEvent.AgentEnd(newMessages.toList()))
                return
            }
            pendingMessages = config.getSteeringMessages?.invoke().orEmpty()
        }

        val followUpMessages = config.getFollowUpMessages?.invoke().orEmpty()
        if (followUpMessages.isNotEmpty()) {
            pendingMessages = followUpMessages
            continue
        }
        break
    }

    emit(AgentEvent.AgentEnd(newMessages.toList()))
}

private suspend fun streamAssistantResponse(
    context: AgentContext,
    config: AgentLoopConfig,
    signal: AbortSignal?,
    emit: AgentEventSink,
    streamFn: StreamFn?,
): AssistantMessage {
    var messages: List<AgentMessage> = context.messages
    if (config.transformContext != null) {
        messages = config.transformContext.invoke(messages, signal)
    }

    val llmMessages = config.convertToLlm(messages)
    val llmContext =
        Context(
            systemPrompt = context.systemPrompt,
            messages = llmMessages,
            tools = context.tools,
        )

    val streamFunction: StreamFn =
        streamFn ?: { model, runtimeContext, options ->
            streamSimple(model, runtimeContext, options)
        }
    val resolvedApiKey = config.getApiKey?.invoke(config.model.provider) ?: config.apiKey
    val response =
        streamFunction.invoke(
            config.model,
            llmContext,
            config.toSimpleStreamOptions(resolvedApiKey, signal),
        )

    var partialMessage: AssistantMessage? = null
    var addedPartial = false

    response.asFlow().collect { event: AssistantMessageEvent ->
        when (event) {
            is AssistantMessageEvent.Start -> {
                partialMessage = event.partial
                context.messages += partialMessage!!
                addedPartial = true
                emit(AgentEvent.MessageStart(partialMessage!!.copyForEvent()))
            }

            is AssistantMessageEvent.TextStart,
            is AssistantMessageEvent.TextDelta,
            is AssistantMessageEvent.TextEnd,
            is AssistantMessageEvent.ThinkingStart,
            is AssistantMessageEvent.ThinkingDelta,
            is AssistantMessageEvent.ThinkingEnd,
            is AssistantMessageEvent.ToolCallStart,
            is AssistantMessageEvent.ToolCallDelta,
            is AssistantMessageEvent.ToolCallEnd,
            -> {
                if (partialMessage != null) {
                    val updatedMessage = event.partial
                    partialMessage = updatedMessage
                    context.messages[context.messages.lastIndex] = updatedMessage
                    emit(AgentEvent.MessageUpdate(updatedMessage.copyForEvent(), event))
                }
            }

            is AssistantMessageEvent.Done,
            is AssistantMessageEvent.Error,
            -> {
                Unit
            }
        }
    }

    val finalMessage = response.result()
    if (addedPartial) {
        context.messages[context.messages.lastIndex] = finalMessage
    } else {
        context.messages += finalMessage
        emit(AgentEvent.MessageStart(finalMessage.copyForEvent()))
    }
    emit(AgentEvent.MessageEnd(finalMessage))
    return finalMessage
}

private suspend fun executeToolCalls(
    currentContext: AgentContext,
    assistantMessage: AssistantMessage,
    config: AgentLoopConfig,
    signal: AbortSignal?,
    emit: AgentEventSink,
): ToolExecutionBatch =
    when (config.toolExecution) {
        ToolExecutionMode.SEQUENTIAL -> executeToolCallsSequential(currentContext, assistantMessage, config, signal, emit)
        ToolExecutionMode.PARALLEL -> executeToolCallsParallel(currentContext, assistantMessage, config, signal, emit)
    }

private data class ToolExecutionBatch(
    val messages: List<ToolResultMessage>,
    val terminal: Boolean,
)

private data class ToolCallResultMessage(
    val message: ToolResultMessage,
    val terminal: Boolean,
)

private suspend fun executeToolCallsSequential(
    currentContext: AgentContext,
    assistantMessage: AssistantMessage,
    config: AgentLoopConfig,
    signal: AbortSignal?,
    emit: AgentEventSink,
): ToolExecutionBatch {
    val results = mutableListOf<ToolResultMessage>()
    val toolCalls = assistantMessage.content.filterIsInstance<ToolCall>()
    var terminal = false

    for (toolCall in toolCalls) {
        emit(AgentEvent.ToolExecutionStart(toolCall.id, toolCall.name, toolCall.arguments))
        val result =
            when (val preparation = prepareToolCall(currentContext, assistantMessage, toolCall, config, signal)) {
                is ImmediateToolCallOutcome -> emitToolCallOutcome(toolCall, preparation.result, preparation.isError, emit)
                is PreparedToolCall -> {
                    val executed = executePreparedToolCall(preparation, signal, emit)
                    finalizeExecutedToolCall(currentContext, assistantMessage, preparation, executed, config, signal, emit)
                }
            }
        results += result.message
        if (result.terminal) {
            terminal = true
            break
        }
    }

    return ToolExecutionBatch(results, terminal)
}

private suspend fun executeToolCallsParallel(
    currentContext: AgentContext,
    assistantMessage: AssistantMessage,
    config: AgentLoopConfig,
    signal: AbortSignal?,
    emit: AgentEventSink,
): ToolExecutionBatch =
    coroutineScope {
        val results = mutableListOf<ToolResultMessage>()
        val runnableCalls = mutableListOf<PreparedToolCall>()
        val toolCalls = assistantMessage.content.filterIsInstance<ToolCall>()
        var terminal = false

        for (toolCall in toolCalls) {
            emit(AgentEvent.ToolExecutionStart(toolCall.id, toolCall.name, toolCall.arguments))
            when (val preparation = prepareToolCall(currentContext, assistantMessage, toolCall, config, signal)) {
                is ImmediateToolCallOutcome -> {
                    val result = emitToolCallOutcome(toolCall, preparation.result, preparation.isError, emit)
                    results += result.message
                    terminal = terminal || result.terminal
                }
                is PreparedToolCall -> runnableCalls += preparation
            }
        }

        val runningCalls =
            runnableCalls.map { prepared ->
                prepared to
                    async(start = CoroutineStart.UNDISPATCHED) {
                        executePreparedToolCall(prepared, signal, emit)
                    }
            }

        runningCalls.forEach { (prepared, execution) ->
            val executed = execution.await()
            val result = finalizeExecutedToolCall(currentContext, assistantMessage, prepared, executed, config, signal, emit)
            results += result.message
            terminal = terminal || result.terminal
        }

        ToolExecutionBatch(results, terminal)
    }

private sealed interface ToolCallPreparation

private data class PreparedToolCall(
    val toolCall: AgentToolCall,
    val tool: AgentTool<Any?>,
    val args: Any?,
) : ToolCallPreparation

private data class ImmediateToolCallOutcome(
    val result: AgentToolResult<JsonElement>,
    val isError: Boolean,
) : ToolCallPreparation

private data class ExecutedToolCallOutcome(
    val result: AgentToolResult<JsonElement>,
    val isError: Boolean,
)

private fun prepareToolCallArguments(
    tool: AgentTool<*>,
    toolCall: ToolCall,
): ToolCall {
    val preparedArguments = tool.prepareArguments(toolCall.arguments)
    return if (preparedArguments == toolCall.arguments) {
        toolCall
    } else {
        toolCall.copy(arguments = preparedArguments)
    }
}

@Suppress("UNCHECKED_CAST")
private suspend fun prepareToolCall(
    currentContext: AgentContext,
    assistantMessage: AssistantMessage,
    toolCall: ToolCall,
    config: AgentLoopConfig,
    signal: AbortSignal?,
): ToolCallPreparation {
    val tool =
        currentContext.tools.firstOrNull { it.name == toolCall.name }
            ?: return ImmediateToolCallOutcome(createErrorToolResult("Tool ${toolCall.name} not found"), true)

    return try {
        val preparedToolCall = prepareToolCallArguments(tool, toolCall)
        val validatedArgs = validateToolArguments(tool as AgentTool<Any?>, preparedToolCall)
        val beforeResult =
            config.beforeToolCall?.invoke(
                BeforeToolCallContext(assistantMessage, toolCall, validatedArgs, currentContext),
                signal,
            )
        if (beforeResult?.block == true) {
            ImmediateToolCallOutcome(
                createErrorToolResult(beforeResult.reason ?: "Tool execution was blocked"),
                true,
            )
        } else {
            PreparedToolCall(toolCall, tool, validatedArgs)
        }
    } catch (error: Throwable) {
        ImmediateToolCallOutcome(createErrorToolResult(error.message ?: error.toString()), true)
    }
}

private suspend fun executePreparedToolCall(
    prepared: PreparedToolCall,
    signal: AbortSignal?,
    emit: AgentEventSink,
): ExecutedToolCallOutcome =
    try {
        val result =
            prepared.tool.execute(
                prepared.toolCall.id,
                prepared.args,
                signal,
            ) { partialResult ->
                emit(
                    AgentEvent.ToolExecutionUpdate(
                        prepared.toolCall.id,
                        prepared.toolCall.name,
                        prepared.toolCall.arguments,
                        partialResult,
                    ),
                )
            }
        ExecutedToolCallOutcome(result, false)
    } catch (error: Throwable) {
        ExecutedToolCallOutcome(createErrorToolResult(error.message ?: error.toString()), true)
    }

private suspend fun finalizeExecutedToolCall(
    currentContext: AgentContext,
    assistantMessage: AssistantMessage,
    prepared: PreparedToolCall,
    executed: ExecutedToolCallOutcome,
    config: AgentLoopConfig,
    signal: AbortSignal?,
    emit: AgentEventSink,
): ToolCallResultMessage {
    var result = executed.result
    var isError = executed.isError

    if (config.afterToolCall != null) {
        try {
            val afterResult =
                config.afterToolCall.invoke(
                    AfterToolCallContext(
                        assistantMessage = assistantMessage,
                        toolCall = prepared.toolCall,
                        args = prepared.args,
                        result = result,
                        isError = isError,
                        context = currentContext,
                    ),
                    signal,
                )
            if (afterResult != null) {
                result =
                    AgentToolResult(
                        content = afterResult.content ?: result.content,
                        details = afterResult.details ?: result.details,
                        terminal = afterResult.terminal ?: result.terminal,
                    )
                isError = afterResult.isError ?: isError
            }
        } catch (error: Throwable) {
            result = createErrorToolResult(error.message ?: error.toString())
            isError = true
        }
    }

    return emitToolCallOutcome(prepared.toolCall, result, isError, emit)
}

private fun createErrorToolResult(message: String): AgentToolResult<JsonElement> =
    AgentToolResult(
        content = listOf(TextContent(message)),
        details = buildJsonObject {},
    )

private suspend fun emitToolCallOutcome(
    toolCall: AgentToolCall,
    result: AgentToolResult<*>,
    isError: Boolean,
    emit: AgentEventSink,
): ToolCallResultMessage {
    emit(AgentEvent.ToolExecutionEnd(toolCall.id, toolCall.name, result, isError))

    val toolResultMessage =
        ToolResultMessage(
            toolCallId = toolCall.id,
            toolName = toolCall.name,
            content = result.content,
            details = result.details as? JsonElement,
            isError = isError,
            timestamp = System.currentTimeMillis(),
        )

    emit(AgentEvent.MessageStart(toolResultMessage.copyForEvent()))
    emit(AgentEvent.MessageEnd(toolResultMessage))
    return ToolCallResultMessage(toolResultMessage, result.terminal)
}

private fun AgentLoopConfig.toSimpleStreamOptions(
    apiKey: String?,
    signal: AbortSignal?,
): SimpleStreamOptions =
    SimpleStreamOptions(
        reasoning = reasoning?.toPiThinkingLevelOrNull(),
        thinkingBudgets = thinkingBudgets,
        temperature = temperature,
        maxTokens = maxTokens,
        signal = signal,
        apiKey = apiKey,
        transport = transport,
        cacheRetention = cacheRetention,
        sessionId = sessionId,
        onPayload = onPayload,
        onResponse = onResponse,
        headers = headers,
        maxRetryDelayMs = maxRetryDelayMs,
        metadata = metadata,
    )

private fun CoroutineScope.launchIgnoringChildren(block: suspend () -> Unit) {
    launch {
        try {
            block()
        } catch (_: Throwable) {
            // Agent loop failures are encoded in the event stream by the caller paths that use it.
        }
    }
}

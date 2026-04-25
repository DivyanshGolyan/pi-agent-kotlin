package pi.agent.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import pi.ai.core.AbortSignal
import pi.ai.core.AssistantMessage
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.CacheRetention
import pi.ai.core.Context
import pi.ai.core.ImageContent
import pi.ai.core.Message
import pi.ai.core.Model
import pi.ai.core.ProviderResponse
import pi.ai.core.SimpleStreamOptions
import pi.ai.core.TextContent
import pi.ai.core.ThinkingBudgets
import pi.ai.core.ThinkingLevel
import pi.ai.core.Tool
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultContentPart
import pi.ai.core.ToolResultMessage
import pi.ai.core.Transport

/**
 * Agent-level message contract.
 *
 * This includes the base pi-ai messages plus any custom messages that implement
 * [CustomAgentMessage].
 */
public typealias AgentMessage = Message
public typealias AgentToolCall = ToolCall
public typealias StreamFn = suspend (Model<*>, Context, SimpleStreamOptions?) -> pi.ai.core.AssistantMessageEventStream

public enum class ToolExecutionMode {
    SEQUENTIAL,
    PARALLEL,
}

public enum class AgentThinkingLevel {
    OFF,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
}

internal fun AgentThinkingLevel.toPiThinkingLevelOrNull(): ThinkingLevel? =
    when (this) {
        AgentThinkingLevel.OFF -> null
        AgentThinkingLevel.MINIMAL -> ThinkingLevel.MINIMAL
        AgentThinkingLevel.LOW -> ThinkingLevel.LOW
        AgentThinkingLevel.MEDIUM -> ThinkingLevel.MEDIUM
        AgentThinkingLevel.HIGH -> ThinkingLevel.HIGH
        AgentThinkingLevel.XHIGH -> ThinkingLevel.XHIGH
    }

public data class BeforeToolCallResult(
    val block: Boolean = false,
    val reason: String? = null,
)

public data class AfterToolCallResult(
    val content: List<ToolResultContentPart>? = null,
    val details: JsonElement? = null,
    val isError: Boolean? = null,
    val terminal: Boolean? = null,
)

public data class BeforeToolCallContext(
    val assistantMessage: AssistantMessage,
    val toolCall: AgentToolCall,
    val args: Any?,
    val context: AgentContext,
)

public data class AfterToolCallContext(
    val assistantMessage: AssistantMessage,
    val toolCall: AgentToolCall,
    val args: Any?,
    val result: AgentToolResult<*>,
    val isError: Boolean,
    val context: AgentContext,
)

public data class AgentLoopConfig(
    val model: Model<*>,
    val convertToLlm: suspend (List<AgentMessage>) -> List<Message>,
    val reasoning: AgentThinkingLevel? = null,
    val transformContext: (suspend (List<AgentMessage>, AbortSignal?) -> List<AgentMessage>)? = null,
    val getApiKey: (suspend (String) -> String?)? = null,
    val getSteeringMessages: (suspend () -> List<AgentMessage>)? = null,
    val getFollowUpMessages: (suspend () -> List<AgentMessage>)? = null,
    val toolExecution: ToolExecutionMode = ToolExecutionMode.PARALLEL,
    val beforeToolCall: (suspend (BeforeToolCallContext, AbortSignal?) -> BeforeToolCallResult?)? = null,
    val afterToolCall: (suspend (AfterToolCallContext, AbortSignal?) -> AfterToolCallResult?)? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val apiKey: String? = null,
    val transport: Transport? = null,
    val cacheRetention: pi.ai.core.CacheRetention? = null,
    val sessionId: String? = null,
    val onPayload: (suspend (payload: Any, model: Model<*>) -> Any?)? = null,
    val onResponse: (suspend (response: ProviderResponse, model: Model<*>) -> Unit)? = null,
    val headers: Map<String, String> = emptyMap(),
    val maxRetryDelayMs: Long? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val thinkingBudgets: ThinkingBudgets? = null,
)

public interface AgentState {
    public var systemPrompt: String
    public var model: Model<*>
    public var thinkingLevel: AgentThinkingLevel
    public var tools: List<AgentTool<*>>
    public var messages: List<AgentMessage>
    public val isStreaming: Boolean
    public val streamingMessage: AgentMessage?
    public val pendingToolCalls: Set<String>
    public val errorMessage: String?
}

public data class AgentToolResult<TDetails>(
    val content: List<ToolResultContentPart>,
    val details: TDetails,
    val terminal: Boolean = false,
)

public typealias AgentToolUpdateCallback<TDetails> = suspend (AgentToolResult<TDetails>) -> Unit

public interface AgentTool<TArguments> : Tool<TArguments> {
    public val label: String

    public fun prepareArguments(args: JsonObject): JsonObject = args

    public suspend fun execute(
        toolCallId: String,
        params: TArguments,
        signal: AbortSignal? = null,
        onUpdate: AgentToolUpdateCallback<JsonElement>? = null,
    ): AgentToolResult<JsonElement>
}

public data class AgentContext(
    val systemPrompt: String,
    val messages: MutableList<AgentMessage>,
    val tools: List<AgentTool<*>> = emptyList(),
)

public sealed interface AgentEvent {
    public data object AgentStart : AgentEvent

    public data class AgentEnd(
        val messages: List<AgentMessage>,
    ) : AgentEvent

    public data object TurnStart : AgentEvent

    public data class TurnEnd(
        val message: AgentMessage,
        val toolResults: List<ToolResultMessage>,
    ) : AgentEvent

    public data class MessageStart(
        val message: AgentMessage,
    ) : AgentEvent

    public data class MessageUpdate(
        val message: AgentMessage,
        val assistantMessageEvent: AssistantMessageEvent,
    ) : AgentEvent

    public data class MessageEnd(
        val message: AgentMessage,
    ) : AgentEvent

    public data class ToolExecutionStart(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject,
    ) : AgentEvent

    public data class ToolExecutionUpdate(
        val toolCallId: String,
        val toolName: String,
        val args: JsonObject,
        val partialResult: AgentToolResult<*>,
    ) : AgentEvent

    public data class ToolExecutionEnd(
        val toolCallId: String,
        val toolName: String,
        val result: AgentToolResult<*>,
        val isError: Boolean,
    ) : AgentEvent
}

public data class AgentOptions(
    val initialState: InitialAgentState? = null,
    val convertToLlm: (suspend (List<AgentMessage>) -> List<Message>)? = null,
    val transformContext: (suspend (List<AgentMessage>, AbortSignal?) -> List<AgentMessage>)? = null,
    val streamFn: StreamFn? = null,
    val getApiKey: (suspend (String) -> String?)? = null,
    val onPayload: (suspend (payload: Any, model: Model<*>) -> Any?)? = null,
    val onResponse: (suspend (response: ProviderResponse, model: Model<*>) -> Unit)? = null,
    val beforeToolCall: (suspend (BeforeToolCallContext, AbortSignal?) -> BeforeToolCallResult?)? = null,
    val afterToolCall: (suspend (AfterToolCallContext, AbortSignal?) -> AfterToolCallResult?)? = null,
    val steeringMode: QueueMode = QueueMode.ONE_AT_A_TIME,
    val followUpMode: QueueMode = QueueMode.ONE_AT_A_TIME,
    val cacheRetention: CacheRetention? = null,
    val sessionId: String? = null,
    val thinkingBudgets: ThinkingBudgets? = null,
    val transport: Transport = Transport.SSE,
    val maxRetryDelayMs: Long? = null,
    val toolExecution: ToolExecutionMode = ToolExecutionMode.PARALLEL,
    val customMessageToLlm: CustomMessageToLlm? = null,
)

public data class InitialAgentState(
    val systemPrompt: String? = null,
    val model: Model<*>? = null,
    val thinkingLevel: AgentThinkingLevel? = null,
    val tools: List<AgentTool<*>>? = null,
    val messages: List<AgentMessage>? = null,
)

public enum class QueueMode {
    ALL,
    ONE_AT_A_TIME,
}

internal fun Message.copyForEvent(): AgentMessage =
    when (this) {
        is AssistantMessage -> copy()
        is ToolResultMessage -> copy()
        is pi.ai.core.UserMessage -> copy()
        else -> this
    }

internal fun stringPromptMessage(
    text: String,
    images: List<ImageContent>,
): pi.ai.core.UserMessage {
    val parts: MutableList<pi.ai.core.UserContentPart> = mutableListOf(TextContent(text))
    parts.addAll(images)
    return pi.ai.core.UserMessage(
        content =
            pi.ai.core.UserMessageContent
                .Structured(parts),
        timestamp = System.currentTimeMillis(),
    )
}

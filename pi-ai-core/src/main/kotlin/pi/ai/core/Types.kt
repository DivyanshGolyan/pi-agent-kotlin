package pi.ai.core

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

public typealias Api = String
public typealias Provider = String

public const val ANTHROPIC_MESSAGES_API: String = "anthropic-messages"
public const val ANTHROPIC_PROVIDER: String = "anthropic"
public const val GOOGLE_GENERATIVE_AI_API: String = "google-generative-ai"
public const val GOOGLE_PROVIDER: String = "google"

public enum class ThinkingLevel {
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
}

public enum class CacheRetention {
    NONE,
    SHORT,
    LONG,
}

public enum class Transport {
    SSE,
    WEBSOCKET,
    AUTO,
}

public data class ThinkingBudgets(
    val minimal: Int? = null,
    val low: Int? = null,
    val medium: Int? = null,
    val high: Int? = null,
)

public data class ProviderResponse(
    val status: Int,
    val headers: Map<String, String>,
)

public data class ModelCost(
    val input: Double,
    val output: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
)

public data class UsageCost(
    var input: Double = 0.0,
    var output: Double = 0.0,
    var cacheRead: Double = 0.0,
    var cacheWrite: Double = 0.0,
    var total: Double = 0.0,
)

public data class Usage(
    var input: Int = 0,
    var output: Int = 0,
    var cacheRead: Int = 0,
    var cacheWrite: Int = 0,
    var totalTokens: Int = 0,
    var cost: UsageCost = UsageCost(),
)

public data class Model<TApi : Api>(
    val id: String,
    val name: String,
    val api: TApi,
    val provider: Provider,
    val baseUrl: String,
    val reasoning: Boolean,
    val input: Set<InputModality>,
    val cost: ModelCost,
    val contextWindow: Int,
    val maxTokens: Int,
    val headers: Map<String, String> = emptyMap(),
)

public enum class InputModality {
    TEXT,
    IMAGE,
}

public sealed interface UserContentPart

public data class TextContent(
    val text: String,
    val textSignature: String? = null,
) : UserContentPart,
    AssistantContentBlock,
    ToolResultContentPart

public data class ThinkingContent(
    val thinking: String,
    val thinkingSignature: String? = null,
    val redacted: Boolean = false,
) : AssistantContentBlock

public data class ImageContent(
    val data: String,
    val mimeType: String,
) : UserContentPart,
    ToolResultContentPart

public data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    val thoughtSignature: String? = null,
) : AssistantContentBlock

public interface AssistantContentBlock

public sealed interface ToolResultContentPart

public sealed interface UserMessageContent {
    public data class Text(
        val value: String,
    ) : UserMessageContent

    public data class Structured(
        val parts: List<UserContentPart>,
    ) : UserMessageContent
}

public interface Message {
    public val role: String
    public val timestamp: Long
}

public data class UserMessage(
    val content: UserMessageContent,
    override val timestamp: Long,
) : Message {
    override val role: String = "user"
}

public enum class StopReason {
    STOP,
    LENGTH,
    TOOL_USE,
    ERROR,
    ABORTED,
}

public data class AssistantMessage(
    val content: MutableList<AssistantContentBlock>,
    val api: Api,
    val provider: Provider,
    val model: String,
    val usage: Usage,
    var stopReason: StopReason,
    var errorMessage: String? = null,
    override val timestamp: Long,
    var responseId: String? = null,
) : Message {
    override val role: String = "assistant"
}

public data class ToolResultMessage(
    val toolCallId: String,
    val toolName: String,
    val content: List<ToolResultContentPart>,
    val details: JsonElement? = null,
    val isError: Boolean,
    override val timestamp: Long,
) : Message {
    override val role: String = "toolResult"
}

public interface Tool<TArguments> {
    public val name: String
    public val description: String
    public val parameters: JsonObject

    public fun validateArguments(arguments: JsonObject): TArguments
}

public data class Context(
    val systemPrompt: String? = null,
    val messages: List<Message>,
    val tools: List<Tool<*>> = emptyList(),
)

public data class StreamOptions(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val signal: AbortSignal? = null,
    val apiKey: String? = null,
    val transport: Transport? = null,
    val cacheRetention: CacheRetention? = null,
    val sessionId: String? = null,
    val onPayload: (suspend (payload: Any, model: Model<*>) -> Any?)? = null,
    val onResponse: (suspend (response: ProviderResponse, model: Model<*>) -> Unit)? = null,
    val headers: Map<String, String> = emptyMap(),
    val maxRetryDelayMs: Long? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
)

public data class SimpleStreamOptions(
    val reasoning: ThinkingLevel? = null,
    val thinkingBudgets: ThinkingBudgets? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val signal: AbortSignal? = null,
    val apiKey: String? = null,
    val transport: Transport? = null,
    val cacheRetention: CacheRetention? = null,
    val sessionId: String? = null,
    val onPayload: (suspend (payload: Any, model: Model<*>) -> Any?)? = null,
    val onResponse: (suspend (response: ProviderResponse, model: Model<*>) -> Unit)? = null,
    val headers: Map<String, String> = emptyMap(),
    val maxRetryDelayMs: Long? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
)

public sealed interface AssistantMessageEvent {
    public val partial: AssistantMessage

    public data class Start(
        override val partial: AssistantMessage,
    ) : AssistantMessageEvent

    public data class TextStart(
        val contentIndex: Int,
        override val partial: AssistantMessage,
    ) : AssistantMessageEvent

    public data class TextDelta(
        val contentIndex: Int,
        val delta: String,
        override val partial: AssistantMessage,
    ) : AssistantMessageEvent

    public data class TextEnd(
        val contentIndex: Int,
        val content: String,
        override val partial: AssistantMessage,
    ) : AssistantMessageEvent

    public data class ThinkingStart(
        val contentIndex: Int,
        override val partial: AssistantMessage,
    ) : AssistantMessageEvent

    public data class ThinkingDelta(
        val contentIndex: Int,
        val delta: String,
        override val partial: AssistantMessage,
    ) : AssistantMessageEvent

    public data class ThinkingEnd(
        val contentIndex: Int,
        val content: String,
        override val partial: AssistantMessage,
    ) : AssistantMessageEvent

    public data class ToolCallStart(
        val contentIndex: Int,
        override val partial: AssistantMessage,
    ) : AssistantMessageEvent

    public data class ToolCallDelta(
        val contentIndex: Int,
        val delta: String,
        override val partial: AssistantMessage,
    ) : AssistantMessageEvent

    public data class ToolCallEnd(
        val contentIndex: Int,
        val toolCall: ToolCall,
        override val partial: AssistantMessage,
    ) : AssistantMessageEvent

    public data class Done(
        val reason: StopReason,
        val message: AssistantMessage,
    ) : AssistantMessageEvent {
        override val partial: AssistantMessage = message
    }

    public data class Error(
        val reason: StopReason,
        val error: AssistantMessage,
    ) : AssistantMessageEvent {
        override val partial: AssistantMessage = error
    }
}

public typealias StreamFunction<TOptions> = (Model<*>, Context, TOptions?) -> AssistantMessageEventStream

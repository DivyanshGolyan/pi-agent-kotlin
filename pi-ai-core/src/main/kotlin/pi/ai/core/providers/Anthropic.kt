package pi.ai.core.providers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pi.ai.core.ANTHROPIC_MESSAGES_API
import pi.ai.core.AbortSignal
import pi.ai.core.ApiProvider
import pi.ai.core.AssistantContentBlock
import pi.ai.core.AssistantMessage
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.AssistantMessageEventStream
import pi.ai.core.CacheRetention
import pi.ai.core.Context
import pi.ai.core.ImageContent
import pi.ai.core.InputModality
import pi.ai.core.Message
import pi.ai.core.Model
import pi.ai.core.ProviderResponse
import pi.ai.core.SimpleStreamOptions
import pi.ai.core.StopReason
import pi.ai.core.StreamOptions
import pi.ai.core.TextContent
import pi.ai.core.ThinkingContent
import pi.ai.core.ThinkingLevel
import pi.ai.core.Tool
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultContentPart
import pi.ai.core.ToolResultMessage
import pi.ai.core.Usage
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import pi.ai.core.calculateCost
import pi.ai.core.getEnvApiKey
import pi.ai.core.parseStreamingJson
import java.io.IOException
import kotlin.math.min

public typealias AnthropicEffort = String
public typealias AnthropicThinkingDisplay = String

public data class AnthropicOptions(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val signal: AbortSignal? = null,
    val apiKey: String? = null,
    val cacheRetention: CacheRetention? = null,
    val sessionId: String? = null,
    val onPayload: (suspend (payload: Any, model: Model<*>) -> Any?)? = null,
    val onResponse: (suspend (response: ProviderResponse, model: Model<*>) -> Unit)? = null,
    val headers: Map<String, String> = emptyMap(),
    val maxRetryDelayMs: Long? = null,
    val metadata: Map<String, JsonElement> = emptyMap(),
    val thinkingEnabled: Boolean? = null,
    val thinkingBudgetTokens: Int? = null,
    val effort: AnthropicEffort? = null,
    val thinkingDisplay: AnthropicThinkingDisplay? = null,
    val toolChoice: ToolChoice? = null,
)

public sealed interface ToolChoice {
    public data class Named(
        val name: String,
    ) : ToolChoice

    public data object Auto : ToolChoice

    public data object Any : ToolChoice

    public data object None : ToolChoice
}

private val json: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

private val client: OkHttpClient = OkHttpClient.Builder().build()
private val jsonMediaType = "application/json".toMediaType()
private const val ANTHROPIC_BETA_HEADER: String = "fine-grained-tool-streaming-2025-05-14"

public object AnthropicApiProvider : ApiProvider {
    override val api: String = ANTHROPIC_MESSAGES_API

    override fun stream(
        model: Model<*>,
        context: Context,
        options: StreamOptions?,
    ): AssistantMessageEventStream {
        require(model.api == ANTHROPIC_MESSAGES_API) { "Anthropic provider only supports $ANTHROPIC_MESSAGES_API" }
        val anthropicOptions =
            AnthropicOptions(
                temperature = options?.temperature,
                maxTokens = options?.maxTokens,
                signal = options?.signal,
                apiKey = options?.apiKey,
                cacheRetention = options?.cacheRetention,
                sessionId = options?.sessionId,
                onPayload = options?.onPayload,
                onResponse = options?.onResponse,
                headers = options?.headers.orEmpty(),
                maxRetryDelayMs = options?.maxRetryDelayMs,
                metadata = options?.metadata.orEmpty(),
            )
        return streamAnthropic(model = castAnthropicModel(model), context = context, options = anthropicOptions)
    }

    override fun streamSimple(
        model: Model<*>,
        context: Context,
        options: SimpleStreamOptions?,
    ): AssistantMessageEventStream {
        require(model.api == ANTHROPIC_MESSAGES_API) { "Anthropic provider only supports $ANTHROPIC_MESSAGES_API" }
        return streamSimpleAnthropic(castAnthropicModel(model), context, options)
    }
}

public fun streamSimpleAnthropic(
    model: Model<String>,
    context: Context,
    options: SimpleStreamOptions? = null,
): AssistantMessageEventStream {
    val apiKey: String =
        options?.apiKey ?: getEnvApiKey(model.provider)
            ?: throw IllegalArgumentException("No API key for provider: ${model.provider}")

    val base: StreamOptions = buildBaseOptions(model, options, apiKey)
    if (options?.reasoning == null) {
        return streamAnthropic(
            model = model,
            context = context,
            options =
                AnthropicOptions(
                    temperature = base.temperature,
                    maxTokens = base.maxTokens,
                    signal = base.signal,
                    apiKey = base.apiKey,
                    cacheRetention = base.cacheRetention,
                    sessionId = base.sessionId,
                    onPayload = base.onPayload,
                    onResponse = base.onResponse,
                    headers = base.headers,
                    maxRetryDelayMs = base.maxRetryDelayMs,
                    metadata = base.metadata,
                    thinkingEnabled = false,
                ),
        )
    }

    if (supportsAdaptiveThinking(model.id)) {
        return streamAnthropic(
            model = model,
            context = context,
            options =
                AnthropicOptions(
                    temperature = base.temperature,
                    maxTokens = base.maxTokens,
                    signal = base.signal,
                    apiKey = base.apiKey,
                    cacheRetention = base.cacheRetention,
                    sessionId = base.sessionId,
                    onPayload = base.onPayload,
                    onResponse = base.onResponse,
                    headers = base.headers,
                    maxRetryDelayMs = base.maxRetryDelayMs,
                    metadata = base.metadata,
                    thinkingEnabled = true,
                    effort = mapThinkingLevelToEffort(options.reasoning, model.id),
                ),
        )
    }

    val adjusted =
        adjustMaxTokensForThinking(
            baseMaxTokens = base.maxTokens ?: min(model.maxTokens, 32_000),
            modelMaxTokens = model.maxTokens,
            reasoningLevel = options.reasoning,
            customBudgets = options.thinkingBudgets,
        )

    return streamAnthropic(
        model = model,
        context = context,
        options =
            AnthropicOptions(
                temperature = base.temperature,
                maxTokens = adjusted.maxTokens,
                signal = base.signal,
                apiKey = base.apiKey,
                cacheRetention = base.cacheRetention,
                sessionId = base.sessionId,
                onPayload = base.onPayload,
                onResponse = base.onResponse,
                headers = base.headers,
                maxRetryDelayMs = base.maxRetryDelayMs,
                metadata = base.metadata,
                thinkingEnabled = true,
                thinkingBudgetTokens = adjusted.thinkingBudget,
            ),
    )
}

public fun streamAnthropic(
    model: Model<String>,
    context: Context,
    options: AnthropicOptions = AnthropicOptions(),
): AssistantMessageEventStream {
    val stream = AssistantMessageEventStream()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    scope.launch {
        val output =
            AssistantMessage(
                content = mutableListOf(),
                api = model.api,
                provider = model.provider,
                model = model.id,
                usage = Usage(),
                stopReason = StopReason.STOP,
                timestamp = System.currentTimeMillis(),
            )

        val signal = options.signal
        if (signal?.aborted == true) {
            finishWithError(stream, output, signal, IllegalStateException("Request was aborted"))
            scope.cancel()
            return@launch
        }

        var activeCall: okhttp3.Call? = null
        val removeAbortListener =
            signal?.addListener {
                activeCall?.cancel()
            }

        try {
            var payload: JsonObject = buildParams(model, context, options)
            val replacement = options.onPayload?.invoke(payload, model)
            if (replacement is JsonObject) {
                payload = replacement
            }

            val request =
                Request
                    .Builder()
                    .url("${model.baseUrl.trimEnd('/')}/v1/messages")
                    .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(jsonMediaType))
                    .header("content-type", "application/json")
                    .header("accept", "text/event-stream")
                    .header("anthropic-version", "2023-06-01")
                    .header("anthropic-dangerous-direct-browser-access", "true")
                    .header("anthropic-beta", ANTHROPIC_BETA_HEADER)
                    .header("x-api-key", options.apiKey ?: "")
                    .apply {
                        model.headers.forEach { (name, value) -> header(name, value) }
                        options.headers.forEach { (name, value) -> header(name, value) }
                    }.build()

            activeCall = client.newCall(request)
            val response = activeCall.execute()
            options.onResponse?.invoke(
                ProviderResponse(
                    status = response.code,
                    headers = response.headers.toMultimap().mapValues { entry -> entry.value.joinToString(",") },
                ),
                model,
            )

            if (!response.isSuccessful) {
                val body = response.body.string()
                throw IOException("Anthropic request failed with ${response.code}: $body")
            }

            stream.push(AssistantMessageEvent.Start(output))
            val reader = response.body.charStream().buffered()
            var eventType: String? = null
            val dataLines = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    if (eventType != null) {
                        handleSseEvent(eventType, dataLines.joinToString("\n"), output, stream, model)
                    }
                    eventType = null
                    dataLines.clear()
                    continue
                }
                if (line.startsWith("event:")) {
                    eventType = line.removePrefix("event:").trim()
                } else if (line.startsWith("data:")) {
                    dataLines += line.removePrefix("data:").trimStart()
                }
            }

            if (signal?.aborted == true) {
                error("Request was aborted")
            }

            if (output.stopReason == StopReason.ERROR || output.stopReason == StopReason.ABORTED) {
                error(output.errorMessage ?: "Anthropic stream failed")
            }

            stream.push(AssistantMessageEvent.Done(output.stopReason, output))
        } catch (error: Throwable) {
            finishWithError(stream, output, signal, error)
        } finally {
            removeAbortListener?.invoke()
            scope.cancel()
        }
    }

    return stream
}

private fun finishWithError(
    stream: AssistantMessageEventStream,
    output: AssistantMessage,
    signal: AbortSignal?,
    error: Throwable,
) {
    output.content.replaceAll { block ->
        when (block) {
            is StreamingToolCall -> block.toToolCall()
            else -> block
        }
    }
    output.stopReason = if (signal?.aborted == true) StopReason.ABORTED else StopReason.ERROR
    output.errorMessage = error.message ?: error.toString()
    stream.push(AssistantMessageEvent.Error(output.stopReason, output))
}

@Suppress("UNCHECKED_CAST")
private fun castAnthropicModel(model: Model<*>): Model<String> = model as Model<String>

private fun supportsAdaptiveThinking(modelId: String): Boolean =
    modelId.contains("opus-4-6") ||
        modelId.contains("opus-4.6") ||
        modelId.contains("opus-4-7") ||
        modelId.contains("opus-4.7") ||
        modelId.contains("sonnet-4-6") ||
        modelId.contains("sonnet-4.6")

private fun mapThinkingLevelToEffort(
    level: ThinkingLevel,
    modelId: String,
): AnthropicEffort =
    when (level) {
        ThinkingLevel.MINIMAL, ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH ->
            when {
                modelId.contains("opus-4-6") || modelId.contains("opus-4.6") -> "max"
                modelId.contains("opus-4-7") || modelId.contains("opus-4.7") -> "xhigh"
                else -> "high"
            }
    }

internal fun buildParams(
    model: Model<String>,
    context: Context,
    options: AnthropicOptions,
): JsonObject {
    val cacheControl = getCacheControl(model.baseUrl, options.cacheRetention)
    return buildJsonObject {
        put("model", JsonPrimitive(model.id))
        put("messages", convertMessages(context.messages, model, cacheControl))
        put("max_tokens", JsonPrimitive(options.maxTokens ?: (model.maxTokens / 3)))
        put("stream", JsonPrimitive(true))

        if (context.systemPrompt != null) {
            put(
                "system",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("text"))
                            put("text", JsonPrimitive(context.systemPrompt))
                            cacheControl?.let { put("cache_control", it) }
                        },
                    )
                },
            )
        }

        if (options.temperature != null && options.thinkingEnabled != true) {
            put("temperature", JsonPrimitive(options.temperature))
        }

        if (context.tools.isNotEmpty()) {
            put("tools", convertTools(context.tools, cacheControl))
        }

        if (model.reasoning) {
            when (options.thinkingEnabled) {
                true -> {
                    val display = options.thinkingDisplay ?: "summarized"
                    if (supportsAdaptiveThinking(model.id)) {
                        put(
                            "thinking",
                            buildJsonObject {
                                put("type", JsonPrimitive("adaptive"))
                                put("display", JsonPrimitive(display))
                            },
                        )
                        options.effort?.let { effort ->
                            put(
                                "output_config",
                                buildJsonObject {
                                    put("effort", JsonPrimitive(effort))
                                },
                            )
                        }
                    } else {
                        put(
                            "thinking",
                            buildJsonObject {
                                put("type", JsonPrimitive("enabled"))
                                put("budget_tokens", JsonPrimitive(options.thinkingBudgetTokens ?: 1024))
                                put("display", JsonPrimitive(display))
                            },
                        )
                    }
                }
                false ->
                    put(
                        "thinking",
                        buildJsonObject {
                            put("type", JsonPrimitive("disabled"))
                        },
                    )
                null -> {}
            }
        }

        options.metadata["user_id"]?.let { userId ->
            put(
                "metadata",
                buildJsonObject {
                    put("user_id", userId)
                },
            )
        }

        when (val toolChoice = options.toolChoice) {
            ToolChoice.Auto -> put("tool_choice", buildJsonObject { put("type", JsonPrimitive("auto")) })
            ToolChoice.Any -> put("tool_choice", buildJsonObject { put("type", JsonPrimitive("any")) })
            ToolChoice.None -> put("tool_choice", buildJsonObject { put("type", JsonPrimitive("none")) })
            is ToolChoice.Named ->
                put(
                    "tool_choice",
                    buildJsonObject {
                        put("type", JsonPrimitive("tool"))
                        put("name", JsonPrimitive(toolChoice.name))
                    },
                )
            null -> {}
        }
    }
}

private fun getCacheControl(
    baseUrl: String,
    cacheRetention: CacheRetention?,
): JsonObject? {
    val retention = cacheRetention ?: CacheRetention.SHORT
    if (retention == CacheRetention.NONE) {
        return null
    }
    return buildJsonObject {
        put("type", JsonPrimitive("ephemeral"))
        if (retention == CacheRetention.LONG && baseUrl.contains("api.anthropic.com")) {
            put("ttl", JsonPrimitive("1h"))
        }
    }
}

private fun convertMessages(
    messages: List<Message>,
    model: Model<String>,
    cacheControl: JsonObject?,
): JsonArray =
    buildJsonArray {
        var index = 0
        while (index < messages.size) {
            when (val message = messages[index]) {
                is UserMessage -> {
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", convertUserContent(message.content, model))
                        },
                    )
                }
                is AssistantMessage -> {
                    val blocks =
                        buildJsonArray {
                            message.content.forEach { block ->
                                when (block) {
                                    is TextContent ->
                                        if (block.text.isNotBlank()) {
                                            add(
                                                buildJsonObject {
                                                    put("type", JsonPrimitive("text"))
                                                    put("text", JsonPrimitive(block.text))
                                                },
                                            )
                                        }
                                    is ThinkingContent ->
                                        when {
                                            block.redacted && !block.thinkingSignature.isNullOrBlank() ->
                                                add(
                                                    buildJsonObject {
                                                        put("type", JsonPrimitive("redacted_thinking"))
                                                        put("data", JsonPrimitive(block.thinkingSignature))
                                                    },
                                                )
                                            block.thinking.isBlank() -> {}
                                            block.thinkingSignature.isNullOrBlank() ->
                                                add(
                                                    buildJsonObject {
                                                        put("type", JsonPrimitive("text"))
                                                        put("text", JsonPrimitive(block.thinking))
                                                    },
                                                )
                                            else ->
                                                add(
                                                    buildJsonObject {
                                                        put("type", JsonPrimitive("thinking"))
                                                        put("thinking", JsonPrimitive(block.thinking))
                                                        put("signature", JsonPrimitive(block.thinkingSignature))
                                                    },
                                                )
                                        }
                                    is ToolCall ->
                                        add(
                                            buildJsonObject {
                                                put("type", JsonPrimitive("tool_use"))
                                                put("id", JsonPrimitive(block.id))
                                                put("name", JsonPrimitive(block.name))
                                                put("input", block.arguments)
                                            },
                                        )
                                    is StreamingToolCall ->
                                        add(
                                            buildJsonObject {
                                                put("type", JsonPrimitive("tool_use"))
                                                put("id", JsonPrimitive(block.id))
                                                put("name", JsonPrimitive(block.name))
                                                put("input", block.arguments)
                                            },
                                        )
                                }
                            }
                        }
                    if (blocks.isNotEmpty()) {
                        add(
                            buildJsonObject {
                                put("role", JsonPrimitive("assistant"))
                                put("content", blocks)
                            },
                        )
                    }
                }
                is ToolResultMessage -> {
                    val toolResults =
                        buildJsonArray {
                            add(convertToolResult(message))
                            var next = index + 1
                            while (next < messages.size && messages[next] is ToolResultMessage) {
                                add(convertToolResult(messages[next] as ToolResultMessage))
                                next += 1
                            }
                            index = next - 1
                        }
                    add(
                        buildJsonObject {
                            put("role", JsonPrimitive("user"))
                            put("content", toolResults)
                        },
                    )
                }
            }
            index += 1
        }
    }.let { params ->
        if (cacheControl != null && params.isNotEmpty()) {
            val last = params.last().jsonObject
            if (last["role"]?.jsonPrimitive?.content == "user") {
                val patched = params.toMutableList()
                val contentArray = last["content"] as? JsonArray
                if (contentArray != null && contentArray.isNotEmpty()) {
                    val updatedBlocks = contentArray.toMutableList()
                    val lastBlock = updatedBlocks.last().jsonObject.toMutableMap()
                    lastBlock["cache_control"] = cacheControl
                    updatedBlocks[updatedBlocks.lastIndex] = JsonObject(lastBlock)
                    patched[patched.lastIndex] =
                        JsonObject(
                            last.toMutableMap().apply {
                                put("content", JsonArray(updatedBlocks))
                            },
                        )
                }
                JsonArray(patched)
            } else {
                params
            }
        } else {
            params
        }
    }

private fun convertUserContent(
    content: UserMessageContent,
    model: Model<String>,
): JsonElement =
    when (content) {
        is UserMessageContent.Text -> JsonPrimitive(content.value)
        is UserMessageContent.Structured ->
            buildJsonArray {
                content.parts.forEach { part ->
                    when (part) {
                        is TextContent ->
                            if (part.text.isNotBlank()) {
                                add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("text"))
                                        put("text", JsonPrimitive(part.text))
                                    },
                                )
                            }
                        is ImageContent ->
                            if (model.input.contains(InputModality.IMAGE)) {
                                add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("image"))
                                        put(
                                            "source",
                                            buildJsonObject {
                                                put("type", JsonPrimitive("base64"))
                                                put("media_type", JsonPrimitive(part.mimeType))
                                                put("data", JsonPrimitive(part.data))
                                            },
                                        )
                                    },
                                )
                            }
                    }
                }
            }
    }

private fun convertToolResult(message: ToolResultMessage): JsonObject =
    buildJsonObject {
        put("type", JsonPrimitive("tool_result"))
        put("tool_use_id", JsonPrimitive(message.toolCallId))
        put("content", convertToolResultContent(message.content))
        put("is_error", JsonPrimitive(message.isError))
    }

private fun convertToolResultContent(content: List<ToolResultContentPart>): JsonElement {
    val hasImages = content.any { it is ImageContent }
    if (!hasImages) {
        return JsonPrimitive(
            content
                .filterIsInstance<TextContent>()
                .joinToString("\n") { it.text },
        )
    }

    val blocks =
        buildJsonArray {
            content.forEach { part ->
                when (part) {
                    is TextContent ->
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("text"))
                                put("text", JsonPrimitive(part.text))
                            },
                        )
                    is ImageContent ->
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive("image"))
                                put(
                                    "source",
                                    buildJsonObject {
                                        put("type", JsonPrimitive("base64"))
                                        put("media_type", JsonPrimitive(part.mimeType))
                                        put("data", JsonPrimitive(part.data))
                                    },
                                )
                            },
                        )
                }
            }
        }
    return if (blocks.none { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }) {
        JsonArray(
            listOf(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("text", JsonPrimitive("(see attached image)"))
                },
            ) + blocks,
        )
    } else {
        blocks
    }
}

private fun convertTools(
    tools: List<Tool<*>>,
    cacheControl: JsonObject?,
): JsonArray =
    buildJsonArray {
        tools.forEachIndexed { index, tool ->
            add(
                buildJsonObject {
                    put("name", JsonPrimitive(tool.name))
                    put("description", JsonPrimitive(tool.description))
                    put(
                        "input_schema",
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put("properties", tool.parameters["properties"] ?: JsonObject(emptyMap()))
                            put("required", tool.parameters["required"] ?: JsonArray(emptyList()))
                        },
                    )
                    if (cacheControl != null && index == tools.lastIndex) {
                        put("cache_control", cacheControl)
                    }
                },
            )
        }
    }

internal fun handleSseEvent(
    eventType: String,
    rawData: String,
    output: AssistantMessage,
    stream: AssistantMessageEventStream,
    model: Model<String>,
) {
    if (eventType == "ping" || rawData.isBlank()) {
        return
    }
    val data = json.parseToJsonElement(rawData).jsonObject
    when (eventType) {
        "message_start" -> {
            val message = data["message"]?.jsonObject ?: return
            output.responseId = message["id"]?.jsonPrimitive?.content
            updateUsageFromUsageObject(output, message["usage"]?.jsonObject, model)
        }
        "content_block_start" -> {
            val index = data["index"]?.jsonPrimitive?.intOrNull ?: return
            val block = data["content_block"]?.jsonObject ?: return
            when (block["type"]?.jsonPrimitive?.content) {
                "text" -> {
                    output.content += TextContent(text = "")
                    stream.push(AssistantMessageEvent.TextStart(index, output))
                }
                "thinking" -> {
                    output.content += ThinkingContent(thinking = "", thinkingSignature = "")
                    stream.push(AssistantMessageEvent.ThinkingStart(index, output))
                }
                "redacted_thinking" -> {
                    output.content +=
                        ThinkingContent(
                            thinking = "[Reasoning redacted]",
                            thinkingSignature = block["data"]?.jsonPrimitive?.content,
                            redacted = true,
                        )
                    stream.push(AssistantMessageEvent.ThinkingStart(index, output))
                }
                "tool_use" -> {
                    output.content +=
                        StreamingToolCall(
                            id = block["id"]?.jsonPrimitive?.content.orEmpty(),
                            name = block["name"]?.jsonPrimitive?.content.orEmpty(),
                            arguments = block["input"]?.jsonObject ?: JsonObject(emptyMap()),
                            partialJson = "",
                        )
                    stream.push(AssistantMessageEvent.ToolCallStart(index, output))
                }
            }
        }
        "content_block_delta" -> {
            val index = data["index"]?.jsonPrimitive?.intOrNull ?: return
            val delta = data["delta"]?.jsonObject ?: return
            when (delta["type"]?.jsonPrimitive?.content) {
                "text_delta" -> {
                    val block = output.content.getOrNull(index) as? TextContent ?: return
                    val next = block.copy(text = block.text + delta["text"]?.jsonPrimitive?.content.orEmpty())
                    output.content[index] = next
                    stream.push(
                        AssistantMessageEvent.TextDelta(
                            contentIndex = index,
                            delta = delta["text"]?.jsonPrimitive?.content.orEmpty(),
                            partial = output,
                        ),
                    )
                }
                "thinking_delta" -> {
                    val block = output.content.getOrNull(index) as? ThinkingContent ?: return
                    val next = block.copy(thinking = block.thinking + delta["thinking"]?.jsonPrimitive?.content.orEmpty())
                    output.content[index] = next
                    stream.push(
                        AssistantMessageEvent.ThinkingDelta(
                            contentIndex = index,
                            delta = delta["thinking"]?.jsonPrimitive?.content.orEmpty(),
                            partial = output,
                        ),
                    )
                }
                "input_json_delta" -> {
                    val block = output.content.getOrNull(index) as? StreamingToolCall ?: return
                    val partialJson = block.partialJson + delta["partial_json"]?.jsonPrimitive?.content.orEmpty()
                    val next = block.copy(partialJson = partialJson, arguments = parseStreamingJson(partialJson))
                    output.content[index] = next
                    stream.push(
                        AssistantMessageEvent.ToolCallDelta(
                            contentIndex = index,
                            delta = delta["partial_json"]?.jsonPrimitive?.content.orEmpty(),
                            partial = output,
                        ),
                    )
                }
                "signature_delta" -> {
                    val block = output.content.getOrNull(index) as? ThinkingContent ?: return
                    output.content[index] =
                        block.copy(
                            thinkingSignature =
                                (block.thinkingSignature ?: "") +
                                    delta["signature"]?.jsonPrimitive?.content.orEmpty(),
                        )
                }
            }
        }
        "content_block_stop" -> {
            val index = data["index"]?.jsonPrimitive?.intOrNull ?: return
            when (val block = output.content.getOrNull(index)) {
                is TextContent -> stream.push(AssistantMessageEvent.TextEnd(index, block.text, output))
                is ThinkingContent -> stream.push(AssistantMessageEvent.ThinkingEnd(index, block.thinking, output))
                is StreamingToolCall -> {
                    val finalized = block.toToolCall()
                    output.content[index] = finalized
                    stream.push(AssistantMessageEvent.ToolCallEnd(index, finalized, output))
                }
                else -> {}
            }
        }
        "message_delta" -> {
            data["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.contentOrNull?.let { stopReason ->
                output.stopReason = mapStopReason(stopReason)
            }
            updateUsageFromUsageObject(output, data["usage"]?.jsonObject, model)
        }
        "message_stop" -> Unit
        "error" -> throw IOException(data.toString())
    }
}

private fun updateUsageFromUsageObject(
    output: AssistantMessage,
    usage: JsonObject?,
    model: Model<*>,
) {
    if (usage == null) {
        return
    }
    usage["input_tokens"]?.jsonPrimitive?.intOrNull?.let { output.usage.input = it }
    usage["output_tokens"]?.jsonPrimitive?.intOrNull?.let { output.usage.output = it }
    usage["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull?.let { output.usage.cacheRead = it }
    usage["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull?.let { output.usage.cacheWrite = it }
    output.usage.totalTokens = output.usage.input + output.usage.output + output.usage.cacheRead + output.usage.cacheWrite
    calculateCost(model, output.usage)
}

private fun mapStopReason(reason: String): StopReason =
    when (reason) {
        "end_turn", "pause_turn", "stop_sequence" -> StopReason.STOP
        "max_tokens" -> StopReason.LENGTH
        "tool_use" -> StopReason.TOOL_USE
        "refusal", "sensitive" -> StopReason.ERROR
        else -> throw IllegalArgumentException("Unhandled stop reason: $reason")
    }

private data class StreamingToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    val partialJson: String,
) : AssistantContentBlock {
    fun toToolCall(): ToolCall =
        ToolCall(
            id = id,
            name = name,
            arguments = if (partialJson.isBlank()) arguments else parseStreamingJson(partialJson),
        )
}

private val JsonPrimitive.intOrNull: Int?
    get() = runCatching { content.toInt() }.getOrNull()

private val JsonPrimitive.contentOrNull: String?
    get() = if (isString || content != "null") content else null

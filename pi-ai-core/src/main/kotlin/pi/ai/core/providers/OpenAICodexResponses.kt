package pi.ai.core.providers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import pi.ai.core.AbortSignal
import pi.ai.core.ApiProvider
import pi.ai.core.AssistantContentBlock
import pi.ai.core.AssistantMessage
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.AssistantMessageEventStream
import pi.ai.core.Context
import pi.ai.core.ImageContent
import pi.ai.core.InputModality
import pi.ai.core.Message
import pi.ai.core.Model
import pi.ai.core.OPENAI_CODEX_RESPONSES_API
import pi.ai.core.ProviderResponse
import pi.ai.core.SimpleStreamOptions
import pi.ai.core.StopReason
import pi.ai.core.StreamOptions
import pi.ai.core.TextContent
import pi.ai.core.ThinkingContent
import pi.ai.core.ThinkingLevel
import pi.ai.core.Tool
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultMessage
import pi.ai.core.Transport
import pi.ai.core.Usage
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import pi.ai.core.calculateCost
import pi.ai.core.getEnvApiKey
import pi.ai.core.parseStreamingJson
import pi.ai.core.supportsXhigh
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow

public data class OpenAICodexResponsesOptions(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val signal: AbortSignal? = null,
    val apiKey: String? = null,
    val sessionId: String? = null,
    val onPayload: (suspend (payload: Any, model: Model<*>) -> Any?)? = null,
    val onResponse: (suspend (response: ProviderResponse, model: Model<*>) -> Unit)? = null,
    val headers: Map<String, String> = emptyMap(),
    val reasoningEffort: ThinkingLevel? = null,
    val reasoningSummary: String? = "auto",
    val textVerbosity: String = "low",
    val transport: Transport? = null,
    val serviceTier: String? = null,
)

private val codexJson: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

private val codexClient: OkHttpClient = OkHttpClient.Builder().build()
private val codexJsonMediaType = "application/json".toMediaType()
private val codexToolCallProviders: Set<String> = setOf("openai", "openai-codex", "opencode")
private const val CODEX_MAX_RETRIES: Int = 3
private const val CODEX_BASE_DELAY_MS: Long = 1_000L
private const val OPENAI_BETA_RESPONSES_WEBSOCKETS: String = "responses_websockets=2026-02-06"
private const val SESSION_WEBSOCKET_CACHE_TTL_MS: Long = 5 * 60 * 1000L
private val codexWebSocketCache: ConcurrentHashMap<String, CachedCodexWebSocket> = ConcurrentHashMap()
private val codexWebSocketTimer: Timer = Timer("openai-codex-websocket-cache", true)

private fun getCodexServiceTier(metadata: Map<String, JsonElement>?): String? =
    metadata
        ?.get("serviceTier")
        ?.jsonPrimitive
        ?.contentOrNull

public object OpenAICodexResponsesApiProvider : ApiProvider {
    override val api: String = OPENAI_CODEX_RESPONSES_API

    override fun stream(
        model: Model<*>,
        context: Context,
        options: StreamOptions?,
    ): AssistantMessageEventStream {
        require(model.api == OPENAI_CODEX_RESPONSES_API) { "OpenAI Codex provider only supports $OPENAI_CODEX_RESPONSES_API" }
        return streamOpenAICodexResponses(
            model = castOpenAICodexModel(model),
            context = context,
            options =
                OpenAICodexResponsesOptions(
                    temperature = options?.temperature,
                    maxTokens = options?.maxTokens,
                    signal = options?.signal,
                    apiKey = options?.apiKey,
                    sessionId = options?.sessionId,
                    onPayload = options?.onPayload,
                    onResponse = options?.onResponse,
                    headers = options?.headers.orEmpty(),
                    transport = options?.transport,
                    serviceTier = getCodexServiceTier(options?.metadata),
                ),
        )
    }

    override fun streamSimple(
        model: Model<*>,
        context: Context,
        options: SimpleStreamOptions?,
    ): AssistantMessageEventStream {
        require(model.api == OPENAI_CODEX_RESPONSES_API) { "OpenAI Codex provider only supports $OPENAI_CODEX_RESPONSES_API" }
        return streamSimpleOpenAICodexResponses(castOpenAICodexModel(model), context, options)
    }
}

public fun streamSimpleOpenAICodexResponses(
    model: Model<String>,
    context: Context,
    options: SimpleStreamOptions? = null,
): AssistantMessageEventStream {
    val apiKey =
        options?.apiKey ?: getEnvApiKey(model.provider)
            ?: throw IllegalArgumentException("No API key for provider: ${model.provider}")
    val base = buildBaseOptions(model, options, apiKey)
    val reasoningEffort = if (supportsXhigh(model)) options?.reasoning else clampReasoning(options?.reasoning)
    return streamOpenAICodexResponses(
        model = model,
        context = context,
        options =
            OpenAICodexResponsesOptions(
                temperature = base.temperature,
                maxTokens = base.maxTokens,
                signal = base.signal,
                apiKey = base.apiKey,
                sessionId = base.sessionId,
                onPayload = base.onPayload,
                onResponse = base.onResponse,
                headers = base.headers,
                reasoningEffort = reasoningEffort,
                transport = base.transport,
                serviceTier = getCodexServiceTier(options?.metadata),
            ),
    )
}

public fun streamOpenAICodexResponses(
    model: Model<String>,
    context: Context,
    options: OpenAICodexResponsesOptions = OpenAICodexResponsesOptions(),
): AssistantMessageEventStream {
    val stream = AssistantMessageEventStream()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    scope.launch {
        val output =
            AssistantMessage(
                content = mutableListOf(),
                api = OPENAI_CODEX_RESPONSES_API,
                provider = model.provider,
                model = model.id,
                usage = Usage(),
                stopReason = StopReason.STOP,
                timestamp = System.currentTimeMillis(),
            )
        val signal = options.signal
        if (signal?.aborted == true) {
            finishCodexWithError(stream, output, signal, IllegalStateException("Request was aborted"))
            scope.cancel()
            return@launch
        }
        var activeCall: okhttp3.Call? = null
        val removeAbortListener = signal?.addListener { activeCall?.cancel() }
        try {
            val apiKey = options.apiKey ?: getEnvApiKey(model.provider).orEmpty()
            require(apiKey.isNotBlank()) { "No API key for provider: ${model.provider}" }
            val accountId =
                extractOpenAICodexAccountId(apiKey)
                    ?: throw IllegalArgumentException("Failed to extract accountId from token")
            var payload = buildCodexRequestBody(model, context, options)
            val replacement = options.onPayload?.invoke(payload, model)
            if (replacement is JsonObject) {
                payload = replacement
            }
            val requestFactory = {
                buildCodexRequest(
                    model = model,
                    payload = payload,
                    apiKey = apiKey,
                    accountId = accountId,
                    options = options,
                )
            }
            val transport = options.transport ?: Transport.SSE
            if (transport != Transport.SSE) {
                var websocketStarted = false
                try {
                    processCodexWebSocketStream(
                        model = model,
                        payload = payload,
                        apiKey = apiKey,
                        accountId = accountId,
                        output = output,
                        stream = stream,
                        options = options,
                        onStart = { websocketStarted = true },
                    )
                    check(signal?.aborted != true) { "Request was aborted" }
                    stream.push(AssistantMessageEvent.Done(output.stopReason, output))
                    return@launch
                } catch (error: Throwable) {
                    if (transport == Transport.WEBSOCKET ||
                        transport == Transport.WEBSOCKET_CACHED ||
                        websocketStarted
                    ) {
                        throw error
                    }
                }
            }
            val response =
                executeCodexRequestWithRetry(
                    requestFactory = requestFactory,
                    model = model,
                    options = options,
                    signal = signal,
                    setActiveCall = { activeCall = it },
                )
            stream.push(AssistantMessageEvent.Start(output))
            processCodexSse(response.body.charStream().buffered()) { event ->
                handleCodexEvent(event, output, stream, model, options)
            }
            check(signal?.aborted != true) { "Request was aborted" }
            stream.push(AssistantMessageEvent.Done(output.stopReason, output))
        } catch (error: Throwable) {
            finishCodexWithError(stream, output, signal, error)
        } finally {
            removeAbortListener?.invoke()
            scope.cancel()
        }
    }
    return stream
}

public fun buildCodexRequestBody(
    model: Model<String>,
    context: Context,
    options: OpenAICodexResponsesOptions = OpenAICodexResponsesOptions(),
): JsonObject =
    buildJsonObject {
        put("model", JsonPrimitive(model.id))
        put("store", JsonPrimitive(false))
        put("stream", JsonPrimitive(true))
        context.systemPrompt?.let { put("instructions", JsonPrimitive(sanitizeSurrogates(it))) }
        put("input", convertCodexMessages(model, context))
        put("text", buildJsonObject { put("verbosity", JsonPrimitive(options.textVerbosity)) })
        put("include", buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) })
        options.sessionId?.let { put("prompt_cache_key", JsonPrimitive(it)) }
        put("tool_choice", JsonPrimitive("auto"))
        put("parallel_tool_calls", JsonPrimitive(true))
        options.temperature?.let { put("temperature", JsonPrimitive(it)) }
        options.serviceTier?.let { put("service_tier", JsonPrimitive(it)) }
        if (context.tools.isNotEmpty()) {
            put("tools", convertCodexTools(context.tools))
        }
        options.reasoningEffort?.let {
            put(
                "reasoning",
                buildJsonObject {
                    put("effort", JsonPrimitive(clampCodexReasoningEffort(model.id, it)))
                    put("summary", JsonPrimitive(options.reasoningSummary ?: "auto"))
                },
            )
        }
    }

private fun convertCodexMessages(
    model: Model<String>,
    context: Context,
): JsonArray =
    buildJsonArray {
        val transformedMessages = transformCodexMessages(model, context.messages)
        transformedMessages.forEachIndexed { index, message ->
            when (message) {
                is UserMessage -> convertCodexUserMessage(message, model)?.let(::add)
                is AssistantMessage -> convertCodexAssistantMessage(index, message, model).forEach(::add)
                is ToolResultMessage -> add(convertCodexToolResult(message, model))
            }
        }
    }

private fun convertCodexUserMessage(
    message: UserMessage,
    model: Model<String>,
): JsonObject? {
    val content =
        when (val content = message.content) {
            is UserMessageContent.Text ->
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("input_text"))
                            put("text", JsonPrimitive(sanitizeSurrogates(content.value)))
                        },
                    )
                }
            is UserMessageContent.Structured ->
                buildJsonArray {
                    content.parts.forEach { part ->
                        when (part) {
                            is TextContent ->
                                add(
                                    buildJsonObject {
                                        put("type", JsonPrimitive("input_text"))
                                        put("text", JsonPrimitive(sanitizeSurrogates(part.text)))
                                    },
                                )
                            is ImageContent ->
                                if (model.input.contains(InputModality.IMAGE)) {
                                    add(
                                        buildJsonObject {
                                            put("type", JsonPrimitive("input_image"))
                                            put("detail", JsonPrimitive("auto"))
                                            put("image_url", JsonPrimitive("data:${part.mimeType};base64,${part.data}"))
                                        },
                                    )
                                }
                        }
                    }
                }
        }
    if (content.isEmpty()) {
        return null
    }
    return buildJsonObject {
        put("role", JsonPrimitive("user"))
        put("content", content)
    }
}

private fun convertCodexAssistantMessage(
    index: Int,
    message: AssistantMessage,
    model: Model<String>,
): List<JsonObject> =
    buildList {
        message.content.forEach { block ->
            when (block) {
                is ThinkingContent ->
                    block.thinkingSignature?.let {
                        runCatching { codexJson.parseToJsonElement(it).jsonObject }.getOrNull()?.let(::add)
                    }
                is TextContent ->
                    add(
                        buildJsonObject {
                            val signature = parseCodexTextSignature(block.textSignature)
                            put("type", JsonPrimitive("message"))
                            put("role", JsonPrimitive("assistant"))
                            put("status", JsonPrimitive("completed"))
                            put("id", JsonPrimitive(signature?.id ?: "msg_$index"))
                            signature?.phase?.let { put("phase", JsonPrimitive(it)) }
                            put(
                                "content",
                                buildJsonArray {
                                    add(
                                        buildJsonObject {
                                            put("type", JsonPrimitive("output_text"))
                                            put("text", JsonPrimitive(sanitizeSurrogates(block.text)))
                                            put("annotations", buildJsonArray {})
                                        },
                                    )
                                },
                            )
                        },
                    )
                is ToolCall -> {
                    val parts = block.id.split("|", limit = 2)
                    val isDifferentModel =
                        message.model != model.id &&
                            message.provider == model.provider &&
                            message.api == model.api
                    add(
                        buildJsonObject {
                            put("type", JsonPrimitive("function_call"))
                            put("call_id", JsonPrimitive(parts[0]))
                            if (parts.size > 1) {
                                if (!(isDifferentModel && parts[1].startsWith("fc_"))) {
                                    put("id", JsonPrimitive(parts[1]))
                                }
                            }
                            put("name", JsonPrimitive(block.name))
                            put("arguments", JsonPrimitive(block.arguments.toString()))
                        },
                    )
                }
            }
        }
    }

private fun convertCodexToolResult(
    message: ToolResultMessage,
    model: Model<String>,
): JsonObject {
    val text =
        message.content
            .filterIsInstance<TextContent>()
            .joinToString("\n") { it.text }
    val images = message.content.filterIsInstance<ImageContent>()
    val callId = message.toolCallId.split("|", limit = 2)[0]
    return buildJsonObject {
        put("type", JsonPrimitive("function_call_output"))
        put("call_id", JsonPrimitive(callId))
        put(
            "output",
            buildCodexToolResultOutput(
                text = text,
                images = images,
                supportsImages = model.input.contains(InputModality.IMAGE),
            ),
        )
    }
}

private fun convertCodexTools(tools: List<Tool<*>>): JsonArray =
    buildJsonArray {
        tools.forEach { tool ->
            add(
                buildJsonObject {
                    put("type", JsonPrimitive("function"))
                    put("name", JsonPrimitive(tool.name))
                    put("description", JsonPrimitive(tool.description))
                    put("parameters", tool.parameters)
                    put("strict", kotlinx.serialization.json.JsonNull)
                },
            )
        }
    }

private fun transformCodexMessages(
    model: Model<String>,
    messages: List<Message>,
): List<Message> =
    transformMessages(messages, model) { id, normalizedModel, source ->
        normalizeCodexToolCallId(id, source, normalizedModel)
    }

private fun buildCodexToolResultOutput(
    text: String,
    images: List<ImageContent>,
    supportsImages: Boolean,
): kotlinx.serialization.json.JsonElement {
    if (images.isEmpty() || !supportsImages) {
        return JsonPrimitive(sanitizeSurrogates(text.ifEmpty { "(see attached image)" }))
    }
    return buildJsonArray {
        if (text.isNotEmpty()) {
            add(
                buildJsonObject {
                    put("type", JsonPrimitive("input_text"))
                    put("text", JsonPrimitive(sanitizeSurrogates(text)))
                },
            )
        }
        images.forEach { image ->
            add(
                buildJsonObject {
                    put("type", JsonPrimitive("input_image"))
                    put("detail", JsonPrimitive("auto"))
                    put("image_url", JsonPrimitive("data:${image.mimeType};base64,${image.data}"))
                },
            )
        }
    }
}

private fun handleCodexEvent(
    rawEvent: JsonObject,
    output: AssistantMessage,
    stream: AssistantMessageEventStream,
    model: Model<String>,
    options: OpenAICodexResponsesOptions,
): Boolean {
    val type = rawEvent["type"]?.jsonPrimitive?.contentOrNull ?: return false
    val event =
        if (type == "response.done" || type == "response.completed" || type == "response.incomplete") {
            buildJsonObject {
                rawEvent.forEach { (key, value) -> put(key, value) }
                put("type", JsonPrimitive("response.completed"))
            }
        } else {
            rawEvent
        }
    when (event["type"]?.jsonPrimitive?.contentOrNull) {
        "error" -> throw IOException("Codex error: ${event["message"]?.jsonPrimitive?.contentOrNull ?: event}")
        "response.failed" -> throw IOException(
            event["response"]
                ?.jsonObject
                ?.get("error")
                ?.jsonObject
                ?.get("message")
                ?.jsonPrimitive
                ?.contentOrNull ?: "Codex response failed",
        )
        "response.created" ->
            output.responseId =
                event["response"]
                    ?.jsonObject
                    ?.get("id")
                    ?.jsonPrimitive
                    ?.contentOrNull
        "response.output_item.added" -> handleCodexOutputItemAdded(event["item"]?.jsonObject ?: return false, output, stream)
        "response.reasoning_summary_text.delta" -> {
            val delta = event["delta"]?.jsonPrimitive?.content.orEmpty()
            val index = output.content.lastIndex
            val block = output.content.getOrNull(index) as? ThinkingContent ?: return false
            output.content[index] = block.copy(thinking = block.thinking + delta)
            stream.push(AssistantMessageEvent.ThinkingDelta(index, delta, output))
        }
        "response.reasoning_summary_part.done" -> {
            val index = output.content.lastIndex
            val block = output.content.getOrNull(index) as? ThinkingContent ?: return false
            output.content[index] = block.copy(thinking = block.thinking + "\n\n")
            stream.push(AssistantMessageEvent.ThinkingDelta(index, "\n\n", output))
        }
        "response.output_text.delta", "response.refusal.delta" -> {
            val delta = event["delta"]?.jsonPrimitive?.content.orEmpty()
            val index = output.content.lastIndex
            val block = output.content.getOrNull(index) as? TextContent ?: return false
            output.content[index] = block.copy(text = block.text + delta)
            stream.push(AssistantMessageEvent.TextDelta(index, delta, output))
        }
        "response.function_call_arguments.delta" -> {
            val delta = event["delta"]?.jsonPrimitive?.content.orEmpty()
            val index = output.content.lastIndex
            val block = output.content.getOrNull(index) as? StreamingCodexToolCall ?: return false
            val partialJson = block.partialJson + delta
            output.content[index] = block.copy(partialJson = partialJson, arguments = parseStreamingJson(partialJson))
            stream.push(AssistantMessageEvent.ToolCallDelta(index, delta, output))
        }
        "response.function_call_arguments.done" -> {
            val arguments = event["arguments"]?.jsonPrimitive?.content.orEmpty()
            val index = output.content.lastIndex
            val block = output.content.getOrNull(index) as? StreamingCodexToolCall ?: return false
            val delta = if (arguments.startsWith(block.partialJson)) arguments.substring(block.partialJson.length) else ""
            output.content[index] = block.copy(partialJson = arguments, arguments = parseStreamingJson(arguments))
            if (delta.isNotEmpty()) {
                stream.push(AssistantMessageEvent.ToolCallDelta(index, delta, output))
            }
        }
        "response.output_item.done" -> handleCodexOutputItemDone(event["item"]?.jsonObject ?: return false, output, stream)
        "response.completed" -> {
            updateCodexUsageAndStop(event["response"]?.jsonObject, output, model, options)
            return true
        }
    }
    return false
}

private fun handleCodexOutputItemAdded(
    item: JsonObject,
    output: AssistantMessage,
    stream: AssistantMessageEventStream,
) {
    when (item["type"]?.jsonPrimitive?.contentOrNull) {
        "reasoning" -> {
            output.content.add(ThinkingContent(thinking = ""))
            stream.push(AssistantMessageEvent.ThinkingStart(output.content.lastIndex, output))
        }
        "message" -> {
            output.content.add(TextContent(text = ""))
            stream.push(AssistantMessageEvent.TextStart(output.content.lastIndex, output))
        }
        "function_call" -> {
            val arguments = item["arguments"]?.jsonPrimitive?.content.orEmpty()
            output.content.add(
                StreamingCodexToolCall(
                    id = "${item["call_id"]?.jsonPrimitive?.content.orEmpty()}|${item["id"]?.jsonPrimitive?.content.orEmpty()}",
                    name = item["name"]?.jsonPrimitive?.content.orEmpty(),
                    arguments = parseStreamingJson(arguments),
                    partialJson = arguments,
                ),
            )
            stream.push(AssistantMessageEvent.ToolCallStart(output.content.lastIndex, output))
        }
    }
}

private fun handleCodexOutputItemDone(
    item: JsonObject,
    output: AssistantMessage,
    stream: AssistantMessageEventStream,
) {
    val index = output.content.lastIndex
    when (item["type"]?.jsonPrimitive?.contentOrNull) {
        "reasoning" -> {
            val text =
                item["summary"]
                    ?.jsonArray
                    ?.joinToString("\n\n") {
                        it.jsonObject["text"]
                            ?.jsonPrimitive
                            ?.content
                            .orEmpty()
                    }.orEmpty()
            val block =
                (output.content.getOrNull(index) as? ThinkingContent)?.copy(thinking = text, thinkingSignature = item.toString()) ?: return
            output.content[index] = block
            stream.push(AssistantMessageEvent.ThinkingEnd(index, text, output))
        }
        "message" -> {
            val text =
                item["content"]
                    ?.jsonArray
                    ?.joinToString("") { part ->
                        val obj = part.jsonObject
                        obj["text"]?.jsonPrimitive?.contentOrNull ?: obj["refusal"]?.jsonPrimitive?.content.orEmpty()
                    }.orEmpty()
            val signature =
                encodeCodexTextSignature(
                    id = item["id"]?.jsonPrimitive?.content.orEmpty(),
                    phase = item["phase"]?.jsonPrimitive?.contentOrNull,
                )
            val block = (output.content.getOrNull(index) as? TextContent)?.copy(text = text, textSignature = signature) ?: return
            output.content[index] = block
            stream.push(AssistantMessageEvent.TextEnd(index, text, output))
        }
        "function_call" -> {
            val current = output.content.getOrNull(index) as? StreamingCodexToolCall
            val toolCall =
                current?.toToolCall()
                    ?: ToolCall(
                        id = "${item["call_id"]?.jsonPrimitive?.content.orEmpty()}|${item["id"]?.jsonPrimitive?.content.orEmpty()}",
                        name = item["name"]?.jsonPrimitive?.content.orEmpty(),
                        arguments = parseStreamingJson(item["arguments"]?.jsonPrimitive?.content.orEmpty()),
                    )
            output.content[index] = toolCall
            stream.push(AssistantMessageEvent.ToolCallEnd(index, toolCall, output))
        }
    }
}

private fun updateCodexUsageAndStop(
    response: JsonObject?,
    output: AssistantMessage,
    model: Model<*>,
    options: OpenAICodexResponsesOptions,
) {
    response
        ?.get("id")
        ?.jsonPrimitive
        ?.contentOrNull
        ?.let { output.responseId = it }
    response?.get("usage")?.jsonObject?.let { usage ->
        val inputTokens = usage["input_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val outputTokens = usage["output_tokens"]?.jsonPrimitive?.intOrNull ?: 0
        val cachedTokens =
            usage["input_tokens_details"]
                ?.jsonObject
                ?.get("cached_tokens")
                ?.jsonPrimitive
                ?.intOrNull ?: 0
        output.usage.input = inputTokens - cachedTokens
        output.usage.output = outputTokens
        output.usage.cacheRead = cachedTokens
        output.usage.cacheWrite = 0
        output.usage.totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull ?: inputTokens + outputTokens
        calculateCost(model, output.usage)
        applyCodexServiceTierPricing(
            usage = output.usage,
            serviceTier =
                resolveCodexServiceTier(
                    responseServiceTier = response["service_tier"]?.jsonPrimitive?.contentOrNull,
                    requestServiceTier = options.serviceTier,
                ),
            model = model,
        )
    }
    output.stopReason =
        when (response?.get("status")?.jsonPrimitive?.contentOrNull) {
            "incomplete" -> StopReason.LENGTH
            "failed", "cancelled" -> StopReason.ERROR
            else -> StopReason.STOP
        }
    if (output.content.any { it is ToolCall } && output.stopReason == StopReason.STOP) {
        output.stopReason = StopReason.TOOL_USE
    }
}

private fun buildCodexRequest(
    model: Model<String>,
    payload: JsonObject,
    apiKey: String,
    accountId: String,
    options: OpenAICodexResponsesOptions,
): Request =
    Request
        .Builder()
        .url(resolveCodexUrl(model.baseUrl))
        .post(codexJson.encodeToString(JsonObject.serializer(), payload).toRequestBody(codexJsonMediaType))
        .header("content-type", "application/json")
        .header("accept", "text/event-stream")
        .header("authorization", "Bearer $apiKey")
        .header("chatgpt-account-id", accountId)
        .header("originator", "pi")
        .header("user-agent", "pi (android)")
        .header("openai-beta", "responses=experimental")
        .apply {
            if (options.sessionId != null) {
                header("session_id", options.sessionId)
                header("x-client-request-id", options.sessionId)
            }
            model.headers.forEach { (name, value) -> header(name, value) }
            options.headers.forEach { (name, value) -> header(name, value) }
        }.build()

private fun buildCodexWebSocketRequest(
    model: Model<String>,
    apiKey: String,
    accountId: String,
    requestId: String,
    options: OpenAICodexResponsesOptions,
): Request =
    Request
        .Builder()
        .url(resolveCodexWebSocketUrl(model.baseUrl))
        .header("authorization", "Bearer $apiKey")
        .header("chatgpt-account-id", accountId)
        .header("originator", "pi")
        .header("user-agent", "pi (android)")
        .header("openai-beta", OPENAI_BETA_RESPONSES_WEBSOCKETS)
        .header("session_id", requestId)
        .header("x-client-request-id", requestId)
        .apply {
            model.headers.forEach { (name, value) -> header(name, value) }
            options.headers.forEach { (name, value) -> header(name, value) }
        }.build()

private suspend fun executeCodexRequestWithRetry(
    requestFactory: () -> Request,
    model: Model<String>,
    options: OpenAICodexResponsesOptions,
    signal: AbortSignal?,
    setActiveCall: (okhttp3.Call?) -> Unit,
): okhttp3.Response {
    var lastError: Throwable? = null
    for (attempt in 0..CODEX_MAX_RETRIES) {
        check(signal?.aborted != true) { "Request was aborted" }
        val call = codexClient.newCall(requestFactory())
        setActiveCall(call)
        try {
            val response = call.execute()
            options.onResponse?.invoke(
                ProviderResponse(
                    status = response.code,
                    headers = response.headers.toMultimap().mapValues { entry -> entry.value.joinToString(",") },
                ),
                model,
            )
            if (response.isSuccessful) {
                return response
            }

            val errorText = response.body.string()
            response.close()
            setActiveCall(null)
            if (attempt < CODEX_MAX_RETRIES && isRetryableCodexError(response.code, errorText)) {
                delayCodexRetry(attempt, signal)
                continue
            }
            throw IllegalStateException(parseCodexError(response.code, errorText))
        } catch (error: InterruptedIOException) {
            throw IllegalStateException("Request was aborted", error)
        } catch (error: IOException) {
            if (signal?.aborted == true) {
                throw IllegalStateException("Request was aborted", error)
            }
            lastError = error
            if (attempt < CODEX_MAX_RETRIES &&
                !error.message.orEmpty().contains("usage limit", ignoreCase = true)
            ) {
                setActiveCall(null)
                delayCodexRetry(attempt, signal)
                continue
            }
            throw error
        } catch (error: Throwable) {
            if (signal?.aborted == true) {
                throw IllegalStateException("Request was aborted", error)
            }
            throw error
        }
    }
    throw lastError ?: IOException("Failed after retries")
}

private suspend fun processCodexWebSocketStream(
    model: Model<String>,
    payload: JsonObject,
    apiKey: String,
    accountId: String,
    output: AssistantMessage,
    stream: AssistantMessageEventStream,
    options: OpenAICodexResponsesOptions,
    onStart: () -> Unit,
) {
    val requestId = options.sessionId ?: createCodexRequestId()
    val acquired =
        acquireCodexWebSocket(
            request =
                buildCodexWebSocketRequest(
                    model = model,
                    apiKey = apiKey,
                    accountId = accountId,
                    requestId = requestId,
                    options = options,
                ),
            sessionId = options.sessionId,
            signal = options.signal,
        )
    val events = Channel<JsonObject>(Channel.UNLIMITED)
    val completion = CompletableDeferred<Throwable?>()
    var keepConnection = true
    try {
        acquired.connection.start(events, completion)
        val fullPayload = payload
        val requestPayload =
            if (options.transport == Transport.WEBSOCKET_CACHED && acquired.cached != null) {
                buildCachedCodexWebSocketPayload(acquired.cached, fullPayload)
            } else {
                fullPayload
            }
        val message =
            buildJsonObject {
                put("type", JsonPrimitive("response.create"))
                requestPayload.forEach { (key, value) -> put(key, value) }
            }
        check(acquired.connection.send(codexJson.encodeToString(JsonObject.serializer(), message))) {
            "Failed to send Codex WebSocket request"
        }
        onStart()
        stream.push(AssistantMessageEvent.Start(output))
        while (true) {
            check(options.signal?.aborted != true) { "Request was aborted" }
            val next = events.receiveCatching().getOrNull() ?: break
            if (handleCodexEvent(next, output, stream, model, options)) {
                break
            }
        }
        completion.await()?.let { throw it }
        if (options.signal?.aborted == true) {
            keepConnection = false
            error("Request was aborted")
        } else if (options.transport == Transport.WEBSOCKET_CACHED && acquired.cached != null && output.responseId != null) {
            acquired.cached.continuation =
                CachedCodexWebSocketContinuation(
                    lastRequestBody = fullPayload,
                    lastResponseId = output.responseId!!,
                    lastResponseItems = buildCodexResponseItems(model, output),
                )
        }
    } catch (error: Throwable) {
        acquired.cached?.continuation = null
        keepConnection = false
        throw error
    } finally {
        acquired.connection.clear(events)
        acquired.release(keepConnection)
    }
}

private data class AcquiredCodexWebSocket(
    val connection: CodexWebSocketConnection,
    val cached: CachedCodexWebSocket?,
    val release: (keep: Boolean) -> Unit,
)

private data class CachedCodexWebSocket(
    val connection: CodexWebSocketConnection,
    var busy: Boolean = true,
    var idleTimer: TimerTask? = null,
    var continuation: CachedCodexWebSocketContinuation? = null,
)

private data class CachedCodexWebSocketContinuation(
    val lastRequestBody: JsonObject,
    val lastResponseId: String,
    val lastResponseItems: JsonArray,
)

private suspend fun acquireCodexWebSocket(
    request: Request,
    sessionId: String?,
    signal: AbortSignal?,
): AcquiredCodexWebSocket {
    if (sessionId == null) {
        val connection = connectCodexWebSocket(request, signal)
        return AcquiredCodexWebSocket(connection, null) { connection.close() }
    }

    var useTemporaryConnection = false
    synchronized(codexWebSocketCache) {
        val cached = codexWebSocketCache[sessionId]
        if (cached != null) {
            cached.idleTimer?.cancel()
            cached.idleTimer = null
            if (!cached.busy && cached.connection.isReusable) {
                cached.busy = true
                return AcquiredCodexWebSocket(cached.connection, cached) { keep ->
                    releaseCachedCodexWebSocket(sessionId, cached, keep)
                }
            }
            if (cached.busy) {
                useTemporaryConnection = true
            } else if (!cached.connection.isReusable) {
                cached.connection.close()
                codexWebSocketCache.remove(sessionId, cached)
            }
        }
    }

    if (useTemporaryConnection) {
        val connection = connectCodexWebSocket(request, signal)
        return AcquiredCodexWebSocket(connection, null) { connection.close() }
    }

    val connection = connectCodexWebSocket(request, signal)
    val cached = CachedCodexWebSocket(connection = connection)
    synchronized(codexWebSocketCache) {
        codexWebSocketCache[sessionId] = cached
    }
    return AcquiredCodexWebSocket(connection, cached) { keep ->
        releaseCachedCodexWebSocket(sessionId, cached, keep)
    }
}

private fun buildCachedCodexWebSocketPayload(
    cached: CachedCodexWebSocket,
    payload: JsonObject,
): JsonObject {
    val continuation = cached.continuation ?: return payload
    val delta = getCachedCodexInputDelta(payload, continuation)
    if (delta == null) {
        cached.continuation = null
        return payload
    }
    return buildJsonObject {
        payload.forEach { (key, value) ->
            if (key != "input") {
                put(key, value)
            }
        }
        put("previous_response_id", JsonPrimitive(continuation.lastResponseId))
        put("input", delta)
    }
}

private fun getCachedCodexInputDelta(
    payload: JsonObject,
    continuation: CachedCodexWebSocketContinuation,
): JsonArray? {
    if (!requestBodiesMatchExceptInput(payload, continuation.lastRequestBody)) {
        return null
    }
    val currentInput = payload["input"] as? JsonArray ?: JsonArray(emptyList())
    val previousInput = continuation.lastRequestBody["input"] as? JsonArray ?: JsonArray(emptyList())
    val baseline = JsonArray(previousInput + continuation.lastResponseItems)
    if (currentInput.size < baseline.size) {
        return null
    }
    val prefix = JsonArray(currentInput.take(baseline.size))
    if (prefix != baseline) {
        return null
    }
    return JsonArray(currentInput.drop(baseline.size))
}

private fun requestBodiesMatchExceptInput(
    left: JsonObject,
    right: JsonObject,
): Boolean = left.withoutCodexInputState() == right.withoutCodexInputState()

private fun JsonObject.withoutCodexInputState(): JsonObject =
    JsonObject(filterKeys { key -> key != "input" && key != "previous_response_id" })

private fun buildCodexResponseItems(
    model: Model<String>,
    output: AssistantMessage,
): JsonArray =
    JsonArray(
        convertCodexMessages(model, Context(messages = listOf(output))).filter { item ->
            item.jsonObject["type"]?.jsonPrimitive?.contentOrNull != "function_call_output"
        },
    )

private fun releaseCachedCodexWebSocket(
    sessionId: String,
    cached: CachedCodexWebSocket,
    keep: Boolean,
) {
    synchronized(codexWebSocketCache) {
        if (!keep || !cached.connection.isReusable) {
            cached.connection.close()
            cached.idleTimer?.cancel()
            codexWebSocketCache.remove(sessionId, cached)
            return
        }
        cached.busy = false
        cached.idleTimer?.cancel()
        cached.idleTimer =
            object : TimerTask() {
                override fun run() {
                    synchronized(codexWebSocketCache) {
                        if (!cached.busy) {
                            cached.connection.close()
                            codexWebSocketCache.remove(sessionId, cached)
                        }
                    }
                }
            }
        codexWebSocketTimer.schedule(cached.idleTimer, SESSION_WEBSOCKET_CACHE_TTL_MS)
    }
}

private suspend fun connectCodexWebSocket(
    request: Request,
    signal: AbortSignal?,
): CodexWebSocketConnection {
    val connection = CodexWebSocketConnection()
    val webSocket = codexClient.newWebSocket(request, connection)
    connection.attach(webSocket)
    val removeAbortListener = signal?.addListener { connection.close(1000, "aborted") }
    try {
        connection.awaitOpen()
        return connection
    } finally {
        removeAbortListener?.invoke()
    }
}

private class CodexWebSocketConnection : WebSocketListener() {
    private val opened = CompletableDeferred<Unit>()
    private val closed = AtomicBoolean(false)

    @Volatile private var webSocket: WebSocket? = null

    @Volatile private var events: Channel<JsonObject>? = null

    @Volatile private var completion: CompletableDeferred<Throwable?>? = null

    @Volatile private var sawCompletion: Boolean = false

    val isReusable: Boolean
        get() = !closed.get()

    fun attach(socket: WebSocket) {
        webSocket = socket
    }

    suspend fun awaitOpen() {
        opened.await()
    }

    fun start(
        events: Channel<JsonObject>,
        completion: CompletableDeferred<Throwable?>,
    ) {
        this.events = events
        this.completion = completion
        sawCompletion = false
    }

    fun clear(expectedEvents: Channel<JsonObject>) {
        if (events === expectedEvents) {
            events = null
            completion = null
        }
    }

    fun send(text: String): Boolean = webSocket?.send(text) == true

    fun close(
        code: Int = 1000,
        reason: String = "done",
    ) {
        closed.set(true)
        webSocket?.close(code, reason)
    }

    override fun onOpen(
        webSocket: WebSocket,
        response: Response,
    ) {
        if (!opened.isCompleted) {
            opened.complete(Unit)
        }
    }

    override fun onMessage(
        webSocket: WebSocket,
        text: String,
    ) {
        handleMessage(text)
    }

    override fun onMessage(
        webSocket: WebSocket,
        bytes: ByteString,
    ) {
        handleMessage(bytes.utf8())
    }

    override fun onFailure(
        webSocket: WebSocket,
        t: Throwable,
        response: Response?,
    ) {
        closed.set(true)
        if (!opened.isCompleted) {
            opened.completeExceptionally(t)
        }
        complete(t)
    }

    override fun onClosed(
        webSocket: WebSocket,
        code: Int,
        reason: String,
    ) {
        closed.set(true)
        if (!sawCompletion) {
            complete(IOException("WebSocket closed $code $reason".trim()))
        } else {
            complete(null)
        }
    }

    private fun handleMessage(text: String) {
        val event = runCatching { codexJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val type = event["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (type == "response.completed" || type == "response.done" || type == "response.incomplete") {
            sawCompletion = true
        }
        events?.trySend(event)
        if (sawCompletion) {
            complete(null)
        }
    }

    private fun complete(error: Throwable?) {
        completion?.complete(error)
        events?.close()
    }
}

private suspend fun delayCodexRetry(
    attempt: Int,
    signal: AbortSignal?,
) {
    check(signal?.aborted != true) { "Request was aborted" }
    delay((CODEX_BASE_DELAY_MS * 2.0.pow(attempt.toDouble())).toLong())
    check(signal?.aborted != true) { "Request was aborted" }
}

private fun isRetryableCodexError(
    status: Int,
    errorText: String,
): Boolean {
    if (status in retryableCodexStatuses) {
        return true
    }
    return Regex("rate.?limit|overloaded|service.?unavailable|upstream.?connect|connection.?refused", RegexOption.IGNORE_CASE)
        .containsMatchIn(errorText)
}

private val retryableCodexStatuses: Set<Int> = setOf(429, 500, 502, 503, 504)

private fun processCodexSse(
    reader: java.io.BufferedReader,
    onEvent: (JsonObject) -> Boolean,
) {
    val dataLines = mutableListOf<String>()
    while (true) {
        val line = reader.readLine() ?: break
        if (line.isEmpty()) {
            if (dataLines.isNotEmpty()) {
                if (parseCodexSseData(dataLines.joinToString("\n"))?.let(onEvent) == true) {
                    return
                }
                dataLines.clear()
            }
            continue
        }
        if (line.startsWith("data:")) {
            dataLines += line.removePrefix("data:").trimStart()
        }
    }
    if (dataLines.isNotEmpty()) {
        parseCodexSseData(dataLines.joinToString("\n"))?.let(onEvent)
    }
}

private fun parseCodexSseData(data: String): JsonObject? =
    if (data.trim() == "[DONE]") {
        null
    } else {
        runCatching { codexJson.parseToJsonElement(data).jsonObject }.getOrNull()
    }

private fun parseCodexError(
    status: Int,
    body: String,
): String =
    runCatching {
        val obj = codexJson.parseToJsonElement(body).jsonObject
        val error = obj["error"]?.jsonObject
        val message = error?.get("message")?.jsonPrimitive?.contentOrNull ?: obj["message"]?.jsonPrimitive?.contentOrNull
        val code = error?.get("code")?.jsonPrimitive?.contentOrNull ?: obj["code"]?.jsonPrimitive?.contentOrNull
        if (code?.contains("usage", ignoreCase = true) == true || message?.contains("usage limit", ignoreCase = true) == true) {
            "ChatGPT usage limit reached. Try again later."
        } else {
            "OpenAI Codex request failed with $status: ${message ?: body}"
        }
    }.getOrElse { "OpenAI Codex request failed with $status: $body" }

private fun resolveCodexUrl(baseUrl: String): String {
    val normalized = baseUrl.trimEnd('/')
    return when {
        normalized.endsWith("/codex/responses") -> normalized
        normalized.endsWith("/codex") -> "$normalized/responses"
        else -> "$normalized/codex/responses"
    }
}

private fun resolveCodexWebSocketUrl(baseUrl: String): String {
    val url = resolveCodexUrl(baseUrl)
    return when {
        url.startsWith("https://") -> "wss://${url.removePrefix("https://")}"
        url.startsWith("http://") -> "ws://${url.removePrefix("http://")}"
        else -> url
    }
}

private fun createCodexRequestId(): String = UUID.randomUUID().toString()

private fun clampCodexReasoningEffort(
    modelId: String,
    effort: ThinkingLevel,
): String {
    val value =
        when (effort) {
            ThinkingLevel.MINIMAL -> "minimal"
            ThinkingLevel.LOW -> "low"
            ThinkingLevel.MEDIUM -> "medium"
            ThinkingLevel.HIGH -> "high"
            ThinkingLevel.XHIGH -> "xhigh"
        }
    val id = modelId.substringAfterLast("/")
    return when {
        (id.startsWith("gpt-5.2") || id.startsWith("gpt-5.3") || id.startsWith("gpt-5.4") || id.startsWith("gpt-5.5")) &&
            value == "minimal" -> "low"
        id == "gpt-5.1" && value == "xhigh" -> "high"
        id == "gpt-5.1-codex-mini" && (value == "high" || value == "xhigh") -> "high"
        id == "gpt-5.1-codex-mini" -> "medium"
        else -> value
    }
}

private data class CodexTextSignature(
    val id: String,
    val phase: String? = null,
)

private fun parseCodexTextSignature(signature: String?): CodexTextSignature? {
    if (signature.isNullOrBlank()) {
        return null
    }
    val parsed =
        if (signature.startsWith("{")) {
            runCatching {
                val obj = codexJson.parseToJsonElement(signature).jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
                val phase = obj["phase"]?.jsonPrimitive?.contentOrNull?.takeIf(::isCodexTextPhase)
                CodexTextSignature(id = id, phase = phase)
            }.getOrNull()
        } else {
            CodexTextSignature(id = signature)
        }
    val id = parsed?.id ?: return null
    return parsed.copy(id = if (id.length > 64) "msg_${id.hashCode().toUInt().toString(16)}" else id)
}

private fun encodeCodexTextSignature(
    id: String,
    phase: String?,
): String =
    buildJsonObject {
        put("v", JsonPrimitive(1))
        put("id", JsonPrimitive(id))
        phase?.takeIf(::isCodexTextPhase)?.let { put("phase", JsonPrimitive(it)) }
    }.toString()

private fun isCodexTextPhase(phase: String): Boolean = phase == "commentary" || phase == "final_answer"

private fun resolveCodexServiceTier(
    responseServiceTier: String?,
    requestServiceTier: String?,
): String? =
    if (responseServiceTier == "default" && (requestServiceTier == "flex" || requestServiceTier == "priority")) {
        requestServiceTier
    } else {
        responseServiceTier ?: requestServiceTier
    }

private fun applyCodexServiceTierPricing(
    usage: Usage,
    serviceTier: String?,
    model: Model<*>,
) {
    val multiplier =
        when (serviceTier) {
            "flex" -> 0.5
            "priority" -> if (model.id == "gpt-5.5") 2.5 else 2.0
            else -> 1.0
        }
    if (multiplier == 1.0) {
        return
    }
    usage.cost.input *= multiplier
    usage.cost.output *= multiplier
    usage.cost.cacheRead *= multiplier
    usage.cost.cacheWrite *= multiplier
    usage.cost.total = usage.cost.input + usage.cost.output + usage.cost.cacheRead + usage.cost.cacheWrite
}

private fun normalizeCodexToolCallId(
    id: String,
    source: AssistantMessage,
    model: Model<String>,
): String {
    if (!codexToolCallProviders.contains(model.provider)) {
        return normalizeCodexIdPart(id)
    }
    if (!id.contains("|")) {
        return normalizeCodexIdPart(id)
    }
    val parts = id.split("|", limit = 2)
    val callId = normalizeCodexIdPart(parts[0])
    var itemId =
        if (source.provider != model.provider || source.api != model.api) {
            buildForeignCodexResponsesItemId(parts[1])
        } else {
            normalizeCodexIdPart(parts[1])
        }
    if (!itemId.startsWith("fc_")) {
        itemId = normalizeCodexIdPart("fc_$itemId")
    }
    return "$callId|$itemId"
}

private fun normalizeCodexIdPart(value: String): String =
    value
        .replace(Regex("[^a-zA-Z0-9_-]"), "_")
        .take(64)
        .trimEnd('_')

private fun buildForeignCodexResponsesItemId(value: String): String = normalizeCodexIdPart("fc_${value.hashCode().toUInt().toString(16)}")

private fun finishCodexWithError(
    stream: AssistantMessageEventStream,
    output: AssistantMessage,
    signal: AbortSignal?,
    error: Throwable,
) {
    output.content.replaceAll { block ->
        when (block) {
            is StreamingCodexToolCall -> block.toToolCall()
            else -> block
        }
    }
    output.stopReason = if (signal?.aborted == true) StopReason.ABORTED else StopReason.ERROR
    output.errorMessage = error.message ?: error.toString()
    stream.push(AssistantMessageEvent.Error(output.stopReason, output))
}

@Suppress("UNCHECKED_CAST")
private fun castOpenAICodexModel(model: Model<*>): Model<String> = model as Model<String>

private data class StreamingCodexToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject,
    val partialJson: String,
) : pi.ai.core.AssistantContentBlock {
    fun toToolCall(): ToolCall = ToolCall(id = id, name = name, arguments = arguments)
}

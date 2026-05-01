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
import kotlinx.serialization.json.booleanOrNull
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
import pi.ai.core.AbortSignal
import pi.ai.core.ApiProvider
import pi.ai.core.AssistantContentBlock
import pi.ai.core.AssistantMessage
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.AssistantMessageEventStream
import pi.ai.core.Context
import pi.ai.core.GOOGLE_GENERATIVE_AI_API
import pi.ai.core.ImageContent
import pi.ai.core.InputModality
import pi.ai.core.Model
import pi.ai.core.ProviderResponse
import pi.ai.core.SimpleStreamOptions
import pi.ai.core.StopReason
import pi.ai.core.StreamOptions
import pi.ai.core.TextContent
import pi.ai.core.ThinkingBudgets
import pi.ai.core.ThinkingContent
import pi.ai.core.ThinkingLevel
import pi.ai.core.Tool
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultMessage
import pi.ai.core.Usage
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import pi.ai.core.calculateCost
import pi.ai.core.getEnvApiKey
import java.io.IOException

public enum class GoogleToolChoice {
    AUTO,
    NONE,
    ANY,
}

public enum class GoogleThinkingLevel {
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
}

public data class GoogleThinkingOptions(
    val enabled: Boolean,
    val budgetTokens: Int? = null,
    val level: GoogleThinkingLevel? = null,
)

public data class GoogleOptions(
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val signal: AbortSignal? = null,
    val apiKey: String? = null,
    val onPayload: (suspend (payload: Any, model: Model<*>) -> Any?)? = null,
    val onResponse: (suspend (response: ProviderResponse, model: Model<*>) -> Unit)? = null,
    val headers: Map<String, String> = emptyMap(),
    val thinking: GoogleThinkingOptions? = null,
    val toolChoice: GoogleToolChoice? = null,
)

private val json: Json =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

private val client: OkHttpClient = OkHttpClient.Builder().build()
private val jsonMediaType = "application/json".toMediaType()
private const val SKIP_THOUGHT_SIGNATURE: String = "skip_thought_signature_validator"

public object GoogleApiProvider : ApiProvider {
    override val api: String = GOOGLE_GENERATIVE_AI_API

    override fun stream(
        model: Model<*>,
        context: Context,
        options: StreamOptions?,
    ): AssistantMessageEventStream {
        require(model.api == GOOGLE_GENERATIVE_AI_API) { "Google provider only supports $GOOGLE_GENERATIVE_AI_API" }
        return streamGoogle(
            model = castGoogleModel(model),
            context = context,
            options =
                GoogleOptions(
                    temperature = options?.temperature,
                    maxTokens = options?.maxTokens,
                    signal = options?.signal,
                    apiKey = options?.apiKey,
                    onPayload = options?.onPayload,
                    onResponse = options?.onResponse,
                    headers = options?.headers.orEmpty(),
                ),
        )
    }

    override fun streamSimple(
        model: Model<*>,
        context: Context,
        options: SimpleStreamOptions?,
    ): AssistantMessageEventStream {
        require(model.api == GOOGLE_GENERATIVE_AI_API) { "Google provider only supports $GOOGLE_GENERATIVE_AI_API" }
        return streamSimpleGoogle(castGoogleModel(model), context, options)
    }
}

public fun streamSimpleGoogle(
    model: Model<String>,
    context: Context,
    options: SimpleStreamOptions? = null,
): AssistantMessageEventStream {
    val apiKey: String =
        options?.apiKey ?: getEnvApiKey(model.provider)
            ?: throw IllegalArgumentException("No API key for provider: ${model.provider}")

    val base = buildBaseOptions(model, options, apiKey)
    val thinking =
        if (options?.reasoning == null) {
            GoogleThinkingOptions(enabled = false)
        } else {
            val effort = clampReasoning(options.reasoning) ?: ThinkingLevel.HIGH
            if (isGemini3ProModel(model) || isGemini3FlashModel(model) || isGemma4Model(model)) {
                GoogleThinkingOptions(enabled = true, level = getThinkingLevel(effort, model))
            } else {
                GoogleThinkingOptions(
                    enabled = true,
                    budgetTokens = getGoogleBudget(model, effort, options.thinkingBudgets),
                )
            }
        }

    return streamGoogle(
        model = model,
        context = context,
        options =
            GoogleOptions(
                temperature = base.temperature,
                maxTokens = base.maxTokens,
                signal = base.signal,
                apiKey = base.apiKey,
                onPayload = base.onPayload,
                onResponse = base.onResponse,
                headers = base.headers,
                thinking = thinking,
            ),
    )
}

public fun streamGoogle(
    model: Model<String>,
    context: Context,
    options: GoogleOptions = GoogleOptions(),
): AssistantMessageEventStream {
    val stream = AssistantMessageEventStream()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    scope.launch {
        val output =
            AssistantMessage(
                content = mutableListOf(),
                api = GOOGLE_GENERATIVE_AI_API,
                provider = model.provider,
                model = model.id,
                usage = Usage(),
                stopReason = StopReason.STOP,
                timestamp = System.currentTimeMillis(),
            )
        val signal = options.signal
        if (signal?.aborted == true) {
            finishGoogleWithError(stream, output, signal, IllegalStateException("Request was aborted"))
            scope.cancel()
            return@launch
        }

        var activeCall: okhttp3.Call? = null
        val removeAbortListener =
            signal?.addListener {
                activeCall?.cancel()
            }

        try {
            val apiKey = options.apiKey ?: getEnvApiKey(model.provider).orEmpty()
            var payload = buildGoogleParams(model, context, options)
            val replacement = options.onPayload?.invoke(payload, model)
            if (replacement is JsonObject) {
                payload = replacement
            }

            val request =
                Request
                    .Builder()
                    .url("${model.baseUrl.trimEnd('/')}/models/${model.id}:streamGenerateContent?alt=sse")
                    .post(json.encodeToString(JsonObject.serializer(), payload).toRequestBody(jsonMediaType))
                    .header("content-type", "application/json")
                    .header("accept", "text/event-stream")
                    .header("x-goog-api-key", apiKey)
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
                throw IOException("Google request failed with ${response.code}: $body")
            }

            stream.push(AssistantMessageEvent.Start(output))
            val reader = response.body.charStream().buffered()
            val dataLines = mutableListOf<String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    if (dataLines.isNotEmpty()) {
                        handleGoogleStreamChunk(dataLines.joinToString("\n"), output, stream, model)
                    }
                    dataLines.clear()
                    continue
                }
                if (line.startsWith("data:")) {
                    dataLines += line.removePrefix("data:").trimStart()
                }
            }
            if (dataLines.isNotEmpty()) {
                handleGoogleStreamChunk(dataLines.joinToString("\n"), output, stream, model)
            }

            if (signal?.aborted == true) {
                error("Request was aborted")
            }
            if (output.stopReason == StopReason.ERROR || output.stopReason == StopReason.ABORTED) {
                error(output.errorMessage ?: "Google stream failed")
            }

            closeOpenGoogleTextBlock(output, stream)
            stream.push(AssistantMessageEvent.Done(output.stopReason, output))
        } catch (error: Throwable) {
            finishGoogleWithError(stream, output, signal, error)
        } finally {
            removeAbortListener?.invoke()
            scope.cancel()
        }
    }

    return stream
}

private fun finishGoogleWithError(
    stream: AssistantMessageEventStream,
    output: AssistantMessage,
    signal: AbortSignal?,
    error: Throwable,
) {
    output.stopReason = if (signal?.aborted == true) StopReason.ABORTED else StopReason.ERROR
    output.errorMessage = error.message ?: error.toString()
    stream.push(AssistantMessageEvent.Error(output.stopReason, output))
}

@Suppress("UNCHECKED_CAST")
private fun castGoogleModel(model: Model<*>): Model<String> = model as Model<String>

internal fun buildGoogleParams(
    model: Model<String>,
    context: Context,
    options: GoogleOptions = GoogleOptions(),
): JsonObject =
    buildJsonObject {
        put("contents", convertGoogleMessages(model, context))
        val generationConfig =
            buildJsonObject {
                options.temperature?.let { put("temperature", JsonPrimitive(it)) }
                options.maxTokens?.let { put("maxOutputTokens", JsonPrimitive(it)) }
                buildThinkingConfig(model, options.thinking)?.let { put("thinkingConfig", it) }
            }
        if (generationConfig.isNotEmpty()) {
            put("generationConfig", generationConfig)
        }
        context.systemPrompt?.let { put("systemInstruction", buildTextPart(it)) }
        if (context.tools.isNotEmpty()) {
            put("tools", convertGoogleTools(context.tools))
            options.toolChoice?.let { choice ->
                put(
                    "toolConfig",
                    buildJsonObject {
                        put(
                            "functionCallingConfig",
                            buildJsonObject {
                                put("mode", JsonPrimitive(choice.name))
                            },
                        )
                    },
                )
            }
        }
    }

internal fun convertGoogleMessages(
    model: Model<String>,
    context: Context,
): JsonArray {
    val contents = mutableListOf<JsonElement>()
    val transformedMessages =
        transformMessages(context.messages, model) { id, normalizedModel, _ ->
            normalizeToolCallId(id, normalizedModel.id)
        }
    transformedMessages.forEach { message ->
        when (message) {
            is UserMessage -> convertGoogleUserMessage(model, message)?.let(contents::add)
            is AssistantMessage -> convertGoogleAssistantMessage(model, message)?.let(contents::add)
            is ToolResultMessage -> contents.addGoogleToolResultMessage(model, message)
        }
    }
    return JsonArray(contents)
}

private fun buildTextPart(text: String): JsonObject =
    buildJsonObject {
        put("parts", buildJsonArray { add(buildJsonObject { put("text", JsonPrimitive(sanitizeSurrogates(text))) }) })
    }

private fun convertGoogleUserMessage(
    model: Model<String>,
    message: UserMessage,
): JsonObject? {
    val parts: List<JsonElement> =
        when (val content = message.content) {
            is UserMessageContent.Text -> listOf(buildJsonObject { put("text", JsonPrimitive(sanitizeSurrogates(content.value))) })
            is UserMessageContent.Structured ->
                content.parts.mapNotNull { part ->
                    when (part) {
                        is TextContent -> buildJsonObject { put("text", JsonPrimitive(sanitizeSurrogates(part.text))) }
                        is ImageContent ->
                            if (model.input.contains(InputModality.IMAGE)) {
                                buildInlineData(part)
                            } else {
                                null
                            }
                    }
                }
        }
    if (parts.isEmpty()) {
        return null
    }
    return buildJsonObject {
        put("role", JsonPrimitive("user"))
        put("parts", JsonArray(parts))
    }
}

private fun convertGoogleAssistantMessage(
    model: Model<String>,
    message: AssistantMessage,
): JsonObject? {
    val isSameProviderAndModel = message.provider == model.provider && message.model == model.id
    val parts: List<JsonElement> =
        message.content.mapNotNull<AssistantContentBlock, JsonElement> { block ->
            when (block) {
                is TextContent -> {
                    if (block.text.isBlank()) {
                        null
                    } else {
                        val thoughtSignature = resolveThoughtSignature(isSameProviderAndModel, block.textSignature)
                        buildJsonObject {
                            put("text", JsonPrimitive(sanitizeSurrogates(block.text)))
                            thoughtSignature?.let { put("thoughtSignature", JsonPrimitive(it)) }
                        }
                    }
                }
                is ThinkingContent -> {
                    if (block.thinking.isBlank()) {
                        null
                    } else if (isSameProviderAndModel) {
                        val thoughtSignature = resolveThoughtSignature(isSameProviderAndModel, block.thinkingSignature)
                        buildJsonObject {
                            put("thought", JsonPrimitive(true))
                            put("text", JsonPrimitive(sanitizeSurrogates(block.thinking)))
                            thoughtSignature?.let { put("thoughtSignature", JsonPrimitive(it)) }
                        }
                    } else {
                        buildJsonObject { put("text", JsonPrimitive(sanitizeSurrogates(block.thinking))) }
                    }
                }
                is ToolCall -> {
                    val thoughtSignature = resolveThoughtSignature(isSameProviderAndModel, block.thoughtSignature)
                    val effectiveSignature =
                        thoughtSignature ?: if (model.id.lowercase().contains("gemini-3")) SKIP_THOUGHT_SIGNATURE else null
                    buildJsonObject {
                        put(
                            "functionCall",
                            buildJsonObject {
                                put("name", JsonPrimitive(block.name))
                                put("args", block.arguments)
                                if (requiresToolCallId(model.id)) {
                                    put("id", JsonPrimitive(normalizeToolCallId(block.id, model.id)))
                                }
                            },
                        )
                        effectiveSignature?.let { put("thoughtSignature", JsonPrimitive(it)) }
                    }
                }
                else -> null
            }
        }
    if (parts.isEmpty()) {
        return null
    }
    return buildJsonObject {
        put("role", JsonPrimitive("model"))
        put("parts", JsonArray(parts))
    }
}

private fun MutableList<JsonElement>.addGoogleToolResultMessage(
    model: Model<String>,
    message: ToolResultMessage,
) {
    val textContent = message.content.filterIsInstance<TextContent>()
    val textResult = textContent.joinToString("\n") { it.text }
    val imageContent =
        if (model.input.contains(InputModality.IMAGE)) {
            message.content.filterIsInstance<ImageContent>()
        } else {
            emptyList()
        }
    val hasText = textResult.isNotEmpty()
    val hasImages = imageContent.isNotEmpty()
    val responseValue =
        when {
            hasText -> textResult
            hasImages -> "(see attached image)"
            else -> ""
        }
    val imageParts = imageContent.map(::buildInlineData)
    val supportsMultimodalFunctionResponse = supportsMultimodalFunctionResponse(model.id)
    val functionResponsePart =
        buildJsonObject {
            put(
                "functionResponse",
                buildJsonObject {
                    put("name", JsonPrimitive(message.toolName))
                    put(
                        "response",
                        buildJsonObject {
                            put(if (message.isError) "error" else "output", JsonPrimitive(sanitizeSurrogates(responseValue)))
                        },
                    )
                    if (hasImages && supportsMultimodalFunctionResponse) {
                        put("parts", JsonArray(imageParts))
                    }
                    if (requiresToolCallId(model.id)) {
                        put("id", JsonPrimitive(normalizeToolCallId(message.toolCallId, model.id)))
                    }
                },
            )
        }

    val lastContent = lastOrNull()?.jsonObject
    val lastParts = lastContent?.get("parts") as? JsonArray
    val canMerge =
        lastContent?.get("role")?.jsonPrimitive?.contentOrNull == "user" &&
            lastParts?.any { it.jsonObject["functionResponse"] != null } == true
    if (canMerge) {
        removeAt(lastIndex)
        add(
            JsonObject(
                lastContent.toMutableMap().apply {
                    put("parts", JsonArray(lastParts + functionResponsePart))
                },
            ),
        )
    } else {
        add(
            buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("parts", buildJsonArray { add(functionResponsePart) })
            },
        )
    }

    if (hasImages && !supportsMultimodalFunctionResponse) {
        add(
            buildJsonObject {
                put("role", JsonPrimitive("user"))
                put(
                    "parts",
                    buildJsonArray {
                        add(buildJsonObject { put("text", JsonPrimitive("Tool result image:")) })
                        imageParts.forEach(::add)
                    },
                )
            },
        )
    }
}

private fun buildInlineData(part: ImageContent): JsonObject =
    buildJsonObject {
        put(
            "inlineData",
            buildJsonObject {
                put("mimeType", JsonPrimitive(part.mimeType))
                put("data", JsonPrimitive(part.data))
            },
        )
    }

private fun convertGoogleTools(tools: List<Tool<*>>): JsonArray =
    buildJsonArray {
        add(
            buildJsonObject {
                put(
                    "functionDeclarations",
                    buildJsonArray {
                        tools.forEach { tool ->
                            add(
                                buildJsonObject {
                                    put("name", JsonPrimitive(tool.name))
                                    put("description", JsonPrimitive(tool.description))
                                    put("parametersJsonSchema", tool.parameters)
                                },
                            )
                        }
                    },
                )
            },
        )
    }

private fun buildThinkingConfig(
    model: Model<String>,
    thinking: GoogleThinkingOptions?,
): JsonObject? {
    if (!model.reasoning || thinking == null) {
        return null
    }
    return if (thinking.enabled) {
        buildJsonObject {
            put("includeThoughts", JsonPrimitive(true))
            thinking.level?.let { put("thinkingLevel", JsonPrimitive(it.name)) }
            thinking.budgetTokens?.let { put("thinkingBudget", JsonPrimitive(it)) }
        }
    } else {
        getDisabledThinkingConfig(model)
    }
}

internal fun handleGoogleStreamChunk(
    rawData: String,
    output: AssistantMessage,
    stream: AssistantMessageEventStream,
    model: Model<String>,
) {
    val chunk = json.parseToJsonElement(rawData).jsonObject
    output.responseId = output.responseId ?: chunk["responseId"]?.jsonPrimitive?.contentOrNull

    val candidate = chunk["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
    val parts =
        candidate
            ?.get("content")
            ?.jsonObject
            ?.get("parts")
            ?.jsonArray
            .orEmpty()
    for (part in parts) {
        handleGooglePart(part.jsonObject, output, stream)
    }

    candidate?.get("finishReason")?.jsonPrimitive?.contentOrNull?.let { finishReason ->
        output.stopReason = mapGoogleStopReason(finishReason)
        if (output.content.any { it is ToolCall }) {
            output.stopReason = StopReason.TOOL_USE
        }
    }

    chunk["usageMetadata"]?.jsonObject?.let { usage ->
        val cached = usage["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
        output.usage.input = (usage["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0) - cached
        output.usage.output =
            (usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0) +
            (usage["thoughtsTokenCount"]?.jsonPrimitive?.intOrNull ?: 0)
        output.usage.cacheRead = cached
        output.usage.cacheWrite = 0
        output.usage.totalTokens = usage["totalTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
        calculateCost(model, output.usage)
    }
}

private fun handleGooglePart(
    part: JsonObject,
    output: AssistantMessage,
    stream: AssistantMessageEventStream,
) {
    val text = part["text"]?.jsonPrimitive?.contentOrNull
    if (text != null) {
        val isThinking = part["thought"]?.jsonPrimitive?.booleanOrNull == true
        appendGoogleText(text, isThinking, part["thoughtSignature"]?.jsonPrimitive?.contentOrNull, output, stream)
    }

    val functionCall = part["functionCall"]?.jsonObject ?: return
    closeOpenGoogleTextBlock(output, stream)
    val providedId = functionCall["id"]?.jsonPrimitive?.contentOrNull
    val needsNewId = providedId.isNullOrBlank() || output.content.any { it is ToolCall && it.id == providedId }
    val name = functionCall["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val id = if (needsNewId) "${name}_${System.currentTimeMillis()}_${output.content.size + 1}" else providedId
    val toolCall =
        ToolCall(
            id = id,
            name = name,
            arguments = functionCall["args"]?.jsonObject ?: JsonObject(emptyMap()),
            thoughtSignature = part["thoughtSignature"]?.jsonPrimitive?.contentOrNull,
        )
    output.content += toolCall
    val index = output.content.lastIndex
    stream.push(AssistantMessageEvent.ToolCallStart(index, output))
    stream.push(AssistantMessageEvent.ToolCallDelta(index, json.encodeToString(JsonObject.serializer(), toolCall.arguments), output))
    stream.push(AssistantMessageEvent.ToolCallEnd(index, toolCall, output))
}

private fun appendGoogleText(
    delta: String,
    thinking: Boolean,
    thoughtSignature: String?,
    output: AssistantMessage,
    stream: AssistantMessageEventStream,
) {
    val last = output.content.lastOrNull()
    val sameBlock = if (thinking) last is ThinkingContent else last is TextContent
    if (!sameBlock) {
        closeOpenGoogleTextBlock(output, stream)
        output.content += if (thinking) ThinkingContent("") else TextContent("")
        val index = output.content.lastIndex
        if (thinking) {
            stream.push(AssistantMessageEvent.ThinkingStart(index, output))
        } else {
            stream.push(AssistantMessageEvent.TextStart(index, output))
        }
    }

    val index = output.content.lastIndex
    when (val block = output.content[index]) {
        is ThinkingContent -> {
            val updated =
                block.copy(
                    thinking = block.thinking + delta,
                    thinkingSignature = retainThoughtSignature(block.thinkingSignature, thoughtSignature),
                )
            output.content[index] = updated
            stream.push(AssistantMessageEvent.ThinkingDelta(index, delta, output))
        }
        is TextContent -> {
            val updated =
                block.copy(
                    text = block.text + delta,
                    textSignature = retainThoughtSignature(block.textSignature, thoughtSignature),
                )
            output.content[index] = updated
            stream.push(AssistantMessageEvent.TextDelta(index, delta, output))
        }
        else -> {}
    }
}

private fun closeOpenGoogleTextBlock(
    output: AssistantMessage,
    stream: AssistantMessageEventStream,
) {
    val index = output.content.lastIndex
    if (index < 0) {
        return
    }
    when (val block = output.content[index]) {
        is TextContent -> stream.push(AssistantMessageEvent.TextEnd(index, block.text, output))
        is ThinkingContent -> stream.push(AssistantMessageEvent.ThinkingEnd(index, block.thinking, output))
        else -> {}
    }
}

private fun mapGoogleStopReason(reason: String): StopReason =
    when (reason) {
        "STOP" -> StopReason.STOP
        "MAX_TOKENS" -> StopReason.LENGTH
        else -> StopReason.ERROR
    }

private fun retainThoughtSignature(
    existing: String?,
    incoming: String?,
): String? = if (!incoming.isNullOrEmpty()) incoming else existing

private val base64SignaturePattern = Regex("^[A-Za-z0-9+/]+={0,2}$")

private fun resolveThoughtSignature(
    isSameProviderAndModel: Boolean,
    signature: String?,
): String? =
    if (isSameProviderAndModel && isValidThoughtSignature(signature)) {
        signature
    } else {
        null
    }

private fun isValidThoughtSignature(signature: String?): Boolean =
    !signature.isNullOrEmpty() &&
        signature.length % 4 == 0 &&
        base64SignaturePattern.matches(signature)

private fun requiresToolCallId(modelId: String): Boolean = modelId.startsWith("claude-") || modelId.startsWith("gpt-oss-")

private fun normalizeToolCallId(
    id: String,
    modelId: String,
): String =
    if (requiresToolCallId(modelId)) {
        id.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(64)
    } else {
        id
    }

private fun supportsMultimodalFunctionResponse(modelId: String): Boolean {
    val match = Regex("^gemini(?:-live)?-(\\d+)").find(modelId.lowercase()) ?: return true
    return match.groupValues[1].toInt() >= 3
}

private fun isGemma4Model(model: Model<String>): Boolean = Regex("gemma-?4").containsMatchIn(model.id.lowercase())

private fun isGemini3ProModel(model: Model<String>): Boolean = Regex("gemini-3(?:\\.\\d+)?-pro").containsMatchIn(model.id.lowercase())

private fun isGemini3FlashModel(model: Model<String>): Boolean = Regex("gemini-3(?:\\.\\d+)?-flash").containsMatchIn(model.id.lowercase())

private fun getDisabledThinkingConfig(model: Model<String>): JsonObject =
    buildJsonObject {
        when {
            isGemini3ProModel(model) -> put("thinkingLevel", JsonPrimitive(GoogleThinkingLevel.LOW.name))
            isGemini3FlashModel(model) || isGemma4Model(model) -> put("thinkingLevel", JsonPrimitive(GoogleThinkingLevel.MINIMAL.name))
            else -> put("thinkingBudget", JsonPrimitive(0))
        }
    }

private fun getThinkingLevel(
    effort: ThinkingLevel,
    model: Model<String>,
): GoogleThinkingLevel =
    if (isGemini3ProModel(model)) {
        when (effort) {
            ThinkingLevel.MINIMAL, ThinkingLevel.LOW -> GoogleThinkingLevel.LOW
            ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH -> GoogleThinkingLevel.HIGH
        }
    } else if (isGemma4Model(model)) {
        when (effort) {
            ThinkingLevel.MINIMAL, ThinkingLevel.LOW -> GoogleThinkingLevel.MINIMAL
            ThinkingLevel.MEDIUM, ThinkingLevel.HIGH, ThinkingLevel.XHIGH -> GoogleThinkingLevel.HIGH
        }
    } else {
        when (effort) {
            ThinkingLevel.MINIMAL -> GoogleThinkingLevel.MINIMAL
            ThinkingLevel.LOW -> GoogleThinkingLevel.LOW
            ThinkingLevel.MEDIUM -> GoogleThinkingLevel.MEDIUM
            ThinkingLevel.HIGH, ThinkingLevel.XHIGH -> GoogleThinkingLevel.HIGH
        }
    }

private fun getGoogleBudget(
    model: Model<String>,
    effort: ThinkingLevel,
    customBudgets: ThinkingBudgets?,
): Int {
    val custom =
        when (effort) {
            ThinkingLevel.MINIMAL -> customBudgets?.minimal
            ThinkingLevel.LOW -> customBudgets?.low
            ThinkingLevel.MEDIUM -> customBudgets?.medium
            ThinkingLevel.HIGH, ThinkingLevel.XHIGH -> customBudgets?.high
        }
    if (custom != null) {
        return custom
    }

    val id = model.id
    return when {
        id.contains("2.5-pro") ->
            when (effort) {
                ThinkingLevel.MINIMAL -> 128
                ThinkingLevel.LOW -> 2048
                ThinkingLevel.MEDIUM -> 8192
                ThinkingLevel.HIGH, ThinkingLevel.XHIGH -> 32768
            }
        id.contains("2.5-flash-lite") ->
            when (effort) {
                ThinkingLevel.MINIMAL -> 512
                ThinkingLevel.LOW -> 2048
                ThinkingLevel.MEDIUM -> 8192
                ThinkingLevel.HIGH, ThinkingLevel.XHIGH -> 24576
            }
        id.contains("2.5-flash") ->
            when (effort) {
                ThinkingLevel.MINIMAL -> 128
                ThinkingLevel.LOW -> 2048
                ThinkingLevel.MEDIUM -> 8192
                ThinkingLevel.HIGH, ThinkingLevel.XHIGH -> 24576
            }
        else -> -1
    }
}

package pi.ai.core.parity

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory
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
import pi.ai.core.ModelCost
import pi.ai.core.SimpleStreamOptions
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.ThinkingContent
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultMessage
import pi.ai.core.Usage
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import pi.ai.core.registerApiProvider
import pi.ai.core.streamSimple
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

@Tag("parity")
class AiParityTest {
    private val json: Json =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
        }

    @TestFactory
    fun `fixture parity scenarios`(): List<DynamicTest> {
        val rootDir = parityRootDir()
        val scenarioDir = rootDir.resolve("parity/scenarios")
        return Files
            .list(scenarioDir)
            .use { paths ->
                paths
                    .filter { path -> path.extension == "json" }
                    .sorted()
                    .map { path ->
                        DynamicTest.dynamicTest(path.nameWithoutExtension) {
                            val scenario = json.parseToJsonElement(Files.readString(path)).jsonObject
                            if (scenario.string("suite") != "pi-ai-core") {
                                return@dynamicTest
                            }

                            val actual = runScenario(scenario)
                            val fixturePath = rootDir.resolve("parity/fixtures/${scenario.string("id")}.json")
                            val expected = json.parseToJsonElement(Files.readString(fixturePath))

                            assertEquals(
                                expected,
                                actual,
                                buildString {
                                    appendLine("Parity fixture mismatch for ${scenario.string("id")}")
                                    appendLine(json.encodeToString(JsonElement.serializer(), actual))
                                },
                            )
                        }
                    }.toList()
            }
    }

    private fun runScenario(scenario: JsonObject): JsonElement {
        require(scenario.string("kind") == "ai_stream_faux") {
            "Unsupported pi-ai parity scenario kind: ${scenario.string("kind")}"
        }

        val model = scenario.modelSpec()
        val tokenSize =
            scenario["provider"]!!
                .jsonObject["tokenSize"]!!
                .jsonObject["min"]!!
                .jsonPrimitive.int
        registerApiProvider(FauxParityProvider(model, scenario["responses"]!!.jsonArray, tokenSize))

        val context = scenario.toContext()
        val stream = streamSimple(model, context)
        val rawEvents = runBlocking { stream.asFlow().toList() }
        val result = runBlocking { stream.result() }

        return buildJsonObject {
            put("scenarioId", scenario.string("id"))
            put("suite", scenario.string("suite"))
            put("events", JsonArray(normalizeAssistantEventSequence(rawEvents)))
            put("result", normalizeMessage(result))
        }
    }

    private fun JsonObject.modelSpec(): Model<String> {
        val model = this["model"]!!.jsonObject
        return Model(
            id = model.string("id"),
            name = model.string("name"),
            api = model.string("api"),
            provider = model.string("provider"),
            baseUrl = "https://example.invalid",
            reasoning = model.boolean("reasoning"),
            input = model["input"]!!.jsonArray.map { InputModality.valueOf(it.jsonPrimitive.content.uppercase()) }.toSet(),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = model.int("contextWindow"),
            maxTokens = model.int("maxTokens"),
        )
    }

    private fun JsonObject.toContext(): Context {
        val context = this["context"]!!.jsonObject
        return Context(
            systemPrompt = context.optionalString("systemPrompt"),
            messages =
                context["messages"]!!
                    .jsonArray
                    .mapIndexed { index, message ->
                        val jsonMessage = message.jsonObject
                        UserMessage(
                            content = jsonMessage.userMessageContent(),
                            timestamp = index + 1L,
                        )
                    },
            tools = emptyList(),
        )
    }

    private fun JsonObject.userMessageContent(): UserMessageContent {
        val content = this["content"]!!
        return when {
            content is JsonPrimitive && content.isString -> UserMessageContent.Text(content.content)
            else ->
                UserMessageContent.Structured(
                    content.jsonArray.map { block ->
                        val jsonBlock = block.jsonObject
                        when (jsonBlock.string("type")) {
                            "text" -> TextContent(jsonBlock.string("text"))
                            "image" ->
                                ImageContent(
                                    data = jsonBlock.string("data"),
                                    mimeType = jsonBlock.string("mimeType"),
                                )
                            else -> error("Unsupported user content block type: ${jsonBlock.string("type")}")
                        }
                    },
                )
        }
    }

    private fun parityRootDir(): Path = Path.of(System.getProperty("parity.rootDir") ?: error("Missing parity.rootDir system property"))
}

private class FauxParityProvider(
    private val model: Model<String>,
    responses: JsonArray,
    private val tokenSize: Int,
) : ApiProvider {
    override val api: String = model.api
    private val pendingResponses: MutableList<AssistantMessage> =
        responses.mapIndexed { index, response -> response.jsonObject.toAssistantMessage(model, 1000L + index) }.toMutableList()

    override fun stream(
        model: Model<*>,
        context: Context,
        options: pi.ai.core.StreamOptions?,
    ): AssistantMessageEventStream = streamSimple(model, context, null)

    override fun streamSimple(
        model: Model<*>,
        context: Context,
        options: SimpleStreamOptions?,
    ): AssistantMessageEventStream {
        val stream = AssistantMessageEventStream()
        val response = pendingResponses.removeFirstOrNull() ?: createErrorMessage(model)
        emitStream(stream, response.withUsageEstimate(context), tokenSize)
        return stream
    }
}

private fun emitStream(
    stream: AssistantMessageEventStream,
    message: AssistantMessage,
    tokenSize: Int,
) {
    var partial = message.copy(content = mutableListOf(), usage = message.usage.copy(cost = message.usage.cost.copy()))
    stream.push(AssistantMessageEvent.Start(partial))

    message.content.forEachIndexed { index, block ->
        when (block) {
            is TextContent -> {
                partial = partial.copy(content = (partial.content + TextContent("")).toMutableList())
                stream.push(AssistantMessageEvent.TextStart(index, partial))
                splitStringByTokenSize(block.text, tokenSize).forEach { chunk ->
                    val existing = partial.content[index] as TextContent
                    val updated = existing.copy(text = existing.text + chunk)
                    val newContent = partial.content.toMutableList().also { it[index] = updated }
                    partial = partial.copy(content = newContent)
                    stream.push(AssistantMessageEvent.TextDelta(index, chunk, partial))
                }
                stream.push(AssistantMessageEvent.TextEnd(index, block.text, partial))
            }

            is ThinkingContent -> {
                partial = partial.copy(content = (partial.content + ThinkingContent("")).toMutableList())
                stream.push(AssistantMessageEvent.ThinkingStart(index, partial))
                splitStringByTokenSize(block.thinking, tokenSize).forEach { chunk ->
                    val existing = partial.content[index] as ThinkingContent
                    val updated = existing.copy(thinking = existing.thinking + chunk)
                    val newContent = partial.content.toMutableList().also { it[index] = updated }
                    partial = partial.copy(content = newContent)
                    stream.push(AssistantMessageEvent.ThinkingDelta(index, chunk, partial))
                }
                stream.push(AssistantMessageEvent.ThinkingEnd(index, block.thinking, partial))
            }

            is ToolCall -> {
                partial = partial.copy(content = (partial.content + ToolCall(block.id, block.name, buildJsonObject { })).toMutableList())
                stream.push(AssistantMessageEvent.ToolCallStart(index, partial))
                splitStringByTokenSize(block.arguments.toString(), tokenSize).forEach { chunk ->
                    stream.push(AssistantMessageEvent.ToolCallDelta(index, chunk, partial))
                }
                val newContent = partial.content.toMutableList().also { it[index] = block }
                partial = partial.copy(content = newContent)
                stream.push(AssistantMessageEvent.ToolCallEnd(index, block, partial))
            }
        }
    }

    if (message.stopReason == StopReason.ERROR || message.stopReason == StopReason.ABORTED) {
        stream.push(AssistantMessageEvent.Error(message.stopReason, message))
        stream.end(message)
    } else {
        stream.push(AssistantMessageEvent.Done(message.stopReason, message))
        stream.end(message)
    }
}

private fun createErrorMessage(model: Model<*>): AssistantMessage =
    AssistantMessage(
        content = mutableListOf(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = Usage(),
        stopReason = StopReason.ERROR,
        errorMessage = "No more faux responses queued",
        timestamp = 0L,
    )

private fun JsonObject.toAssistantMessage(
    model: Model<String>,
    timestamp: Long,
): AssistantMessage =
    AssistantMessage(
        content =
            this["content"]!!
                .jsonArray
                .map { block ->
                    val jsonBlock = block.jsonObject
                    when (jsonBlock.string("type")) {
                        "text" -> TextContent(jsonBlock.string("text"))
                        "thinking" -> ThinkingContent(jsonBlock.string("thinking"))
                        "toolCall" -> ToolCall(jsonBlock.string("id"), jsonBlock.string("name"), jsonBlock["arguments"]!!.jsonObject)
                        else -> error("Unsupported assistant content block type: ${jsonBlock.string("type")}")
                    }
                }.toMutableList(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = Usage(),
        stopReason = this["stopReason"]!!.jsonPrimitive.toStopReason(),
        errorMessage = optionalString("errorMessage"),
        timestamp = timestamp,
        responseId = optionalString("responseId"),
    )

private fun AssistantMessage.withUsageEstimate(context: Context): AssistantMessage {
    val promptText = serializeContext(context)
    val input = estimateTokens(promptText)
    val output = estimateTokens(assistantContentToText(content))
    return copy(
        usage =
            Usage(
                input = input,
                output = output,
                cacheRead = 0,
                cacheWrite = 0,
                totalTokens = input + output,
            ),
    )
}

private fun serializeContext(context: Context): String {
    val parts = mutableListOf<String>()
    if (context.systemPrompt != null) {
        parts += "system:${context.systemPrompt}"
    }
    context.messages.forEach { message ->
        parts += "${message.role}:${messageToText(message)}"
    }
    if (context.tools.isNotEmpty()) {
        parts += "tools:${context.tools}"
    }
    return parts.joinToString(separator = "\n\n")
}

private fun messageToText(message: Message): String =
    when (message) {
        is UserMessage ->
            when (val content = message.content) {
                is UserMessageContent.Text -> content.value
                is UserMessageContent.Structured ->
                    content.parts.joinToString(separator = "\n") { block ->
                        when (block) {
                            is TextContent -> block.text
                            is ImageContent -> "[image:${block.mimeType}:${block.data.length}]"
                            else -> error("Unsupported user content block")
                        }
                    }
            }

        is AssistantMessage -> assistantContentToText(message.content)
        is ToolResultMessage ->
            listOf(
                message.toolName,
                message.content.joinToString(separator = "\n") { block ->
                    when (block) {
                        is TextContent -> block.text
                        is ImageContent -> "[image:${block.mimeType}:${block.data.length}]"
                        else -> error("Unsupported tool result block")
                    }
                },
            ).joinToString(separator = "\n")
        else -> error("Unsupported message type: ${message::class.simpleName}")
    }

private fun assistantContentToText(content: List<AssistantContentBlock>): String =
    content.joinToString(separator = "\n") { block ->
        when (block) {
            is TextContent -> block.text
            is ThinkingContent -> block.thinking
            is ToolCall -> "${block.name}:${block.arguments}"
            else -> error("Unsupported assistant content block")
        }
    }

private fun estimateTokens(text: String): Int = kotlin.math.ceil(text.length / 4.0).toInt()

private fun splitStringByTokenSize(
    text: String,
    tokenSize: Int,
): List<String> {
    val size = tokenSize.coerceAtLeast(1) * 4
    if (text.isEmpty()) {
        return listOf("")
    }
    val chunks = mutableListOf<String>()
    var index = 0
    while (index < text.length) {
        chunks += text.substring(index, minOf(text.length, index + size))
        index += size
    }
    return chunks
}

private fun normalizeAssistantEventSequence(rawEvents: List<AssistantMessageEvent>): List<JsonElement> {
    var partial: JsonObject? = null
    return rawEvents.map { event ->
        val normalized = normalizeAssistantEvent(partial, event)
        partial = normalized.first
        normalized.second
    }
}

private fun normalizeAssistantEvent(
    partial: JsonObject?,
    event: AssistantMessageEvent,
): Pair<JsonObject?, JsonElement> =
    when (event) {
        is AssistantMessageEvent.Start -> {
            val next = baseAssistantMessage(event.partial)
            next to
                buildJsonObject {
                    put("type", "start")
                    put("partial", next)
                }
        }

        is AssistantMessageEvent.TextStart -> {
            val next =
                partial!!.appendContent(
                    buildJsonObject {
                        put("type", "text")
                        put("text", "")
                    },
                )
            next to
                buildJsonObject {
                    put("type", "text_start")
                    put("contentIndex", event.contentIndex)
                    put("partial", next)
                }
        }

        is AssistantMessageEvent.TextDelta -> {
            val next = partial!!.appendText(event.contentIndex, event.delta)
            next to
                buildJsonObject {
                    put("type", "text_delta")
                    put("contentIndex", event.contentIndex)
                    put("delta", event.delta)
                    put("partial", next)
                }
        }

        is AssistantMessageEvent.TextEnd ->
            partial!! to
                buildJsonObject {
                    put("type", "text_end")
                    put("contentIndex", event.contentIndex)
                    put("content", event.content)
                    put("partial", partial)
                }

        is AssistantMessageEvent.ThinkingStart -> {
            val next =
                partial!!.appendContent(
                    buildJsonObject {
                        put("type", "thinking")
                        put("thinking", "")
                    },
                )
            next to
                buildJsonObject {
                    put("type", "thinking_start")
                    put("contentIndex", event.contentIndex)
                    put("partial", next)
                }
        }

        is AssistantMessageEvent.ThinkingDelta -> {
            val next = partial!!.appendThinking(event.contentIndex, event.delta)
            next to
                buildJsonObject {
                    put("type", "thinking_delta")
                    put("contentIndex", event.contentIndex)
                    put("delta", event.delta)
                    put("partial", next)
                }
        }

        is AssistantMessageEvent.ThinkingEnd ->
            partial!! to
                buildJsonObject {
                    put("type", "thinking_end")
                    put("contentIndex", event.contentIndex)
                    put("content", event.content)
                    put("partial", partial)
                }

        is AssistantMessageEvent.ToolCallStart -> {
            val block = event.partial.content[event.contentIndex] as ToolCall
            val next =
                partial!!.appendContent(
                    buildJsonObject {
                        put("type", "toolCall")
                        put("id", block.id)
                        put("name", block.name)
                        put("arguments", buildJsonObject { })
                    },
                )
            next to
                buildJsonObject {
                    put("type", "toolcall_start")
                    put("contentIndex", event.contentIndex)
                    put("partial", next)
                }
        }

        is AssistantMessageEvent.ToolCallDelta ->
            partial!! to
                buildJsonObject {
                    put("type", "toolcall_delta")
                    put("contentIndex", event.contentIndex)
                    put("delta", event.delta)
                    put("partial", partial)
                }

        is AssistantMessageEvent.ToolCallEnd -> {
            val next = partial!!.replaceContent(event.contentIndex, normalizeContentBlock(event.toolCall).jsonObject)
            next to
                buildJsonObject {
                    put("type", "toolcall_end")
                    put("contentIndex", event.contentIndex)
                    put("toolCall", normalizeContentBlock(event.toolCall))
                    put("partial", next)
                }
        }

        is AssistantMessageEvent.Done ->
            partial to
                buildJsonObject {
                    put("type", "done")
                    put("reason", event.reason.normalizedStopReason())
                    put("message", normalizeMessage(event.message))
                }

        is AssistantMessageEvent.Error ->
            partial to
                buildJsonObject {
                    put("type", "error")
                    put("reason", event.reason.normalizedStopReason())
                    put("error", normalizeMessage(event.error))
                }
    }

private fun baseAssistantMessage(message: AssistantMessage): JsonObject {
    val normalized = normalizeMessage(message).jsonObject
    return buildJsonObject {
        normalized.forEach { (key, value) ->
            if (key != "content") {
                put(key, value)
            }
        }
        put("content", buildJsonArray { })
    }
}

private fun JsonObject.appendContent(block: JsonObject): JsonObject =
    replaceContent(
        content = this["content"]!!.jsonArray + block,
    )

private fun JsonObject.appendText(
    index: Int,
    delta: String,
): JsonObject {
    val content = this["content"]!!.jsonArray.toMutableList()
    val current = content[index].jsonObject
    content[index] =
        buildJsonObject {
            current.forEach { (key, value) -> put(key, value) }
            put("text", current.string("text") + delta)
        }
    return replaceContent(content = content)
}

private fun JsonObject.appendThinking(
    index: Int,
    delta: String,
): JsonObject {
    val content = this["content"]!!.jsonArray.toMutableList()
    val current = content[index].jsonObject
    content[index] =
        buildJsonObject {
            current.forEach { (key, value) -> put(key, value) }
            put("thinking", current.string("thinking") + delta)
        }
    return replaceContent(content = content)
}

private fun JsonObject.replaceContent(
    index: Int? = null,
    block: JsonObject? = null,
    content: List<JsonElement>? = null,
): JsonObject {
    val updatedContent =
        content?.toMutableList()
            ?: this["content"]!!.jsonArray.toMutableList().also { items ->
                if (index != null && block != null) {
                    items[index] = block
                }
            }
    return buildJsonObject {
        this@replaceContent.forEach { (key, value) ->
            if (key != "content") {
                put(key, value)
            }
        }
        put("content", JsonArray(updatedContent))
    }
}

private fun normalizeMessage(message: Message): JsonElement =
    when (message) {
        is UserMessage ->
            buildJsonObject {
                put("role", "user")
                when (val content = message.content) {
                    is UserMessageContent.Text -> put("content", content.value)
                    is UserMessageContent.Structured -> {
                        put(
                            "content",
                            buildJsonArray {
                                content.parts.forEach { part -> add(normalizeContentBlock(part)) }
                            },
                        )
                    }
                }
            }

        is ToolResultMessage ->
            buildJsonObject {
                put("role", "toolResult")
                put("toolCallId", message.toolCallId)
                put("toolName", message.toolName)
                put("isError", message.isError)
                put(
                    "content",
                    buildJsonArray {
                        message.content.forEach { block -> add(normalizeContentBlock(block)) }
                    },
                )
                message.details?.let { put("details", it) }
            }

        is AssistantMessage ->
            buildJsonObject {
                put("role", "assistant")
                put("stopReason", message.stopReason.normalizedStopReason())
                message.errorMessage?.let { put("errorMessage", it) }
                if (message is AssistantMessage && message.responseId != null) {
                    put("responseId", message.responseId)
                }
                put(
                    "content",
                    buildJsonArray {
                        message.content.forEach { block -> add(normalizeContentBlock(block)) }
                    },
                )
                put("api", message.api)
                put("provider", message.provider)
                put("model", message.model)
                put(
                    "usage",
                    buildJsonObject {
                        put("input", message.usage.input)
                        put("output", message.usage.output)
                        put("cacheRead", message.usage.cacheRead)
                        put("cacheWrite", message.usage.cacheWrite)
                        put("totalTokens", message.usage.totalTokens)
                    },
                )
            }
        else -> error("Unsupported message type: ${message::class.simpleName}")
    }

private fun normalizeContentBlock(block: Any): JsonElement =
    when (block) {
        is TextContent ->
            buildJsonObject {
                put("type", "text")
                put("text", block.text)
            }

        is ThinkingContent ->
            buildJsonObject {
                put("type", "thinking")
                put("thinking", block.thinking)
            }

        is ToolCall ->
            buildJsonObject {
                put("type", "toolCall")
                put("id", block.id)
                put("name", block.name)
                put("arguments", block.arguments)
            }

        is ImageContent ->
            buildJsonObject {
                put("type", "image")
                put("data", block.data)
                put("mimeType", block.mimeType)
            }

        else -> error("Unsupported content block: ${block::class.simpleName}")
    }

private fun JsonPrimitive.toStopReason(): StopReason =
    when (content) {
        "stop" -> StopReason.STOP
        "length" -> StopReason.LENGTH
        "toolUse" -> StopReason.TOOL_USE
        "error" -> StopReason.ERROR
        "aborted" -> StopReason.ABORTED
        else -> error("Unsupported stop reason: $content")
    }

private fun StopReason.normalizedStopReason(): String =
    when (this) {
        StopReason.STOP -> "stop"
        StopReason.LENGTH -> "length"
        StopReason.TOOL_USE -> "toolUse"
        StopReason.ERROR -> "error"
        StopReason.ABORTED -> "aborted"
    }

private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

private fun JsonObject.int(key: String): Int = this[key]!!.jsonPrimitive.int

private fun JsonObject.boolean(key: String): Boolean = this[key]!!.jsonPrimitive.content.toBooleanStrict()

private fun JsonObject.optionalString(key: String): String? = this[key]?.jsonPrimitive?.content

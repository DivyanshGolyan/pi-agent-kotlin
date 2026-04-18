package pi.agent.core.parity

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
import pi.agent.core.Agent
import pi.agent.core.AgentContext
import pi.agent.core.AgentEvent
import pi.agent.core.AgentLoopConfig
import pi.agent.core.AgentMessage
import pi.agent.core.AgentOptions
import pi.agent.core.AgentThinkingLevel
import pi.agent.core.AgentTool
import pi.agent.core.AgentToolResult
import pi.agent.core.AgentToolUpdateCallback
import pi.agent.core.InitialAgentState
import pi.agent.core.StreamFn
import pi.agent.core.agentLoop
import pi.ai.core.AbortSignal
import pi.ai.core.AssistantContentBlock
import pi.ai.core.AssistantMessage
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.AssistantMessageEventStream
import pi.ai.core.ImageContent
import pi.ai.core.InputModality
import pi.ai.core.Message
import pi.ai.core.Model
import pi.ai.core.ModelCost
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.ThinkingContent
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultMessage
import pi.ai.core.Usage
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

@Tag("parity")
class AgentParityTest {
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
                            if (scenario.string("suite") != "pi-agent-core") {
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

    private fun runScenario(scenario: JsonObject): JsonElement =
        when (scenario.string("kind")) {
            "agent_loop_scripted" -> runAgentLoopScenario(scenario)
            "agent_prompt_scripted" -> runAgentScenario(scenario, continueRun = false)
            "agent_continue_scripted" -> runAgentScenario(scenario, continueRun = true)
            else -> error("Unsupported pi-agent parity scenario kind: ${scenario.string("kind")}")
        }

    private fun runAgentLoopScenario(scenario: JsonObject): JsonElement {
        val model = scenario.modelSpec()
        val streamFn = createScriptedStreamFn(model, scenario["responses"]!!.jsonArray, scenario.tokenSize())
        val prompts =
            scenario["prompts"]!!.jsonArray.mapIndexed { index, element ->
                createUserMessage(
                    element.jsonObject.string("content"),
                    index + 1L,
                )
            }
        val context =
            AgentContext(
                systemPrompt = scenario["context"]!!.jsonObject.string("systemPrompt"),
                messages = mutableListOf(),
                tools = emptyList(),
            )

        val stream =
            agentLoop(
                prompts = prompts,
                context = context,
                config =
                    AgentLoopConfig(
                        model = model,
                        convertToLlm = ::identityConverter,
                    ),
                streamFn = streamFn,
            )

        val rawEvents = runBlocking { stream.asFlow().toList() }
        val messages = runBlocking { stream.result() }

        return buildJsonObject {
            put("scenarioId", scenario.string("id"))
            put("suite", scenario.string("suite"))
            put("events", JsonArray(normalizeAgentEventSequence(rawEvents)))
            put(
                "messages",
                buildJsonArray {
                    messages.forEach { message ->
                        add(normalizeMessage(message))
                    }
                },
            )
        }
    }

    private fun runAgentScenario(
        scenario: JsonObject,
        continueRun: Boolean,
    ): JsonElement {
        val model = scenario.modelSpec()
        val streamFn = createScriptedStreamFn(model, scenario["responses"]!!.jsonArray, scenario.tokenSize())
        val tools =
            scenario["tools"]
                ?.jsonArray
                ?.map { toolId ->
                    when (toolId.jsonPrimitive.content) {
                        "calculate" -> createCalculateTool()
                        else -> error("Unsupported tool id: ${toolId.jsonPrimitive.content}")
                    }
                }.orEmpty()

        val initialStateJson = scenario["initialState"]!!.jsonObject
        val initialMessages =
            initialStateJson["messages"]
                ?.jsonArray
                ?.mapIndexed { index, message -> materializeMessage(message.jsonObject, model, 50L + index) }
                .orEmpty()

        val agent =
            Agent(
                AgentOptions(
                    initialState =
                        InitialAgentState(
                            systemPrompt = initialStateJson.optionalString("systemPrompt").orEmpty(),
                            model = model,
                            thinkingLevel = initialStateJson["thinkingLevel"]?.toThinkingLevel() ?: AgentThinkingLevel.OFF,
                            tools = tools,
                            messages = initialMessages,
                        ),
                    streamFn = streamFn,
                ),
            )

        val rawEvents = mutableListOf<AgentEvent>()
        val pendingToolCallsTimeline = mutableListOf<JsonElement>()
        agent.subscribe { event, _ ->
            rawEvents += event
            if (event is AgentEvent.ToolExecutionStart || event is AgentEvent.ToolExecutionEnd) {
                pendingToolCallsTimeline +=
                    buildJsonObject {
                        put(
                            "event",
                            when (event) {
                                is AgentEvent.ToolExecutionStart -> "tool_execution_start"
                                is AgentEvent.ToolExecutionEnd -> "tool_execution_end"
                                else -> error("Unsupported event")
                            },
                        )
                        put(
                            "ids",
                            buildJsonArray {
                                agent.state.pendingToolCalls
                                    .sorted()
                                    .forEach { id -> add(JsonPrimitive(id)) }
                            },
                        )
                    }
            }
        }

        scenario["steeringMessages"]
            ?.jsonArray
            ?.forEachIndexed { index, message ->
                agent.steer(createUserMessage(message.jsonObject.string("content"), 70L + index))
            }

        runBlocking {
            if (continueRun) {
                agent.`continue`()
            } else {
                agent.prompt(scenario["prompt"]!!.jsonObject.string("text"))
            }
        }

        return buildJsonObject {
            put("scenarioId", scenario.string("id"))
            put("suite", scenario.string("suite"))
            put("events", JsonArray(normalizeAgentEventSequence(rawEvents)))
            put("pendingToolCallsTimeline", JsonArray(pendingToolCallsTimeline))
            put(
                "state",
                buildJsonObject {
                    put(
                        "messages",
                        buildJsonArray {
                            agent.state.messages.forEach { message ->
                                add(normalizeMessage(message))
                            }
                        },
                    )
                    put("isStreaming", agent.state.isStreaming)
                    put(
                        "pendingToolCalls",
                        buildJsonArray {
                            agent.state.pendingToolCalls
                                .sorted()
                                .forEach { id -> add(JsonPrimitive(id)) }
                        },
                    )
                    put(
                        "errorMessage",
                        agent.state.errorMessage?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull,
                    )
                },
            )
        }
    }

    private fun parityRootDir(): Path = Path.of(System.getProperty("parity.rootDir") ?: error("Missing parity.rootDir system property"))
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

private fun JsonObject.tokenSize(): Int =
    this["stream"]!!
        .jsonObject["tokenSize"]!!
        .jsonObject["min"]!!
        .jsonPrimitive.int

private fun JsonElement.toThinkingLevel(): AgentThinkingLevel =
    when (jsonPrimitive.content) {
        "off" -> AgentThinkingLevel.OFF
        "minimal" -> AgentThinkingLevel.MINIMAL
        "low" -> AgentThinkingLevel.LOW
        "medium" -> AgentThinkingLevel.MEDIUM
        "high" -> AgentThinkingLevel.HIGH
        "xhigh" -> AgentThinkingLevel.XHIGH
        else -> error("Unsupported thinking level: ${jsonPrimitive.content}")
    }

private fun createUserMessage(
    text: String,
    timestamp: Long,
): UserMessage =
    UserMessage(
        content = UserMessageContent.Text(text),
        timestamp = timestamp,
    )

private fun materializeMessage(
    message: JsonObject,
    model: Model<String>,
    timestamp: Long,
): AgentMessage =
    when (message.string("role")) {
        "user" -> createUserMessage(message.string("content"), timestamp)
        "assistant" ->
            AssistantMessage(
                content = message["content"]!!.jsonArray.map { materializeAssistantBlock(it.jsonObject) }.toMutableList(),
                api = model.api,
                provider = model.provider,
                model = model.id,
                usage = Usage(),
                stopReason = message["stopReason"]!!.jsonPrimitive.toStopReason(),
                errorMessage = message.optionalString("errorMessage"),
                timestamp = timestamp,
                responseId = message.optionalString("responseId"),
            )
        else -> error("Unsupported message role: ${message.string("role")}")
    }

private fun JsonObject.toAssistantMessage(
    model: Model<String>,
    timestamp: Long,
): AssistantMessage =
    AssistantMessage(
        content = this["content"]!!.jsonArray.map { materializeAssistantBlock(it.jsonObject) }.toMutableList(),
        api = model.api,
        provider = model.provider,
        model = model.id,
        usage = Usage(),
        stopReason = this["stopReason"]!!.jsonPrimitive.toStopReason(),
        errorMessage = optionalString("errorMessage"),
        timestamp = timestamp,
        responseId = optionalString("responseId"),
    )

private fun materializeAssistantBlock(block: JsonObject): AssistantContentBlock =
    when (block.string("type")) {
        "text" -> TextContent(block.string("text"))
        "thinking" -> ThinkingContent(block.string("thinking"))
        "toolCall" -> ToolCall(block.string("id"), block.string("name"), block["arguments"]!!.jsonObject)
        else -> error("Unsupported assistant block type: ${block.string("type")}")
    }

private fun createScriptedStreamFn(
    model: Model<String>,
    responses: JsonArray,
    tokenSize: Int,
): StreamFn {
    val queue = responses.mapIndexed { index, response -> response.jsonObject.toAssistantMessage(model, 1000L + index) }.toMutableList()

    return { _, _, _ ->
        val stream = AssistantMessageEventStream()
        val message = queue.removeFirst()
        emitScriptedAssistantEvents(stream, message, tokenSize)
        stream
    }
}

private fun emitScriptedAssistantEvents(
    stream: AssistantMessageEventStream,
    message: AssistantMessage,
    tokenSize: Int,
) {
    var partial = message.copy(content = mutableListOf(), usage = Usage())
    stream.push(AssistantMessageEvent.Start(partial))

    message.content.forEachIndexed { index, block ->
        when (block) {
            is TextContent -> {
                partial = partial.copy(content = (partial.content + TextContent("")).toMutableList())
                stream.push(AssistantMessageEvent.TextStart(index, partial))
                splitStringByTokenSize(block.text, tokenSize).forEach { chunk ->
                    val current = partial.content[index] as TextContent
                    val next = current.copy(text = current.text + chunk)
                    partial = partial.copy(content = partial.content.toMutableList().also { it[index] = next })
                    stream.push(AssistantMessageEvent.TextDelta(index, chunk, partial))
                }
                stream.push(AssistantMessageEvent.TextEnd(index, block.text, partial))
            }

            is ThinkingContent -> {
                partial = partial.copy(content = (partial.content + ThinkingContent("")).toMutableList())
                stream.push(AssistantMessageEvent.ThinkingStart(index, partial))
                splitStringByTokenSize(block.thinking, tokenSize).forEach { chunk ->
                    val current = partial.content[index] as ThinkingContent
                    val next = current.copy(thinking = current.thinking + chunk)
                    partial = partial.copy(content = partial.content.toMutableList().also { it[index] = next })
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
                partial = partial.copy(content = partial.content.toMutableList().also { it[index] = block })
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

private fun createCalculateTool(): AgentTool<String> =
    object : AgentTool<String> {
        override val name: String = "calculate"
        override val label: String = "Calculator"
        override val description: String = "Evaluate mathematical expressions"
        override val parameters: JsonObject = buildJsonObject { put("type", "object") }

        override fun validateArguments(arguments: JsonObject): String = arguments["expression"]!!.jsonPrimitive.content

        override suspend fun execute(
            toolCallId: String,
            params: String,
            signal: AbortSignal?,
            onUpdate: AgentToolUpdateCallback<JsonElement>?,
        ): AgentToolResult<JsonElement> {
            val match =
                Regex("""^(\d+)\s*([+\-*/])\s*(\d+)$""").matchEntire(params.trim())
                    ?: error("Unsupported expression: $params")
            val left = match.groupValues[1].toLong()
            val right = match.groupValues[3].toLong()
            val result =
                when (match.groupValues[2]) {
                    "*" -> left * right
                    "+" -> left + right
                    "-" -> left - right
                    "/" -> left / right
                    else -> error("Unsupported operator")
                }
            return AgentToolResult(
                content = listOf(TextContent("${params.trim()} = $result")),
                details = kotlinx.serialization.json.JsonNull,
            )
        }
    }

private fun identityConverter(messages: List<AgentMessage>): List<Message> =
    messages.filter { message -> message is UserMessage || message is AssistantMessage || message is ToolResultMessage }

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

private fun normalizeAgentEventSequence(rawEvents: List<AgentEvent>): List<JsonElement> {
    var assistantPartial: JsonObject? = null
    return rawEvents.map { event ->
        when (event) {
            AgentEvent.AgentStart -> buildJsonObject { put("type", "agent_start") }
            AgentEvent.TurnStart -> buildJsonObject { put("type", "turn_start") }
            is AgentEvent.AgentEnd ->
                buildJsonObject {
                    put("type", "agent_end")
                    put(
                        "messages",
                        buildJsonArray {
                            event.messages.forEach { message -> add(normalizeMessage(message)) }
                        },
                    )
                }
            is AgentEvent.TurnEnd ->
                buildJsonObject {
                    put("type", "turn_end")
                    put("message", normalizeMessage(event.message))
                    put(
                        "toolResults",
                        buildJsonArray {
                            event.toolResults.forEach { result -> add(normalizeMessage(result)) }
                        },
                    )
                }
            is AgentEvent.MessageStart -> {
                if (event.message is AssistantMessage) {
                    assistantPartial = baseAssistantMessage(event.message)
                    buildJsonObject {
                        put("type", "message_start")
                        put("message", assistantPartial!!)
                    }
                } else {
                    buildJsonObject {
                        put("type", "message_start")
                        put("message", normalizeMessage(event.message))
                    }
                }
            }
            is AgentEvent.MessageUpdate -> {
                val normalized = normalizeAssistantEvent(assistantPartial, event.assistantMessageEvent)
                assistantPartial = normalized.first
                buildJsonObject {
                    put("type", "message_update")
                    put("message", assistantPartial!!)
                    put("assistantMessageEvent", normalized.second)
                }
            }
            is AgentEvent.MessageEnd ->
                buildJsonObject {
                    put("type", "message_end")
                    put("message", normalizeMessage(event.message))
                }
            is AgentEvent.ToolExecutionStart ->
                buildJsonObject {
                    put("type", "tool_execution_start")
                    put("toolCallId", event.toolCallId)
                    put("toolName", event.toolName)
                    put("args", event.args)
                }
            is AgentEvent.ToolExecutionUpdate ->
                buildJsonObject {
                    put("type", "tool_execution_update")
                    put("toolCallId", event.toolCallId)
                    put("toolName", event.toolName)
                    put("args", event.args)
                    put(
                        "partialResult",
                        buildJsonObject {
                            put(
                                "content",
                                buildJsonArray {
                                    event.partialResult.content.forEach { block -> add(normalizeContentBlock(block)) }
                                },
                            )
                            normalizeDetails(event.partialResult.details)?.let { put("details", it) }
                        },
                    )
                }
            is AgentEvent.ToolExecutionEnd ->
                buildJsonObject {
                    put("type", "tool_execution_end")
                    put("toolCallId", event.toolCallId)
                    put("toolName", event.toolName)
                    put(
                        "result",
                        buildJsonObject {
                            put(
                                "content",
                                buildJsonArray {
                                    event.result.content.forEach { block -> add(normalizeContentBlock(block)) }
                                },
                            )
                            normalizeDetails(event.result.details)?.let { put("details", it) }
                        },
                    )
                    put("isError", event.isError)
                }
        }
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
            val next = partial!!.replaceContent(index = event.contentIndex, block = normalizeContentBlock(event.toolCall).jsonObject)
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

private fun JsonObject.appendContent(block: JsonObject): JsonObject = replaceContent(content = this["content"]!!.jsonArray + block)

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
        is AssistantMessage ->
            buildJsonObject {
                put("role", "assistant")
                put("stopReason", message.stopReason.normalizedStopReason())
                message.errorMessage?.let { put("errorMessage", it) }
                put(
                    "content",
                    buildJsonArray {
                        message.content.forEach { block -> add(normalizeContentBlock(block)) }
                    },
                )
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
                normalizeDetails(message.details)?.let { put("details", it) }
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

private fun normalizeDetails(details: Any?): JsonElement? =
    when (details) {
        null -> null
        is kotlinx.serialization.json.JsonNull -> null
        is JsonElement -> details
        is String -> JsonPrimitive(details)
        is Number -> JsonPrimitive(details)
        is Boolean -> JsonPrimitive(details)
        else -> JsonPrimitive(details.toString())
    }

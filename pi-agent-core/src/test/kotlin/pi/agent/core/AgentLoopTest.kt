package pi.agent.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pi.ai.core.AssistantMessage
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.AssistantMessageEventStream
import pi.ai.core.Message
import pi.ai.core.Model
import pi.ai.core.ModelCost
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultMessage
import pi.ai.core.Usage
import pi.ai.core.UsageCost
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent

class AgentLoopTest {
    @Test
    fun `agentLoop emits expected lifecycle events`() =
        runTest {
            val context =
                AgentContext(
                    systemPrompt = "You are helpful.",
                    messages = mutableListOf(),
                    tools = emptyList(),
                )
            val userPrompt = createUserMessage("Hello")
            val config =
                AgentLoopConfig(
                    model = createModel(),
                    convertToLlm = ::identityConverter,
                )

            val stream =
                agentLoop(listOf(userPrompt), context, config, streamFn = { _, _, _ ->
                    AssistantMessageEventStream().also { eventStream ->
                        eventStream.push(
                            AssistantMessageEvent.Done(
                                reason = StopReason.STOP,
                                message = createAssistantMessage(listOf(TextContent("Hi there!"))),
                            ),
                        )
                    }
                })

            val events = stream.asFlow().toList()
            val messages = stream.result()

            assertEquals(2, messages.size)
            assertTrue(messages[0] is UserMessage)
            assertTrue(messages[1] is AssistantMessage)
            assertEquals(
                listOf(
                    AgentEvent.AgentStart,
                    AgentEvent.TurnStart,
                    AgentEvent.MessageStart(userPrompt),
                    AgentEvent.MessageEnd(userPrompt),
                    AgentEvent.MessageStart(messages[1].copyForEvent()),
                    AgentEvent.MessageEnd(messages[1]),
                    AgentEvent.TurnEnd(messages[1], emptyList()),
                    AgentEvent.AgentEnd(messages),
                ),
                events,
            )
        }

    @Test
    fun `transformContext is applied before convertToLlm`() =
        runTest {
            val context =
                AgentContext(
                    systemPrompt = "You are helpful.",
                    messages =
                        mutableListOf(
                            createUserMessage("old message 1"),
                            createAssistantMessage(listOf(TextContent("old response 1"))),
                            createUserMessage("old message 2"),
                            createAssistantMessage(listOf(TextContent("old response 2"))),
                        ),
                    tools = emptyList(),
                )
            val userPrompt = createUserMessage("new message")

            var transformedMessages: List<AgentMessage> = emptyList()
            var convertedMessages: List<Message> = emptyList()

            val stream =
                agentLoop(
                    prompts = listOf(userPrompt),
                    context = context,
                    config =
                        AgentLoopConfig(
                            model = createModel(),
                            transformContext = { messages, _ ->
                                messages.takeLast(2).also { transformedMessages = it }
                            },
                            convertToLlm = { messages ->
                                identityConverter(messages).also { convertedMessages = it }
                            },
                        ),
                    streamFn = { _, _, _ ->
                        AssistantMessageEventStream().also { eventStream ->
                            eventStream.push(
                                AssistantMessageEvent.Done(
                                    reason = StopReason.STOP,
                                    message = createAssistantMessage(listOf(TextContent("Response"))),
                                ),
                            )
                        }
                    },
                )

            stream.asFlow().toList()

            assertEquals(2, transformedMessages.size)
            assertEquals(2, convertedMessages.size)
        }

    @Test
    fun `tool calls produce tool results in source order`() =
        runTest {
            val executed = mutableListOf<String>()
            val tool =
                object : AgentTool<String> {
                    override val name: String = "echo"
                    override val label: String = "Echo"
                    override val description: String = "Echo tool"
                    override val parameters: JsonObject = buildJsonObject { put("type", "object") }

                    override fun validateArguments(arguments: JsonObject): String = arguments["value"]!!.toString().trim('"')

                    override suspend fun execute(
                        toolCallId: String,
                        params: String,
                        signal: pi.ai.core.AbortSignal?,
                        onUpdate: AgentToolUpdateCallback<kotlinx.serialization.json.JsonElement>?,
                    ): AgentToolResult<kotlinx.serialization.json.JsonElement> {
                        executed += "$toolCallId:$params"
                        return AgentToolResult(
                            content = listOf(TextContent("echoed: $params")),
                            details = buildJsonObject { put("value", params) },
                        )
                    }
                }

            val context = AgentContext(systemPrompt = "", messages = mutableListOf(), tools = listOf(tool))
            val assistantWithTools =
                createAssistantMessage(
                    listOf(
                        ToolCall("tool-1", "echo", buildJsonObject { put("value", "first") }),
                        ToolCall("tool-2", "echo", buildJsonObject { put("value", "second") }),
                    ),
                    stopReason = StopReason.TOOL_USE,
                )
            val finalAssistant = createAssistantMessage(listOf(TextContent("done")))

            var callCount = 0
            val stream =
                agentLoop(
                    prompts = listOf(createUserMessage("run tools")),
                    context = context,
                    config = AgentLoopConfig(model = createModel(), convertToLlm = ::identityConverter),
                    streamFn = { _, _, _ ->
                        callCount += 1
                        AssistantMessageEventStream().also { eventStream ->
                            eventStream.push(
                                AssistantMessageEvent.Done(
                                    reason = if (callCount == 1) StopReason.TOOL_USE else StopReason.STOP,
                                    message = if (callCount == 1) assistantWithTools else finalAssistant,
                                ),
                            )
                        }
                    },
                )

            stream.asFlow().toList()
            val messages = stream.result()

            val toolResults = messages.filterIsInstance<ToolResultMessage>()
            assertEquals(listOf("tool-1:first", "tool-2:second"), executed)
            assertEquals(listOf("tool-1", "tool-2"), toolResults.map { it.toolCallId })
        }

    @Test
    fun `terminal tool result ends loop without another assistant turn`() =
        runTest {
            val tool =
                object : AgentTool<String> {
                    override val name: String = "finish"
                    override val label: String = "Finish"
                    override val description: String = "Finish tool"
                    override val parameters: JsonObject = buildJsonObject { put("type", "object") }

                    override fun validateArguments(arguments: JsonObject): String = "done"

                    override suspend fun execute(
                        toolCallId: String,
                        params: String,
                        signal: pi.ai.core.AbortSignal?,
                        onUpdate: AgentToolUpdateCallback<kotlinx.serialization.json.JsonElement>?,
                    ): AgentToolResult<kotlinx.serialization.json.JsonElement> =
                        AgentToolResult(
                            content = listOf(TextContent("finished")),
                            details = buildJsonObject { put("status", params) },
                            terminal = true,
                        )
                }

            val context = AgentContext(systemPrompt = "", messages = mutableListOf(), tools = listOf(tool))
            val assistantWithTerminalTool =
                createAssistantMessage(
                    listOf(ToolCall("tool-1", "finish", buildJsonObject {})),
                    stopReason = StopReason.TOOL_USE,
                )
            val unexpectedAssistant = createAssistantMessage(listOf(TextContent("should not be called")))

            var callCount = 0
            val stream =
                agentLoop(
                    prompts = listOf(createUserMessage("finish")),
                    context = context,
                    config = AgentLoopConfig(model = createModel(), convertToLlm = ::identityConverter),
                    streamFn = { _, _, _ ->
                        callCount += 1
                        AssistantMessageEventStream().also { eventStream ->
                            eventStream.push(
                                AssistantMessageEvent.Done(
                                    reason = if (callCount == 1) StopReason.TOOL_USE else StopReason.STOP,
                                    message = if (callCount == 1) assistantWithTerminalTool else unexpectedAssistant,
                                ),
                            )
                        }
                    },
                )

            stream.asFlow().toList()
            val messages = stream.result()

            assertEquals(1, callCount)
            assertEquals(3, messages.size)
            assertTrue(messages[0] is UserMessage)
            assertTrue(messages[1] is AssistantMessage)
            assertTrue(messages[2] is ToolResultMessage)
        }
}

private fun identityConverter(messages: List<AgentMessage>): List<Message> =
    messages.filter { it is UserMessage || it is AssistantMessage || it is ToolResultMessage }

private fun createModel(): Model<String> =
    Model(
        id = "mock",
        name = "mock",
        api = "anthropic-messages",
        provider = "anthropic",
        baseUrl = "https://example.invalid",
        reasoning = true,
        input = setOf(pi.ai.core.InputModality.TEXT),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 8192,
        maxTokens = 2048,
    )

private fun createAssistantMessage(
    content: List<pi.ai.core.AssistantContentBlock>,
    stopReason: StopReason = StopReason.STOP,
): AssistantMessage =
    AssistantMessage(
        content = content.toMutableList(),
        api = "anthropic-messages",
        provider = "anthropic",
        model = "mock",
        usage = Usage(0, 0, 0, 0, 0, UsageCost(0.0, 0.0, 0.0, 0.0, 0.0)),
        stopReason = stopReason,
        timestamp = System.currentTimeMillis(),
    )

private fun createUserMessage(text: String): UserMessage =
    UserMessage(
        content = UserMessageContent.Text(text),
        timestamp = System.currentTimeMillis(),
    )

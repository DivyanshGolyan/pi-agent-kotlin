package pi.ai.core.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pi.ai.core.ANTHROPIC_MESSAGES_API
import pi.ai.core.ANTHROPIC_PROVIDER
import pi.ai.core.AssistantMessage
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.AssistantMessageEventStream
import pi.ai.core.Context
import pi.ai.core.ImageContent
import pi.ai.core.InputModality
import pi.ai.core.Model
import pi.ai.core.ModelCompat
import pi.ai.core.ModelCost
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.ThinkingContent
import pi.ai.core.Tool
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultMessage
import pi.ai.core.Usage
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent

class AnthropicProviderTest {
    @Test
    fun `buildParams disables thinking when reasoning is off`() {
        val payload =
            buildParams(
                model = createModel(),
                context = createSimpleContext(),
                options =
                    AnthropicOptions(
                        apiKey = "test-key",
                        thinkingEnabled = false,
                    ),
            )

        assertEquals("claude-sonnet-4-5", payload["model"]!!.jsonPrimitive.content)
        assertEquals("disabled", payload["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(
            "You are helpful.",
            payload["system"]!!
                .jsonArray
                .first()
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `buildParams enables reasoning budgets for classic thinking models`() {
        val payload =
            buildParams(
                model = createModel(),
                context = createSimpleContext(),
                options =
                    AnthropicOptions(
                        apiKey = "test-key",
                        thinkingEnabled = true,
                        thinkingBudgetTokens = 8192,
                    ),
            )

        val thinking = payload["thinking"]!!.jsonObject
        assertEquals("enabled", thinking["type"]!!.jsonPrimitive.content)
        assertEquals("8192", thinking["budget_tokens"]!!.jsonPrimitive.content)
    }

    @Test
    fun `buildParams applies shared message transform and eager tool streaming`() {
        val payload =
            buildParams(
                model = createModel().copy(input = setOf(InputModality.TEXT)),
                context =
                    Context(
                        messages =
                            listOf(
                                UserMessage(
                                    UserMessageContent.Structured(listOf(ImageContent("abc", "image/png"))),
                                    1L,
                                ),
                                AssistantMessage(
                                    content = mutableListOf(ToolCall("call.1", "echo", buildJsonObject { put("value", "x") })),
                                    api = "openai-codex-responses",
                                    provider = "openai-codex",
                                    model = "gpt-5.4-mini",
                                    usage = Usage(),
                                    stopReason = StopReason.TOOL_USE,
                                    timestamp = 2L,
                                ),
                                UserMessage(UserMessageContent.Text("continue"), 3L),
                            ),
                        tools = listOf(EchoTool),
                    ),
                options = AnthropicOptions(apiKey = "test-key"),
            )

        val messages = payload["messages"]!!.jsonArray
        assertEquals(
            NON_VISION_USER_IMAGE_PLACEHOLDER,
            messages[0]
                .jsonObject["content"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "call_1",
            messages[1]
                .jsonObject["content"]!!
                .jsonArray[0]
                .jsonObject["id"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "No result provided",
            messages[2]
                .jsonObject["content"]!!
                .jsonArray[0]
                .jsonObject["content"]!!
                .jsonPrimitive.content,
        )
        assertEquals(
            "true",
            payload["tools"]!!
                .jsonArray[0]
                .jsonObject["eager_input_streaming"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `anthropic beta header defaults to interleaved thinking except adaptive models`() {
        val context = createSimpleContext().copy(tools = listOf(EchoTool))

        assertEquals(
            "interleaved-thinking-2025-05-14",
            buildAnthropicBetaHeader(createModel(), context.copy(tools = emptyList()), AnthropicOptions(apiKey = "test-key")),
        )
        assertEquals(
            "interleaved-thinking-2025-05-14",
            buildAnthropicBetaHeader(createModel(), context, AnthropicOptions(apiKey = "test-key")),
        )
        assertEquals(
            null,
            buildAnthropicBetaHeader(createModel(), context, AnthropicOptions(apiKey = "test-key", interleavedThinking = false)),
        )
        assertEquals(
            null,
            buildAnthropicBetaHeader(
                createModel().copy(id = "claude-sonnet-4-6"),
                context,
                AnthropicOptions(apiKey = "test-key"),
            ),
        )
    }

    @Test
    fun `anthropic compat can disable eager tool input streaming`() {
        val model = createModel().copy(compat = ModelCompat(supportsEagerToolInputStreaming = false))
        val context = createSimpleContext().copy(tools = listOf(EchoTool))
        val payload = buildParams(model = model, context = context, options = AnthropicOptions(apiKey = "test-key"))

        val tool = payload["tools"]!!.jsonArray[0].jsonObject
        assertFalse(tool.containsKey("eager_input_streaming"))
        assertEquals(
            "fine-grained-tool-streaming-2025-05-14,interleaved-thinking-2025-05-14",
            buildAnthropicBetaHeader(model, context, AnthropicOptions(apiKey = "test-key")),
        )
    }

    @Test
    fun `anthropic long cache retention uses compat instead of base url`() {
        val proxyModel = createModel().copy(baseUrl = "https://my-proxy.example.com/v1")
        val payload =
            buildParams(
                model = proxyModel,
                context = createSimpleContext(),
                options = AnthropicOptions(apiKey = "test-key", cacheRetention = pi.ai.core.CacheRetention.LONG),
            )

        assertEquals(
            "1h",
            payload["system"]!!
                .jsonArray
                .first()
                .jsonObject["cache_control"]!!
                .jsonObject["ttl"]!!
                .jsonPrimitive.content,
        )

        val disabledPayload =
            buildParams(
                model = proxyModel.copy(compat = ModelCompat(supportsLongCacheRetention = false)),
                context = createSimpleContext(),
                options = AnthropicOptions(apiKey = "test-key", cacheRetention = pi.ai.core.CacheRetention.LONG),
            )
        val cacheControl =
            disabledPayload["system"]!!
                .jsonArray
                .first()
                .jsonObject["cache_control"]!!
                .jsonObject

        assertEquals("ephemeral", cacheControl["type"]!!.jsonPrimitive.content)
        assertFalse(cacheControl.containsKey("ttl"))
    }

    @Test
    fun `buildParams sanitizes unpaired surrogates in request text`() {
        val malformed = "bad " + Char(0xD83D) + " text"
        val sanitized = "bad  text"
        val payload =
            buildParams(
                model = createModel(),
                context =
                    Context(
                        systemPrompt = malformed,
                        messages =
                            listOf(
                                UserMessage(UserMessageContent.Text(malformed), 1L),
                                AssistantMessage(
                                    content =
                                        mutableListOf(
                                            TextContent(malformed),
                                            ThinkingContent(malformed),
                                            ToolCall("tool-1", "echo", buildJsonObject { put("value", "x") }),
                                        ),
                                    api = ANTHROPIC_MESSAGES_API,
                                    provider = ANTHROPIC_PROVIDER,
                                    model = "claude-sonnet-4-5",
                                    usage = Usage(),
                                    stopReason = StopReason.TOOL_USE,
                                    timestamp = 2L,
                                ),
                                ToolResultMessage(
                                    toolCallId = "tool-1",
                                    toolName = "echo",
                                    content = listOf(TextContent(malformed)),
                                    isError = false,
                                    timestamp = 3L,
                                ),
                            ),
                    ),
                options = AnthropicOptions(apiKey = "test-key"),
            )

        assertEquals(
            sanitized,
            payload["system"]!!
                .jsonArray[0]
                .jsonObject["text"]!!
                .jsonPrimitive.content,
        )
        val messages = payload["messages"]!!.jsonArray
        assertEquals(sanitized, messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        val assistantBlocks = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals(sanitized, assistantBlocks[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(sanitized, assistantBlocks[1].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals(
            sanitized,
            messages[2]
                .jsonObject["content"]!!
                .jsonArray[0]
                .jsonObject["content"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `handleSseEvent assembles tool call deltas into a final tool call`() =
        runTest {
            val stream = AssistantMessageEventStream()
            val output =
                AssistantMessage(
                    content = mutableListOf(),
                    api = ANTHROPIC_MESSAGES_API,
                    provider = ANTHROPIC_PROVIDER,
                    model = "claude-sonnet-4-5",
                    usage = Usage(),
                    stopReason = StopReason.STOP,
                    timestamp = System.currentTimeMillis(),
                )

            handleSseEvent(
                eventType = "content_block_start",
                rawData = """{"index":0,"content_block":{"type":"tool_use","id":"tool_1","name":"echo","input":{}}}""",
                output = output,
                stream = stream,
                model = createModel(),
            )
            handleSseEvent(
                eventType = "content_block_delta",
                rawData = """{"index":0,"delta":{"type":"input_json_delta","partial_json":"{\"value\":\"hello\"}"}}""",
                output = output,
                stream = stream,
                model = createModel(),
            )
            handleSseEvent(
                eventType = "content_block_stop",
                rawData = """{"index":0}""",
                output = output,
                stream = stream,
                model = createModel(),
            )
            handleSseEvent(
                eventType = "message_delta",
                rawData = """{"delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":12}}""",
                output = output,
                stream = stream,
                model = createModel(),
            )
            stream.push(AssistantMessageEvent.Done(output.stopReason, output))

            val events = stream.asFlow().toList()
            val result = stream.result()

            val toolCall = result.content.single() as ToolCall
            assertEquals("tool_1", toolCall.id)
            assertEquals("echo", toolCall.name)
            assertEquals("hello", toolCall.arguments["value"]!!.jsonPrimitive.content)
            assertEquals(StopReason.TOOL_USE, result.stopReason)
            assertTrue(events.any { it is AssistantMessageEvent.ToolCallDelta })
            assertTrue(events.any { it is AssistantMessageEvent.ToolCallEnd })
        }
}

private fun createModel(): Model<String> =
    Model(
        id = "claude-sonnet-4-5",
        name = "Claude Sonnet 4.5",
        api = ANTHROPIC_MESSAGES_API,
        provider = ANTHROPIC_PROVIDER,
        baseUrl = "https://api.anthropic.com",
        reasoning = true,
        input = setOf(InputModality.TEXT),
        cost = ModelCost(0.0, 0.0, 0.0, 0.0),
        contextWindow = 200_000,
        maxTokens = 64_000,
    )

private fun createSimpleContext(): Context =
    Context(
        systemPrompt = "You are helpful.",
        messages =
            listOf(
                UserMessage(
                    content = UserMessageContent.Text("Hello"),
                    timestamp = System.currentTimeMillis(),
                ),
            ),
        tools = emptyList(),
    )

private object EchoTool : Tool<JsonObject> {
    override val name: String = "echo"
    override val description: String = "Echo input"
    override val parameters: JsonObject = buildJsonObject { put("type", "object") }

    override fun validateArguments(arguments: JsonObject): JsonObject = arguments
}

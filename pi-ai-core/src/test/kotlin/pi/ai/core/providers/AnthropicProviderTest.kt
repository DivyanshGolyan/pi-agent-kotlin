package pi.ai.core.providers

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pi.ai.core.ANTHROPIC_MESSAGES_API
import pi.ai.core.ANTHROPIC_PROVIDER
import pi.ai.core.AssistantMessage
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.AssistantMessageEventStream
import pi.ai.core.Context
import pi.ai.core.InputModality
import pi.ai.core.Model
import pi.ai.core.ModelCost
import pi.ai.core.StopReason
import pi.ai.core.ToolCall
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

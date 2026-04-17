package pi.ai.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoreSmokeTest {
    @Test
    fun `getModel returns direct anthropic model metadata`() {
        val model = requireNotNull(getModel(ANTHROPIC_PROVIDER, "claude-sonnet-4-5"))

        assertEquals("claude-sonnet-4-5", model.id)
        assertEquals(ANTHROPIC_MESSAGES_API, model.api)
        assertEquals(ANTHROPIC_PROVIDER, model.provider)
        assertTrue(model.reasoning)
        assertTrue(model.input.contains(InputModality.TEXT))
    }

    @Test
    fun `validateToolArguments delegates to runtime validator`() {
        val tool =
            object : Tool<String> {
                override val name: String = "echo"
                override val description: String = "Echo tool"
                override val parameters: JsonObject = buildJsonObject { put("type", "object") }

                override fun validateArguments(arguments: JsonObject): String = arguments["value"]!!.toString().trim('"')
            }

        val toolCall =
            ToolCall(
                id = "tool-1",
                name = "echo",
                arguments = buildJsonObject { put("value", "42") },
            )

        assertEquals("42", validateToolArguments(tool, toolCall))
    }

    @Test
    fun `assistant event stream exposes terminal result`() =
        runTest {
            val message =
                AssistantMessage(
                    content = mutableListOf(TextContent("hello")),
                    api = ANTHROPIC_MESSAGES_API,
                    provider = ANTHROPIC_PROVIDER,
                    model = "claude-sonnet-4-5",
                    usage = Usage(),
                    stopReason = StopReason.STOP,
                    timestamp = 1L,
                )
            val stream = AssistantMessageEventStream()
            stream.push(AssistantMessageEvent.Start(message.copy()))
            stream.push(AssistantMessageEvent.Done(StopReason.STOP, message))

            val events = stream.asFlow().toList()

            assertEquals(2, events.size)
            assertEquals(message, stream.result())
        }
}

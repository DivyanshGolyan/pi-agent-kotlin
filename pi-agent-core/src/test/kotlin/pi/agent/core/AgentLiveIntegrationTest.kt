package pi.agent.core

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import pi.ai.core.ANTHROPIC_PROVIDER
import pi.ai.core.AbortSignal
import pi.ai.core.TextContent
import pi.ai.core.ToolResultMessage
import pi.ai.core.getModel

@Tag("live")
class AgentLiveIntegrationTest {
    @Test
    fun `agent live executes tool calls on haiku`() =
        runTest {
            assumeTrue(apiKey().isNotBlank(), "ANTHROPIC_API_KEY is required for live tests")

            val tool =
                object : AgentTool<String> {
                    override val name: String = "add_numbers"
                    override val label: String = "Add Numbers"
                    override val description: String = "Adds two integers and returns the sum."
                    override val parameters: JsonObject =
                        buildJsonObject {
                            put("type", JsonPrimitive("object"))
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "a",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("integer"))
                                        },
                                    )
                                    put(
                                        "b",
                                        buildJsonObject {
                                            put("type", JsonPrimitive("integer"))
                                        },
                                    )
                                },
                            )
                            put("required", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))))
                        }

                    override fun validateArguments(arguments: JsonObject): String {
                        val a = arguments["a"]?.jsonPrimitive?.content?.toInt() ?: error("Missing a")
                        val b = arguments["b"]?.jsonPrimitive?.content?.toInt() ?: error("Missing b")
                        return "${a + b}"
                    }

                    override suspend fun execute(
                        toolCallId: String,
                        params: String,
                        signal: AbortSignal?,
                        onUpdate: AgentToolUpdateCallback<JsonElement>?,
                    ): AgentToolResult<JsonElement> =
                        AgentToolResult(
                            content = listOf(TextContent("sum=$params")),
                            details = JsonPrimitive(params),
                        )
                }

            val agent =
                Agent(
                    AgentOptions(
                        initialState =
                            InitialAgentState(
                                systemPrompt = "Use tools when instructed. After using a tool, answer briefly.",
                                model = requireNotNull(getModel(ANTHROPIC_PROVIDER, "claude-haiku-4-5")),
                                thinkingLevel = AgentThinkingLevel.OFF,
                                tools = listOf(tool),
                            ),
                        getApiKey = { apiKey() },
                    ),
                )

            agent.prompt("Use add_numbers with a=2 and b=3. Then answer with RESULT:5")

            val toolMessages = agent.state.messages.filterIsInstance<ToolResultMessage>()
            val finalAssistant =
                agent.state.messages
                    .last { it is pi.ai.core.AssistantMessage } as pi.ai.core.AssistantMessage

            assertTrue(toolMessages.isNotEmpty())
            assertTrue(toolMessages.any { toolMessage -> toolMessage.toolName == "add_numbers" })
            assertTrue(
                finalAssistant.content
                    .filterIsInstance<TextContent>()
                    .joinToString("\n") { it.text }
                    .contains("5"),
            )
        }

    private fun apiKey(): String = System.getenv("ANTHROPIC_API_KEY").orEmpty()
}

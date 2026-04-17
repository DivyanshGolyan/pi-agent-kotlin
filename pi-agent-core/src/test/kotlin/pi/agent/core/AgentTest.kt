package pi.agent.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pi.ai.core.AbortSignal
import pi.ai.core.AssistantMessage
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.AssistantMessageEventStream
import pi.ai.core.CacheRetention
import pi.ai.core.Model
import pi.ai.core.ModelCost
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.Usage
import pi.ai.core.UsageCost
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent

class AgentTest {
    @Test
    fun `agent starts with default state`() {
        val agent = Agent()

        assertEquals("", agent.state.systemPrompt)
        assertEquals(AgentThinkingLevel.OFF, agent.state.thinkingLevel)
        assertTrue(agent.state.tools.isEmpty())
        assertTrue(agent.state.messages.isEmpty())
        assertFalse(agent.state.isStreaming)
        assertNull(agent.state.streamingMessage)
        assertTrue(agent.state.pendingToolCalls.isEmpty())
        assertNull(agent.state.errorMessage)
    }

    @Test
    fun `prompt waits for async subscribers before resolving`() =
        runTest {
            val barrier = CompletableDeferred<Unit>()
            val agent =
                Agent(
                    AgentOptions(
                        streamFn = { _, _, _ ->
                            AssistantMessageEventStream().also { stream ->
                                stream.push(
                                    AssistantMessageEvent.Done(
                                        reason = StopReason.STOP,
                                        message = createAssistantMessage("ok"),
                                    ),
                                )
                            }
                        },
                    ),
                )

            var listenerFinished = false
            agent.subscribe { event, _ ->
                if (event is AgentEvent.AgentEnd) {
                    barrier.await()
                    listenerFinished = true
                }
            }

            var promptResolved = false
            val promptJob =
                launch {
                    agent.prompt("hello")
                    promptResolved = true
                }

            delay(10)
            assertFalse(promptResolved)
            assertFalse(listenerFinished)
            assertTrue(agent.state.isStreaming)

            barrier.complete(Unit)
            promptJob.join()

            assertTrue(listenerFinished)
            assertTrue(promptResolved)
            assertFalse(agent.state.isStreaming)
        }

    @Test
    fun `subscribers receive active abort signal`() =
        runTest {
            var receivedSignal: AbortSignal? = null
            val agent =
                Agent(
                    AgentOptions(
                        initialState = InitialAgentState(model = createModel()),
                        streamFn = { _, _, options ->
                            AssistantMessageEventStream().also { stream ->
                                stream.push(AssistantMessageEvent.Start(createAssistantMessage("")))
                                launch {
                                    while (true) {
                                        if (options?.signal?.aborted == true) {
                                            stream.push(
                                                AssistantMessageEvent.Error(
                                                    reason = StopReason.ABORTED,
                                                    error = createAssistantMessage("Aborted", StopReason.ABORTED),
                                                ),
                                            )
                                            break
                                        }
                                        delay(5)
                                    }
                                }
                            }
                        },
                    ),
                )

            agent.subscribe { event, signal ->
                if (event is AgentEvent.AgentStart) {
                    receivedSignal = signal
                }
            }

            val promptJob = launch { agent.prompt("hello") }
            delay(20)

            assertNotNull(receivedSignal)
            assertFalse(receivedSignal!!.aborted)

            agent.abort()
            promptJob.join()

            assertTrue(receivedSignal.aborted)
        }

    @Test
    fun `agent forwards cache retention to stream options`() =
        runTest {
            var receivedCacheRetention: CacheRetention? = null
            val agent =
                Agent(
                    AgentOptions(
                        initialState =
                            InitialAgentState(
                                model = createModel(),
                            ),
                        cacheRetention = CacheRetention.LONG,
                        streamFn = { _, _, options ->
                            receivedCacheRetention = options?.cacheRetention
                            AssistantMessageEventStream().also { stream ->
                                stream.push(
                                    AssistantMessageEvent.Done(
                                        reason = StopReason.STOP,
                                        message = createAssistantMessage("ok"),
                                    ),
                                )
                            }
                        },
                    ),
                )

            agent.prompt("hello")

            assertEquals(CacheRetention.LONG, receivedCacheRetention)
        }

    @Test
    fun `queue operations and reset update agent state`() {
        val agent =
            Agent(
                AgentOptions(
                    initialState =
                        InitialAgentState(
                            model = createModel(),
                            messages = listOf(createUserMessage("existing")),
                        ),
                ),
            )

        agent.steeringMode = QueueMode.ALL
        agent.followUpMode = QueueMode.ALL
        agent.steer(createUserMessage("steer"))
        agent.followUp(createUserMessage("follow-up"))

        assertEquals(QueueMode.ALL, agent.steeringMode)
        assertEquals(QueueMode.ALL, agent.followUpMode)
        assertTrue(agent.hasQueuedMessages())

        agent.clearSteeringQueue()
        assertTrue(agent.hasQueuedMessages())

        agent.clearFollowUpQueue()
        assertFalse(agent.hasQueuedMessages())

        agent.steer(createUserMessage("steer-again"))
        agent.followUp(createUserMessage("follow-up-again"))
        assertTrue(agent.hasQueuedMessages())

        agent.reset()

        assertTrue(agent.state.messages.isEmpty())
        assertFalse(agent.hasQueuedMessages())
        assertFalse(agent.state.isStreaming)
        assertNull(agent.state.streamingMessage)
        assertNull(agent.state.errorMessage)
    }

    @Test
    fun `continue consumes queued steering after assistant tail`() =
        runTest {
            val seenPrompts = mutableListOf<String>()
            val agent =
                Agent(
                    AgentOptions(
                        initialState =
                            InitialAgentState(
                                model = createModel(),
                                messages = listOf(createAssistantMessage("ready")),
                            ),
                        streamFn = { _, context, _ ->
                            val lastUser =
                                context.messages
                                    .last { it is UserMessage } as UserMessage
                            seenPrompts += (lastUser.content as UserMessageContent.Text).value
                            AssistantMessageEventStream().also { stream ->
                                stream.push(
                                    AssistantMessageEvent.Done(
                                        reason = StopReason.STOP,
                                        message = createAssistantMessage("ok"),
                                    ),
                                )
                            }
                        },
                    ),
                )

            agent.steer(createUserMessage("steer-now"))
            agent.`continue`()

            assertEquals(listOf("steer-now"), seenPrompts)
            assertTrue(agent.state.messages.last() is AssistantMessage)
        }

    @Test
    fun `continue fails from assistant tail without queued messages`() =
        runTest {
            val agent =
                Agent(
                    AgentOptions(
                        initialState =
                            InitialAgentState(
                                model = createModel(),
                                messages = listOf(createAssistantMessage("done")),
                            ),
                    ),
                )

            var error: IllegalStateException? = null
            try {
                agent.`continue`()
            } catch (caught: IllegalStateException) {
                error = caught
            }

            assertEquals("Cannot continue from message role: assistant", error?.message)
        }
}

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
    text: String,
    stopReason: StopReason = StopReason.STOP,
): AssistantMessage =
    AssistantMessage(
        content = mutableListOf(TextContent(text)),
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

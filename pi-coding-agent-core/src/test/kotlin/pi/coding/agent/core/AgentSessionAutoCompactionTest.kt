package pi.coding.agent.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pi.ai.core.ApiProvider
import pi.ai.core.AssistantMessage
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.AssistantMessageEventStream
import pi.ai.core.Context
import pi.ai.core.InputModality
import pi.ai.core.Model
import pi.ai.core.ModelCost
import pi.ai.core.SimpleStreamOptions
import pi.ai.core.StopReason
import pi.ai.core.StreamOptions
import pi.ai.core.TextContent
import pi.ai.core.Usage
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import pi.ai.core.registerApiProvider
import pi.coding.agent.core.compaction.CompactionSettings
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger

class AgentSessionAutoCompactionTest {
    @Test
    fun `overflow compacts removes active error and retries continuation`() =
        runBlocking {
            val model = autoCompactionModel()
            registerApiProvider(SummaryProvider(model.api))
            val authStorage = tempAuthStorage(model.provider)
            val settings = SettingsManager.create()
            settings.setCompactionSettings(testCompactionSettings())
            val promptCalls = AtomicInteger(0)
            val events = mutableListOf<AgentSessionEvent>()
            val session =
                createAgentSession(
                    CreateAgentSessionOptions(
                        model = model,
                        authStorage = authStorage,
                        settingsManager = settings,
                        sessionManager = SessionManager.inMemory(),
                        streamFn = { _, _, _ ->
                            when (promptCalls.incrementAndGet()) {
                                1 -> streamMessage(errorAssistant(model, "prompt is too long: 200001 tokens > 200000 maximum"))
                                else -> streamMessage(stopAssistant(model, "retried response", usage = usage(10, 5)))
                            }
                        },
                    ),
                ).session
            session.subscribe { events += it }

            session.prompt("make a large request")
            withTimeout(2_000) {
                while (promptCalls.get() < 2) {
                    delay(10)
                }
            }

            val compactionEnds = events.filterIsInstance<AgentSessionEvent.CompactionEnd>()
            assertTrue(compactionEnds.any { it.reason == CompactionReason.OVERFLOW && it.willRetry })
            assertEquals("retried response", session.getLastAssistantText())
            assertFalse(
                session.messages.any {
                    it is AssistantMessage &&
                        it.errorMessage == "prompt is too long: 200001 tokens > 200000 maximum"
                },
            )
            assertTrue(
                session.sessionManager
                    .getEntries()
                    .filterIsInstance<SessionMessageEntry>()
                    .any { (it.message as? AssistantMessage)?.errorMessage == "prompt is too long: 200001 tokens > 200000 maximum" },
            )
            assertTrue(session.sessionManager.getEntries().any { it is CompactionEntry })
        }

    @Test
    fun `overflow recovery does not loop repeatedly`() =
        runBlocking {
            val model = autoCompactionModel()
            registerApiProvider(SummaryProvider(model.api))
            val authStorage = tempAuthStorage(model.provider)
            val settings = SettingsManager.create()
            settings.setCompactionSettings(testCompactionSettings())
            val promptCalls = AtomicInteger(0)
            val events = mutableListOf<AgentSessionEvent>()
            val session =
                createAgentSession(
                    CreateAgentSessionOptions(
                        model = model,
                        authStorage = authStorage,
                        settingsManager = settings,
                        sessionManager = SessionManager.inMemory(),
                        streamFn = { _, _, _ ->
                            promptCalls.incrementAndGet()
                            streamMessage(errorAssistant(model, "prompt is too long"))
                        },
                    ),
                ).session
            session.subscribe { events += it }

            session.prompt("too large")
            withTimeout(2_000) {
                while (promptCalls.get() < 2) {
                    delay(10)
                }
            }
            delay(150)

            assertEquals(2, promptCalls.get())
            assertTrue(
                events.filterIsInstance<AgentSessionEvent.CompactionEnd>().any {
                    it.reason == CompactionReason.OVERFLOW &&
                        it.errorMessage?.contains("failed after one compact-and-retry attempt") == true
                },
            )
        }

    @Test
    fun `threshold compaction runs after non-overflow error using prior usage`() =
        runBlocking {
            val model = autoCompactionModel(contextWindow = 1_000)
            registerApiProvider(SummaryProvider(model.api))
            val authStorage = tempAuthStorage(model.provider)
            val settings = SettingsManager.create()
            settings.setCompactionSettings(testCompactionSettings(reserveTokens = 100, keepRecentTokens = 0))
            val promptCalls = AtomicInteger(0)
            val events = mutableListOf<AgentSessionEvent>()
            val session =
                createAgentSession(
                    CreateAgentSessionOptions(
                        model = model,
                        authStorage = authStorage,
                        settingsManager = settings,
                        sessionManager = SessionManager.inMemory(),
                        streamFn = { _, _, _ ->
                            when (promptCalls.incrementAndGet()) {
                                1 -> streamMessage(stopAssistant(model, "near threshold", usage = usage(880, 10)))
                                else -> streamMessage(errorAssistant(model, "529 overloaded"))
                            }
                        },
                    ),
                ).session
            session.subscribe { events += it }

            session.prompt("first")
            session.prompt("second ".repeat(400))

            assertTrue(
                events.filterIsInstance<AgentSessionEvent.CompactionEnd>().any {
                    it.reason == CompactionReason.THRESHOLD && !it.willRetry && it.result != null
                },
            )
        }

    @Test
    fun `pre prompt check compacts aborted high usage response`() =
        runBlocking {
            val model = autoCompactionModel(contextWindow = 1_000)
            registerApiProvider(SummaryProvider(model.api))
            val authStorage = tempAuthStorage(model.provider)
            val settings = SettingsManager.create()
            settings.setCompactionSettings(testCompactionSettings(reserveTokens = 100, keepRecentTokens = 0))
            val promptCalls = AtomicInteger(0)
            val events = mutableListOf<AgentSessionEvent>()
            val session =
                createAgentSession(
                    CreateAgentSessionOptions(
                        model = model,
                        authStorage = authStorage,
                        settingsManager = settings,
                        sessionManager = SessionManager.inMemory(),
                        streamFn = { _, _, _ ->
                            when (promptCalls.incrementAndGet()) {
                                1 ->
                                    streamMessage(
                                        AssistantMessage(
                                            content = mutableListOf(TextContent("")),
                                            api = model.api,
                                            provider = model.provider,
                                            model = model.id,
                                            usage = usage(950, 10),
                                            stopReason = StopReason.ABORTED,
                                            timestamp = System.currentTimeMillis(),
                                        ),
                                    )
                                else -> streamMessage(stopAssistant(model, "after pre prompt compaction"))
                            }
                        },
                    ),
                ).session
            session.subscribe { events += it }

            session.prompt("first")
            assertFalse(events.filterIsInstance<AgentSessionEvent.CompactionEnd>().any { it.reason == CompactionReason.THRESHOLD })

            session.prompt("second")

            assertTrue(
                events.filterIsInstance<AgentSessionEvent.CompactionEnd>().any {
                    it.reason == CompactionReason.THRESHOLD && it.result != null
                },
            )
            assertEquals(2, promptCalls.get())
        }

    @Test
    fun `auto compaction aborts before appending summary`() =
        runBlocking {
            val model = autoCompactionModel(contextWindow = 1_000)
            val authStorage = tempAuthStorage(model.provider)
            val settings = SettingsManager.create()
            settings.setCompactionSettings(testCompactionSettings(reserveTokens = 100, keepRecentTokens = 0))
            val events = mutableListOf<AgentSessionEvent>()
            lateinit var session: AgentSession
            session =
                createAgentSession(
                    CreateAgentSessionOptions(
                        model = model,
                        authStorage = authStorage,
                        settingsManager = settings,
                        sessionManager = SessionManager.inMemory(),
                        streamFn = { _, _, _ -> streamMessage(stopAssistant(model, "near threshold", usage = usage(950, 10))) },
                    ),
                ).session
            registerApiProvider(CallbackSummaryProvider(model.api) { session.abortCompaction() })
            session.subscribe { event ->
                events += event
            }

            session.prompt("first")

            assertTrue(events.filterIsInstance<AgentSessionEvent.CompactionEnd>().any { it.aborted })
            assertFalse(session.sessionManager.getEntries().any { it is CompactionEntry })
        }

    @Test
    fun `stale pre-compaction usage does not trigger threshold compaction after error`() =
        runBlocking {
            val model = autoCompactionModel(contextWindow = 1_000)
            registerApiProvider(SummaryProvider(model.api))
            val authStorage = tempAuthStorage(model.provider)
            val sessionManager = SessionManager.inMemory()
            sessionManager.appendMessage(user("before compaction", timestamp = 1_000))
            val keptAssistant = stopAssistant(model, "old high usage", usage = usage(950, 10), timestamp = 2_000)
            sessionManager.appendMessage(keptAssistant)
            val firstKeptEntryId = sessionManager.getEntries().first().id
            sessionManager.appendCompaction("summary", firstKeptEntryId, keptAssistant.usage.totalTokens)
            val settings = SettingsManager.create()
            settings.setCompactionSettings(testCompactionSettings(reserveTokens = 100, keepRecentTokens = 0))
            val events = mutableListOf<AgentSessionEvent>()
            val session =
                createAgentSession(
                    CreateAgentSessionOptions(
                        model = model,
                        authStorage = authStorage,
                        settingsManager = settings,
                        sessionManager = sessionManager,
                        streamFn = { _, _, _ -> streamMessage(errorAssistant(model, "529 overloaded")) },
                    ),
                ).session
            session.subscribe { events += it }

            session.prompt("new prompt")

            assertFalse(events.filterIsInstance<AgentSessionEvent.CompactionEnd>().any { it.reason == CompactionReason.THRESHOLD })
        }

    private fun testCompactionSettings(
        reserveTokens: Int = 1,
        keepRecentTokens: Int = 0,
    ): CompactionSettings =
        CompactionSettings(
            enabled = true,
            reserveTokens = reserveTokens,
            keepRecentTokens = keepRecentTokens,
        )

    private fun tempAuthStorage(provider: String): AuthStorage {
        val path = Files.createTempFile("pi-agent-auth", ".json").toString()
        return AuthStorage.create(path).also { it.setApiKey(provider, "test-key") }
    }

    private fun streamMessage(message: AssistantMessage): AssistantMessageEventStream =
        AssistantMessageEventStream().also { stream ->
            stream.push(AssistantMessageEvent.Done(message.stopReason, message))
        }

    private fun user(
        text: String,
        timestamp: Long = System.currentTimeMillis(),
    ): UserMessage = UserMessage(UserMessageContent.Text(text), timestamp)

    private fun stopAssistant(
        model: Model<String>,
        text: String,
        usage: Usage = usage(10, 5),
        timestamp: Long = System.currentTimeMillis(),
    ): AssistantMessage =
        AssistantMessage(
            content = mutableListOf(TextContent(text)),
            api = model.api,
            provider = model.provider,
            model = model.id,
            usage = usage,
            stopReason = StopReason.STOP,
            timestamp = timestamp,
        )

    private fun errorAssistant(
        model: Model<String>,
        errorMessage: String,
    ): AssistantMessage =
        AssistantMessage(
            content = mutableListOf(TextContent("")),
            api = model.api,
            provider = model.provider,
            model = model.id,
            usage = Usage(),
            stopReason = StopReason.ERROR,
            errorMessage = errorMessage,
            timestamp = System.currentTimeMillis(),
        )

    private fun usage(
        input: Int,
        output: Int,
    ): Usage =
        Usage(
            input = input,
            output = output,
            totalTokens = input + output,
        )

    private fun autoCompactionModel(contextWindow: Int = 200_000): Model<String> =
        Model(
            id = "auto-compaction-test-model",
            name = "Auto Compaction Test Model",
            api = "auto-compaction-test-api",
            provider = "auto-compaction-test-provider",
            baseUrl = "http://localhost",
            reasoning = false,
            input = setOf(InputModality.TEXT),
            cost = ModelCost(0.0, 0.0, 0.0, 0.0),
            contextWindow = contextWindow,
            maxTokens = 4_096,
        )

    private class SummaryProvider(
        override val api: String,
    ) : ApiProvider {
        override fun stream(
            model: Model<*>,
            context: Context,
            options: StreamOptions?,
        ): AssistantMessageEventStream = streamSimple(model, context, null)

        override fun streamSimple(
            model: Model<*>,
            context: Context,
            options: SimpleStreamOptions?,
        ): AssistantMessageEventStream =
            AssistantMessageEventStream().also { stream ->
                stream.push(
                    AssistantMessageEvent.Done(
                        StopReason.STOP,
                        AssistantMessage(
                            content = mutableListOf(TextContent("## Goal\nSummarized.")),
                            api = model.api,
                            provider = model.provider,
                            model = model.id,
                            usage = Usage(input = 10, output = 5, totalTokens = 15),
                            stopReason = StopReason.STOP,
                            timestamp = System.currentTimeMillis(),
                        ),
                    ),
                )
            }
    }

    private class CallbackSummaryProvider(
        override val api: String,
        private val onStream: () -> Unit,
    ) : ApiProvider {
        override fun stream(
            model: Model<*>,
            context: Context,
            options: StreamOptions?,
        ): AssistantMessageEventStream = streamSimple(model, context, null)

        override fun streamSimple(
            model: Model<*>,
            context: Context,
            options: SimpleStreamOptions?,
        ): AssistantMessageEventStream {
            onStream()
            return SummaryProvider(api).streamSimple(model, context, options)
        }
    }
}

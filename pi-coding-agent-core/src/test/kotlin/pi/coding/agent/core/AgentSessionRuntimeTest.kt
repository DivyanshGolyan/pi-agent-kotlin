package pi.coding.agent.core

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import pi.ai.core.AssistantMessage
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.AssistantMessageEventStream
import pi.ai.core.Model
import pi.ai.core.ModelCost
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.Usage
import pi.ai.core.UsageCost
import java.nio.file.Files

class AgentSessionRuntimeTest {
    @Test
    fun `create agent session persists prompt messages and assistant response`() =
        runTest {
            val sessionManager = SessionManager.inMemory()
            val result =
                createAgentSession(
                    CreateAgentSessionOptions(
                        model = createModel(),
                        sessionManager = sessionManager,
                        streamFn = fakeStream("assistant reply"),
                    ),
                )

            result.session.prompt("hello world")

            val entries = sessionManager.getEntries()
            assertEquals(4, entries.size)
            assertTrue(result.session.messages.last() is AssistantMessage)
            assertEquals("assistant reply", result.session.getLastAssistantText())
        }

    @Test
    fun `create agent session passes resolved api key and headers to stream`() =
        runTest {
            val auth = AuthStorage.create(Files.createTempFile("auth", ".json").toString())
            auth.setApiKey("anthropic", "sk-test", mapOf("x-auth" to "auth-header"))
            val modelsPath = Files.createTempFile("models", ".json")
            Files.writeString(
                modelsPath,
                """
                {
                    "providers": {
                        "anthropic": {
                            "headers": {
                                "x-provider": "provider-header"
                            },
                            "modelOverrides": {
                                "claude-sonnet-4-5": {
                                    "headers": {
                                        "x-model": "model-header"
                                    }
                                }
                            }
                        }
                    }
                }
                """.trimIndent(),
            )
            val registry = ModelRegistry.create(auth, modelsPath.toString())
            var seenApiKey: String? = null
            var seenHeaders: Map<String, String> = emptyMap()
            val streamFn: pi.agent.core.StreamFn = { _, _, options ->
                seenApiKey = options?.apiKey
                seenHeaders = options?.headers.orEmpty()
                fakeStream("ok").invoke(createModel(), pi.ai.core.Context(messages = emptyList()), options)
            }

            val session =
                createAgentSession(
                    CreateAgentSessionOptions(
                        authStorage = auth,
                        modelRegistry = registry,
                        model = registry.find("anthropic", "claude-sonnet-4-5"),
                        sessionManager = SessionManager.inMemory(),
                        streamFn = streamFn,
                    ),
                ).session

            session.prompt("hello")

            assertEquals("sk-test", seenApiKey)
            assertEquals("auth-header", seenHeaders["x-auth"])
            assertEquals("provider-header", seenHeaders["x-provider"])
            assertEquals("model-header", seenHeaders["x-model"])
        }

    @Test
    fun `set session name emits normalized stored name`() =
        runTest {
            val session =
                createAgentSession(
                    CreateAgentSessionOptions(
                        model = createModel(),
                        sessionManager = SessionManager.inMemory(),
                        streamFn = fakeStream("ok"),
                    ),
                ).session
            val events = mutableListOf<AgentSessionEvent>()
            session.subscribe { events += it }

            session.setSessionName("  hello world  ")

            assertEquals("hello world", session.sessionName)
            assertEquals(
                listOf("hello world"),
                events.filterIsInstance<AgentSessionEvent.SessionInfoChanged>().map { it.name },
            )
        }

    @Test
    fun `navigate tree to user message returns editor text and rewinds context`() =
        runTest {
            val session =
                createAgentSession(
                    CreateAgentSessionOptions(
                        model = createModel(),
                        sessionManager = SessionManager.inMemory(),
                        streamFn = fakeStream("ok"),
                    ),
                ).session

            session.prompt("first question")
            session.prompt("second question")

            val firstUserId =
                session.sessionManager
                    .getEntries()
                    .filterIsInstance<SessionMessageEntry>()
                    .first { it.message is pi.ai.core.UserMessage }
                    .id

            val result = session.navigateTree(firstUserId)

            assertFalse(result.cancelled)
            assertEquals("first question", result.editorText)
            assertTrue(session.messages.isEmpty())
        }

    @Test
    fun `runtime can create new session and switch back to persisted session`() =
        runTest {
            val cwd = Files.createTempDirectory("pi-coding-runtime").toString()
            val sessionDir = Files.createTempDirectory("pi-coding-sessions").toString()
            val initialManager = SessionManager.create(cwd, sessionDir)

            val runtime =
                createAgentSessionRuntime(
                    createRuntime = runtimeFactory(),
                    cwd = cwd,
                    agentDir = getAgentDir(),
                    sessionManager = initialManager,
                )

            runtime.session.prompt("persist me")
            val originalSessionFile = runtime.session.sessionFile
            val originalSessionId = runtime.session.sessionId
            assertNotNull(originalSessionFile)

            runtime.newSession()

            assertNotEquals(originalSessionId, runtime.session.sessionId)

            runtime.switchSession(requireNotNull(originalSessionFile))

            assertEquals(originalSessionId, runtime.session.sessionId)
            assertTrue(runtime.session.messages.any { it is pi.ai.core.UserMessage })
        }

    @Test
    fun `runtime fork returns selected text and replaces active session`() =
        runTest {
            val runtime =
                createAgentSessionRuntime(
                    createRuntime = runtimeFactory(),
                    cwd = Files.createTempDirectory("pi-coding-fork").toString(),
                    agentDir = getAgentDir(),
                    sessionManager = SessionManager.inMemory(),
                )

            runtime.session.prompt("fork me")
            val userEntryId =
                runtime.session.sessionManager
                    .getEntries()
                    .filterIsInstance<SessionMessageEntry>()
                    .first { it.message is pi.ai.core.UserMessage }
                    .id
            val previousSessionId = runtime.session.sessionId

            val (cancelled, selectedText) = runtime.fork(userEntryId)

            assertFalse(cancelled)
            assertEquals("fork me", selectedText)
            assertNotEquals(previousSessionId, runtime.session.sessionId)
        }

    @Test
    fun `runtime fork at selected entry keeps that entry in fork context`() =
        runTest {
            val runtime =
                createAgentSessionRuntime(
                    createRuntime = runtimeFactory(),
                    cwd = Files.createTempDirectory("pi-coding-fork-at").toString(),
                    agentDir = getAgentDir(),
                    sessionManager = SessionManager.inMemory(),
                )

            runtime.session.prompt("fork at me")
            val assistantEntryId =
                runtime.session.sessionManager
                    .getEntries()
                    .filterIsInstance<SessionMessageEntry>()
                    .first { it.message is AssistantMessage }
                    .id
            val previousSessionId = runtime.session.sessionId

            val (cancelled, selectedText) = runtime.fork(assistantEntryId, ForkSessionOptions(ForkPosition.AT))

            assertFalse(cancelled)
            assertEquals(null, selectedText)
            assertNotEquals(previousSessionId, runtime.session.sessionId)
            assertTrue(runtime.session.messages.any { it is AssistantMessage })
        }

    private fun runtimeFactory(): CreateAgentSessionRuntimeFactory =
        { cwd, agentDir, sessionManager, _ ->
            val services =
                createAgentSessionServices(
                    CreateAgentSessionServicesOptions(
                        cwd = cwd,
                        agentDir = agentDir,
                    ),
                )
            val result =
                createAgentSessionFromServices(
                    CreateAgentSessionFromServicesOptions(
                        services = services,
                        sessionManager = sessionManager,
                        model = createModel(),
                        streamFn = fakeStream("ok"),
                    ),
                )
            CreateAgentSessionRuntimeResult(
                session = result.session,
                services = services,
                diagnostics = services.diagnostics,
                modelFallbackMessage = result.modelFallbackMessage,
            )
        }

    private fun fakeStream(text: String): pi.agent.core.StreamFn =
        { _, _, _ ->
            AssistantMessageEventStream().also { stream ->
                stream.push(
                    AssistantMessageEvent.Done(
                        reason = StopReason.STOP,
                        message = createAssistantMessage(text),
                    ),
                )
            }
        }

    private fun createAssistantMessage(text: String): AssistantMessage =
        AssistantMessage(
            content = mutableListOf(TextContent(text)),
            api = "anthropic-messages",
            provider = "anthropic",
            model = "claude-sonnet-4-5",
            usage =
                Usage(
                    input = 100,
                    output = 50,
                    cacheRead = 0,
                    cacheWrite = 0,
                    totalTokens = 150,
                    cost = UsageCost(total = 0.001),
                ),
            stopReason = StopReason.STOP,
            timestamp = System.currentTimeMillis(),
        )

    private fun createModel(): Model<String> =
        Model(
            id = "claude-sonnet-4-5",
            name = "Claude Sonnet 4.5",
            api = "anthropic-messages",
            provider = "anthropic",
            baseUrl = "https://api.anthropic.com",
            reasoning = true,
            input = setOf(pi.ai.core.InputModality.TEXT),
            cost = ModelCost(3.0, 15.0, 0.3, 3.75),
            contextWindow = 200_000,
            maxTokens = 64_000,
        )
}

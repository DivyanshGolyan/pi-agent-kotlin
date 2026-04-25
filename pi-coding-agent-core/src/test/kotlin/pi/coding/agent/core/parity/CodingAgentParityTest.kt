package pi.coding.agent.core.parity

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestFactory
import pi.agent.core.AgentEvent
import pi.agent.core.AgentMessage
import pi.agent.core.AgentThinkingLevel
import pi.ai.core.ApiProvider
import pi.ai.core.AssistantContentBlock
import pi.ai.core.AssistantMessage
import pi.ai.core.AssistantMessageEvent
import pi.ai.core.AssistantMessageEventStream
import pi.ai.core.Context
import pi.ai.core.ImageContent
import pi.ai.core.InputModality
import pi.ai.core.Model
import pi.ai.core.ModelCost
import pi.ai.core.SimpleStreamOptions
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.ThinkingContent
import pi.ai.core.ToolCall
import pi.ai.core.ToolResultMessage
import pi.ai.core.Usage
import pi.ai.core.UserContentPart
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import pi.ai.core.registerApiProvider
import pi.coding.agent.core.AgentSession
import pi.coding.agent.core.AgentSessionEvent
import pi.coding.agent.core.AuthStorage
import pi.coding.agent.core.BashExecutionMessage
import pi.coding.agent.core.BranchSummaryEntry
import pi.coding.agent.core.BranchSummaryMessage
import pi.coding.agent.core.CompactionEntry
import pi.coding.agent.core.CompactionSummaryMessage
import pi.coding.agent.core.CreateAgentSessionOptions
import pi.coding.agent.core.CreateAgentSessionRuntimeFactory
import pi.coding.agent.core.CreateAgentSessionRuntimeResult
import pi.coding.agent.core.CustomEntry
import pi.coding.agent.core.CustomMessage
import pi.coding.agent.core.CustomMessageEntry
import pi.coding.agent.core.DefaultResourceLoader
import pi.coding.agent.core.DefaultResourceLoaderOptions
import pi.coding.agent.core.LabelEntry
import pi.coding.agent.core.ModelChangeEntry
import pi.coding.agent.core.ModelRegistry
import pi.coding.agent.core.ModelScope
import pi.coding.agent.core.ReadonlySessionManager
import pi.coding.agent.core.ResourceLoader
import pi.coding.agent.core.SessionInfoEntry
import pi.coding.agent.core.SessionManager
import pi.coding.agent.core.SessionMessageEntry
import pi.coding.agent.core.SessionStartEvent
import pi.coding.agent.core.SettingsManager
import pi.coding.agent.core.ThinkingLevelChangeEntry
import pi.coding.agent.core.buildSessionContext
import pi.coding.agent.core.compaction.BranchSummarySettings
import pi.coding.agent.core.compaction.CompactionSettings
import pi.coding.agent.core.createAgentSession
import pi.coding.agent.core.createAgentSessionRuntime
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

@Suppress("LargeClass")
@Tag("parity")
class CodingAgentParityTest {
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
                            if (scenario.string("suite") != "pi-coding-agent-core") {
                                return@dynamicTest
                            }

                            val actual = runScenario(scenario)
                            val fixturePath = rootDir.resolve("parity/fixtures/${scenario.string("id")}.json")
                            val actualPath = rootDir.resolve("build/parity-actual/${scenario.string("id")}.json")
                            Files.createDirectories(actualPath.parent)
                            Files.writeString(actualPath, json.encodeToString(JsonElement.serializer(), actual))
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
            "coding_session_manager_scripted" -> runSessionManagerScenario(scenario)
            "coding_agent_session_scripted" -> runAgentSessionScenario(scenario)
            "coding_agent_runtime_scripted" -> runRuntimeScenario(scenario)
            else -> error("Unsupported coding-agent parity scenario kind: ${scenario.string("kind")}")
        }

    private fun runSessionManagerScenario(scenario: JsonObject): JsonElement {
        val manager = SessionManager.inMemory()
        val aliases = linkedMapOf<String, String>()
        val modelCatalog = scenario.modelCatalog()

        fun resolveAlias(value: String?): String? = value?.let { aliases[it] ?: it }

        scenario["operations"]!!.jsonArray.forEachIndexed { index, element ->
            val operation = element.jsonObject
            when (operation.string("op")) {
                "appendMessage" -> {
                    val id =
                        if (operation.string("role") == "user") {
                            manager.appendMessage(
                                UserMessage(
                                    content = operation["text"]!!.jsonPrimitive.toUserMessageContent(),
                                    timestamp = (index + 1).toLong(),
                                ),
                            )
                        } else {
                            manager.appendMessage(
                                createAssistantMessage(
                                    model = modelCatalog.first(),
                                    response =
                                        buildJsonObject {
                                            put(
                                                "content",
                                                buildJsonArray {
                                                    add(
                                                        buildJsonObject {
                                                            put("type", "text")
                                                            put("text", operation.string("text"))
                                                        },
                                                    )
                                                },
                                            )
                                            put("stopReason", operation.optionalString("stopReason") ?: "stop")
                                        },
                                    timestamp = 1000L + index,
                                ),
                            )
                        }
                    operation.optionalString("as")?.let { aliases[it] = id }
                }

                "appendThinkingLevel" -> {
                    val id = manager.appendThinkingLevelChange(operation.string("thinkingLevel"))
                    operation.optionalString("as")?.let { aliases[it] = id }
                }

                "appendModelChange" -> {
                    val id = manager.appendModelChange(operation.string("provider"), operation.string("modelId"))
                    operation.optionalString("as")?.let { aliases[it] = id }
                }

                "appendCompaction" -> {
                    val id =
                        manager.appendCompaction(
                            summary = operation.string("summary"),
                            firstKeptEntryId = requireNotNull(resolveAlias(operation.string("firstKept"))),
                            tokensBefore = operation["tokensBefore"]?.jsonPrimitive?.int ?: 1000,
                        )
                    operation.optionalString("as")?.let { aliases[it] = id }
                }

                "appendCustomMessage" -> {
                    val id =
                        manager.appendCustomMessageEntry(
                            customType = operation.string("customType"),
                            content = operation["content"]!!.toUserMessageContent(),
                            display = operation.boolean("display"),
                        )
                    operation.optionalString("as")?.let { aliases[it] = id }
                }

                "appendLabel" -> {
                    val id =
                        manager.appendLabelChange(
                            targetId = requireNotNull(resolveAlias(operation.string("target"))),
                            label = operation.optionalString("label"),
                        )
                    operation.optionalString("as")?.let { aliases[it] = id }
                }

                "appendSessionInfo" -> {
                    val id = manager.appendSessionInfo(operation.string("name"))
                    operation.optionalString("as")?.let { aliases[it] = id }
                }

                "branch" -> manager.branch(requireNotNull(resolveAlias(operation.string("target"))))
                "resetLeaf" -> manager.resetLeaf()
                "branchWithSummary" -> {
                    val id =
                        manager.branchWithSummary(
                            branchFromId = resolveAlias(operation.optionalString("parent")),
                            summary = operation.string("summary"),
                        )
                    operation.optionalString("as")?.let { aliases[it] = id }
                }

                else -> error("Unsupported session-manager parity operation: ${operation.string("op")}")
            }
        }

        val normalizer = CodingNormalizer()
        val entries = manager.getEntries()
        val byId = entries.associateBy { it.id }
        val contexts =
            buildJsonObject {
                scenario["inspect"]!!
                    .jsonObject["contexts"]!!
                    .jsonObject
                    .forEach { (name, leafAlias) ->
                        put(
                            name,
                            normalizer.normalizeSessionContext(
                                buildSessionContext(entries, aliases[leafAlias.jsonPrimitive.content], byId),
                            ),
                        )
                    }
            }
        val branches =
            buildJsonObject {
                scenario["inspect"]!!
                    .jsonObject["branches"]!!
                    .jsonObject
                    .forEach { (name, leafAlias) ->
                        put(
                            name,
                            buildJsonArray {
                                manager.getBranch(aliases[leafAlias.jsonPrimitive.content]).forEach {
                                    add(normalizer.normalizeSessionEntry(it))
                                }
                            },
                        )
                    }
            }
        return buildJsonObject {
            put("scenarioId", scenario.string("id"))
            put("suite", scenario.string("suite"))
            put(
                "entries",
                buildJsonArray {
                    entries.forEach { add(normalizer.normalizeSessionEntry(it)) }
                },
            )
            put(
                "tree",
                buildJsonArray {
                    manager.getTree().forEach { add(normalizer.normalizeTreeNode(it)) }
                },
            )
            put("leafId", normalizer.entryRef(manager.getLeafId()))
            put("contexts", contexts)
            put("branches", branches)
        }
    }

    private fun runAgentSessionScenario(scenario: JsonObject): JsonElement {
        val tempDir = createWorkingDir(scenario.string("id"))
        val provider =
            FauxCodingProvider(scenario.modelCatalog(), scenario["responses"]?.jsonArray ?: JsonArray(emptyList()), scenario.tokenSize())
        registerApiProvider(provider)

        return try {
            val env = createEnvironment(tempDir, scenario, provider)
            val sessionManager =
                if (scenario["persisted"]?.jsonPrimitive?.contentOrNull == "true") {
                    SessionManager.create(env.cwd.toString(), tempDir.resolve("sessions").toString())
                } else {
                    SessionManager.inMemory()
                }
            val session =
                runBlocking {
                    createAgentSession(
                        CreateAgentSessionOptions(
                            cwd = env.cwd.toString(),
                            agentDir = env.agentDir.toString(),
                            authStorage = env.authStorage,
                            modelRegistry = env.modelRegistry,
                            model = env.resolveModel(scenario.optionalString("initialModel")),
                            thinkingLevel = scenario["initialThinkingLevel"]?.jsonPrimitive?.contentOrNull?.toThinkingLevel(),
                            scopedModels = env.resolveScopedModels(scenario["scopedModels"]?.jsonArray),
                            resourceLoader = env.resourceLoader,
                            sessionManager = sessionManager,
                            settingsManager = env.settingsManager,
                        ),
                    ).session
                }

            val rawEvents = mutableListOf<AgentSessionEvent>()
            session.subscribe { event -> rawEvents += event }
            val operationResults = mutableListOf<Pair<String, Any?>>()

            scenario["operations"]!!.jsonArray.forEach { element ->
                val operation = element.jsonObject
                when (operation.string("op")) {
                    "prompt" -> {
                        runBlocking { session.prompt(operation.string("text")) }
                        operationResults += operation.string("op") to null
                    }
                    "sendCustomMessage" -> {
                        runBlocking {
                            session.sendCustomMessage(
                                message =
                                    CustomMessage(
                                        customType = operation.string("customType"),
                                        content = operation["content"]!!.toUserMessageContent(),
                                        display = operation.boolean("display"),
                                        details = null,
                                        timestamp = System.currentTimeMillis(),
                                    ),
                                triggerTurn = operation["triggerTurn"]?.jsonPrimitive?.contentOrNull == "true",
                            )
                        }
                        operationResults += operation.string("op") to null
                    }
                    "cycleThinkingLevel" -> operationResults += operation.string("op") to session.cycleThinkingLevel()
                    "cycleModel" -> {
                        val result = runBlocking { session.cycleModel(operation.optionalString("direction") ?: "forward") }
                        operationResults += operation.string("op") to result
                    }
                    "setSessionName" -> {
                        session.setSessionName(operation.string("name"))
                        operationResults += operation.string("op") to null
                    }
                    "navigateTree" -> {
                        val targetId = resolveSelector(session.sessionManager, operation["selector"]!!.jsonObject)
                        val result =
                            runBlocking {
                                session.navigateTree(
                                    targetId = targetId,
                                    options =
                                        pi.coding.agent.core.TreeNavigationOptions(
                                            summarize = operation["summarize"]?.jsonPrimitive?.contentOrNull == "true",
                                            customInstructions = operation.optionalString("customInstructions"),
                                            replaceInstructions = operation["replaceInstructions"]?.jsonPrimitive?.contentOrNull == "true",
                                            label = operation.optionalString("label"),
                                        ),
                                )
                            }
                        operationResults += operation.string("op") to result
                    }
                    "compact" -> {
                        val result = runBlocking { session.compact(operation.optionalString("customInstructions")) }
                        operationResults += operation.string("op") to result
                    }
                    else -> error("Unsupported agent-session parity operation: ${operation.string("op")}")
                }
            }

            val normalizer = CodingNormalizer()
            buildJsonObject {
                put("scenarioId", scenario.string("id"))
                put("suite", scenario.string("suite"))
                put("events", JsonArray(normalizeSessionEvents(rawEvents, normalizer)))
                put(
                    "operationResults",
                    buildJsonArray {
                        operationResults.forEach { (op, result) ->
                            add(normalizeOperationResult(op, result, normalizer))
                        }
                    },
                )
                put("finalState", normalizer.normalizeSessionSnapshot(session))
            }
        } finally {
            if (tempDir.exists()) {
                tempDir.toFile().deleteRecursively()
            }
        }
    }

    private fun runRuntimeScenario(scenario: JsonObject): JsonElement {
        val tempDir = createWorkingDir(scenario.string("id"))
        val provider =
            FauxCodingProvider(scenario.modelCatalog(), scenario["responses"]?.jsonArray ?: JsonArray(emptyList()), scenario.tokenSize())
        registerApiProvider(provider)

        return try {
            val env = createEnvironment(tempDir, scenario, provider)
            val capturedFiles = linkedMapOf<String, String>()
            val createRuntime: CreateAgentSessionRuntimeFactory =
                { cwd: String, agentDir: String, sessionManager: SessionManager, _: SessionStartEvent? ->
                    val replacementEnv =
                        createEnvironment(
                            tempDir.resolve("runtime-${sessionManager.getSessionId()}"),
                            scenario,
                            provider,
                            cwdOverride = cwd,
                            agentDirOverride = agentDir,
                        )
                    val session =
                        createAgentSession(
                            CreateAgentSessionOptions(
                                cwd = cwd,
                                agentDir = agentDir,
                                authStorage = replacementEnv.authStorage,
                                modelRegistry = replacementEnv.modelRegistry,
                                model = replacementEnv.resolveModel(scenario.optionalString("initialModel")),
                                thinkingLevel = scenario["initialThinkingLevel"]?.jsonPrimitive?.contentOrNull?.toThinkingLevel(),
                                scopedModels = replacementEnv.resolveScopedModels(scenario["scopedModels"]?.jsonArray),
                                resourceLoader = replacementEnv.resourceLoader,
                                sessionManager = sessionManager,
                                settingsManager = replacementEnv.settingsManager,
                            ),
                        ).session
                    CreateAgentSessionRuntimeResult(
                        session = session,
                        services =
                            pi.coding.agent.core.AgentSessionServices(
                                cwd = cwd,
                                agentDir = agentDir,
                                authStorage = replacementEnv.authStorage,
                                settingsManager = replacementEnv.settingsManager,
                                modelRegistry = replacementEnv.modelRegistry,
                                resourceLoader = replacementEnv.resourceLoader,
                            ),
                        diagnostics = emptyList(),
                    )
                }
            val runtime =
                runBlocking {
                    createAgentSessionRuntime(
                        createRuntime = createRuntime,
                        cwd = env.cwd.toString(),
                        agentDir = env.agentDir.toString(),
                        sessionManager = SessionManager.create(env.cwd.toString(), tempDir.resolve("sessions").toString()),
                        sessionStartEvent = SessionStartEvent(reason = pi.coding.agent.core.SessionStartReason.STARTUP),
                    )
                }

            val operationResults = mutableListOf<Pair<String, JsonObject>>()
            scenario["operations"]!!.jsonArray.forEach { element ->
                val operation = element.jsonObject
                when (operation.string("op")) {
                    "prompt" -> {
                        runBlocking { runtime.session.prompt(operation.string("text")) }
                        operationResults += operation.string("op") to buildJsonObject { put("op", operation.string("op")) }
                    }
                    "captureSessionFile" -> {
                        val file = requireNotNull(runtime.session.sessionFile)
                        capturedFiles[operation.string("as")] = file
                        operationResults +=
                            operation.string("op") to
                            buildJsonObject {
                                put("op", operation.string("op"))
                                put("sessionFile", file)
                            }
                    }
                    "newSession" -> {
                        val result = runBlocking { runtime.newSession() }
                        operationResults +=
                            operation.string("op") to
                            buildJsonObject {
                                put("op", operation.string("op"))
                                put("result", buildCancelResult(result.first))
                            }
                    }
                    "switchSession" -> {
                        val result = runBlocking { runtime.switchSession(requireNotNull(capturedFiles[operation.string("sessionFile")])) }
                        operationResults +=
                            operation.string("op") to
                            buildJsonObject {
                                put("op", operation.string("op"))
                                put("result", buildCancelResult(result.first))
                            }
                    }
                    "fork" -> {
                        val selector = operation["selector"]!!.jsonObject
                        val entryId = resolveSelector(runtime.session.sessionManager, selector)
                        val result = runBlocking { runtime.fork(entryId) }
                        operationResults +=
                            operation.string("op") to
                            buildJsonObject {
                                put("op", operation.string("op"))
                                put(
                                    "result",
                                    buildJsonObject {
                                        put("cancelled", result.first)
                                        put("selectedText", result.second?.let(::JsonPrimitive) ?: JsonNull)
                                    },
                                )
                            }
                    }
                    "importFromJsonl" -> {
                        val result = runBlocking { runtime.importFromJsonl(requireNotNull(capturedFiles[operation.string("sessionFile")])) }
                        operationResults +=
                            operation.string("op") to
                            buildJsonObject {
                                put("op", operation.string("op"))
                                put("result", buildCancelResult(result.first))
                            }
                    }
                    else -> error("Unsupported runtime parity operation: ${operation.string("op")}")
                }
            }

            val normalizer = CodingNormalizer()
            buildJsonObject {
                put("scenarioId", scenario.string("id"))
                put("suite", scenario.string("suite"))
                put(
                    "operationResults",
                    buildJsonArray {
                        operationResults.forEach { (_, result) ->
                            add(normalizeRuntimeOperationResult(result, normalizer))
                        }
                    },
                )
                put(
                    "runtime",
                    buildJsonObject {
                        put("cwd", normalizer.pathRef(runtime.cwd))
                        put("diagnostics", buildJsonArray {})
                        put("modelFallbackMessage", JsonNull)
                        put("session", normalizer.normalizeSessionSnapshot(runtime.session))
                    },
                )
            }
        } finally {
            if (tempDir.exists()) {
                tempDir.toFile().deleteRecursively()
            }
        }
    }

    private fun normalizeRuntimeOperationResult(
        result: JsonObject,
        normalizer: CodingNormalizer,
    ): JsonObject {
        val op = result.string("op")
        return when (op) {
            "captureSessionFile" ->
                buildJsonObject {
                    put("op", op)
                    put("sessionFile", normalizer.pathRef(result.string("sessionFile")))
                }
            else ->
                if ("result" in result) {
                    buildJsonObject {
                        put("op", op)
                        put("result", result["result"]!!)
                    }
                } else {
                    result
                }
        }
    }

    private fun normalizeOperationResult(
        op: String,
        result: Any?,
        normalizer: CodingNormalizer,
    ): JsonObject =
        when (op) {
            "cycleThinkingLevel" ->
                buildJsonObject {
                    put("op", op)
                    put("level", (result as? AgentThinkingLevel)?.name?.lowercase()?.let(::JsonPrimitive) ?: JsonNull)
                }
            "cycleModel" ->
                buildJsonObject {
                    put("op", op)
                    put(
                        "result",
                        if (result == null) {
                            JsonNull
                        } else {
                            val value = result as pi.coding.agent.core.ModelCycleResult
                            buildJsonObject {
                                put(
                                    "model",
                                    buildJsonObject {
                                        put("provider", value.model.provider)
                                        put("modelId", value.model.id)
                                    },
                                )
                                put("thinkingLevel", value.thinkingLevel.name.lowercase())
                                put("isScoped", value.isScoped)
                            }
                        },
                    )
                }
            "navigateTree" ->
                buildJsonObject {
                    put("op", op)
                    put("result", normalizer.normalizeTreeNavigationResult(result as pi.coding.agent.core.TreeNavigationResult))
                }
            "compact" ->
                buildJsonObject {
                    put("op", op)
                    put(
                        "result",
                        normalizer.normalizeCompactionResult(result as pi.coding.agent.core.compaction.CompactionResult<JsonElement>),
                    )
                }
            else ->
                buildJsonObject {
                    put("op", op)
                }
        }

    private fun buildCancelResult(cancelled: Boolean): JsonObject =
        buildJsonObject {
            put("cancelled", cancelled)
        }

    private fun resolveSelector(
        sessionManager: ReadonlySessionManager,
        selector: JsonObject,
    ): String {
        selector.optionalString("entryId")?.let { return it }
        val role = selector.optionalString("role")
        if (role != null) {
            val index = selector["index"]?.jsonPrimitive?.int ?: 0
            val target =
                sessionManager
                    .getEntries()
                    .filterIsInstance<SessionMessageEntry>()
                    .filter { it.message.role == role }
                    .getOrNull(index)
            requireNotNull(target) { "Could not resolve selector $selector" }
            return target.id
        }
        if (selector.optionalString("type") == "custom_message") {
            val index = selector["index"]?.jsonPrimitive?.int ?: 0
            val target =
                sessionManager
                    .getEntries()
                    .filterIsInstance<CustomMessageEntry>()
                    .getOrNull(index)
            requireNotNull(target) { "Could not resolve selector $selector" }
            return target.id
        }
        error("Unsupported selector $selector")
    }

    private fun normalizeSessionEvents(
        events: List<AgentSessionEvent>,
        normalizer: CodingNormalizer,
    ): List<JsonElement> {
        val agentEvents = events.mapNotNull { (it as? AgentSessionEvent.Agent)?.event }
        val normalizedAgentEvents = normalizeAgentEventSequence(agentEvents, "pi-coding-agent-core")
        var agentIndex = 0

        return events.map { event ->
            when (event) {
                is AgentSessionEvent.Agent -> normalizedAgentEvents[agentIndex++]
                is AgentSessionEvent.QueueUpdate ->
                    buildJsonObject {
                        put("type", "queue_update")
                        put(
                            "steering",
                            buildJsonArray {
                                event.steering.forEach { add(JsonPrimitive(it)) }
                            },
                        )
                        put(
                            "followUp",
                            buildJsonArray {
                                event.followUp.forEach { add(JsonPrimitive(it)) }
                            },
                        )
                    }
                is AgentSessionEvent.CompactionStart ->
                    buildJsonObject {
                        put("type", "compaction_start")
                        put("reason", event.reason.name.lowercase())
                    }
                is AgentSessionEvent.CompactionEnd ->
                    buildJsonObject {
                        put("type", "compaction_end")
                        put("reason", event.reason.name.lowercase())
                        put("aborted", event.aborted)
                        put("willRetry", event.willRetry)
                        event.errorMessage?.let { put("errorMessage", it) }
                        event.result?.let { put("result", normalizer.normalizeCompactionResult(it)) }
                    }
            }
        }
    }

    private fun normalizeAgentEventSequence(
        rawEvents: List<AgentEvent>,
        suite: String,
    ): List<JsonElement> {
        var assistantPartial: JsonObject? = null
        val normalized = mutableListOf<JsonElement>()

        rawEvents.forEach { event ->
            when (event) {
                AgentEvent.AgentStart,
                AgentEvent.TurnStart,
                ->
                    normalized +=
                        buildJsonObject {
                            put("type", if (event is AgentEvent.AgentStart) "agent_start" else "turn_start")
                        }
                is AgentEvent.AgentEnd ->
                    normalized +=
                        buildJsonObject {
                            put("type", "agent_end")
                            put(
                                "messages",
                                buildJsonArray {
                                    event.messages.forEach { add(normalizeMessage(it, suite)) }
                                },
                            )
                        }
                is AgentEvent.TurnEnd ->
                    normalized +=
                        buildJsonObject {
                            put("type", "turn_end")
                            put("message", normalizeMessage(event.message, suite))
                            put(
                                "toolResults",
                                buildJsonArray {
                                    event.toolResults.forEach { add(normalizeMessage(it, suite)) }
                                },
                            )
                        }
                is AgentEvent.MessageStart ->
                    normalized +=
                        buildJsonObject {
                            put("type", "message_start")
                            if (event.message is AssistantMessage) {
                                assistantPartial = baseAssistantMessage(event.message as AssistantMessage, suite)
                                put("message", assistantPartial!!)
                            } else {
                                put("message", normalizeMessage(event.message, suite))
                            }
                        }
                is AgentEvent.MessageUpdate -> {
                    val normalizedEvent = normalizeAssistantMessageEvent(assistantPartial, event.assistantMessageEvent, suite)
                    assistantPartial = normalizedEvent.first
                    normalized +=
                        buildJsonObject {
                            put("type", "message_update")
                            put("message", assistantPartial ?: JsonNull)
                            put("assistantMessageEvent", normalizedEvent.second)
                        }
                }
                is AgentEvent.MessageEnd -> {
                    normalized +=
                        buildJsonObject {
                            put("type", "message_end")
                            put("message", normalizeMessage(event.message, suite))
                        }
                    if (event.message is AssistantMessage) {
                        assistantPartial = null
                    }
                }
                is AgentEvent.ToolExecutionStart ->
                    normalized +=
                        buildJsonObject {
                            put("type", "tool_execution_start")
                            put("toolCallId", event.toolCallId)
                            put("toolName", event.toolName)
                            put("args", event.args)
                        }
                is AgentEvent.ToolExecutionUpdate ->
                    normalized +=
                        buildJsonObject {
                            put("type", "tool_execution_update")
                            put("toolCallId", event.toolCallId)
                            put("toolName", event.toolName)
                            put("args", event.args)
                            put("partialResult", normalizeToolResult(event.partialResult))
                        }
                is AgentEvent.ToolExecutionEnd ->
                    normalized +=
                        buildJsonObject {
                            put("type", "tool_execution_end")
                            put("toolCallId", event.toolCallId)
                            put("toolName", event.toolName)
                            put("result", normalizeToolResult(event.result))
                            put("isError", event.isError)
                        }
            }
        }

        return normalized
    }

    private fun normalizeToolResult(result: pi.agent.core.AgentToolResult<*>): JsonObject =
        buildJsonObject {
            put(
                "content",
                buildJsonArray {
                    result.content.forEach { add(normalizeContentBlock(it)) }
                },
            )
            if (result.details is JsonElement) {
                put("details", result.details as JsonElement)
            }
        }

    private fun baseAssistantMessage(
        message: AssistantMessage,
        suite: String,
    ): JsonObject =
        normalizeMessage(message, suite)
            .jsonObject
            .let { original ->
                buildJsonObject {
                    original.forEach { (key, value) ->
                        if (key == "content") {
                            put("content", buildJsonArray {})
                        } else {
                            put(key, value)
                        }
                    }
                }
            }

    private fun normalizeAssistantMessageEvent(
        partial: JsonObject?,
        event: AssistantMessageEvent,
        suite: String,
    ): Pair<JsonObject?, JsonObject> =
        when (event) {
            is AssistantMessageEvent.Start -> {
                val next = baseAssistantMessage(event.partial, suite)
                next to
                    buildJsonObject {
                        put("type", "start")
                        put("partial", next)
                    }
            }
            is AssistantMessageEvent.TextStart -> {
                val next =
                    appendAssistantContent(
                        partial,
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
                val next = updateAssistantText(partial, event.contentIndex, event.delta)
                next to
                    buildJsonObject {
                        put("type", "text_delta")
                        put("contentIndex", event.contentIndex)
                        put("delta", event.delta)
                        put("partial", next)
                    }
            }
            is AssistantMessageEvent.TextEnd ->
                partial to
                    buildJsonObject {
                        put("type", "text_end")
                        put("contentIndex", event.contentIndex)
                        put("content", event.content)
                        put("partial", partial ?: JsonNull)
                    }
            is AssistantMessageEvent.ThinkingStart -> {
                val next =
                    appendAssistantContent(
                        partial,
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
                val next = updateAssistantThinking(partial, event.contentIndex, event.delta)
                next to
                    buildJsonObject {
                        put("type", "thinking_delta")
                        put("contentIndex", event.contentIndex)
                        put("delta", event.delta)
                        put("partial", next)
                    }
            }
            is AssistantMessageEvent.ThinkingEnd ->
                partial to
                    buildJsonObject {
                        put("type", "thinking_end")
                        put("contentIndex", event.contentIndex)
                        put("content", event.content)
                        put("partial", partial ?: JsonNull)
                    }
            is AssistantMessageEvent.ToolCallStart -> {
                val partialMessage = event.partial.content[event.contentIndex] as ToolCall
                val next =
                    appendAssistantContent(
                        partial,
                        buildJsonObject {
                            put("type", "toolCall")
                            put("id", partialMessage.id)
                            put("name", partialMessage.name)
                            put("arguments", buildJsonObject {})
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
                partial to
                    buildJsonObject {
                        put("type", "toolcall_delta")
                        put("contentIndex", event.contentIndex)
                        put("delta", event.delta)
                        put("partial", partial ?: JsonNull)
                    }
            is AssistantMessageEvent.ToolCallEnd -> {
                val next = replaceAssistantContent(partial, event.contentIndex, normalizeContentBlock(event.toolCall))
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
                        put("reason", event.reason.name.lowercase())
                        put("message", normalizeMessage(event.message, suite))
                    }
            is AssistantMessageEvent.Error ->
                partial to
                    buildJsonObject {
                        put("type", "error")
                        put("reason", event.reason.name.lowercase())
                        put("error", normalizeMessage(event.error, suite))
                    }
        }

    private fun appendAssistantContent(
        partial: JsonObject?,
        block: JsonObject,
    ): JsonObject {
        val content = partial?.get("content")?.jsonArray.orEmpty()
        return partial
            ?.toMutableMap()
            ?.toMap()
            ?.let { original ->
                buildJsonObject {
                    original.forEach { (key, value) ->
                        if (key == "content") {
                            put(
                                "content",
                                buildJsonArray {
                                    content.forEach { add(it) }
                                    add(block)
                                },
                            )
                        } else {
                            put(key, value)
                        }
                    }
                }
            } ?: buildJsonObject { }
    }

    private fun updateAssistantText(
        partial: JsonObject?,
        index: Int,
        delta: String,
    ): JsonObject = updateAssistantContentField(partial, index, "text", delta)

    private fun updateAssistantThinking(
        partial: JsonObject?,
        index: Int,
        delta: String,
    ): JsonObject = updateAssistantContentField(partial, index, "thinking", delta)

    private fun updateAssistantContentField(
        partial: JsonObject?,
        index: Int,
        field: String,
        delta: String,
    ): JsonObject {
        val content = partial?.get("content")?.jsonArray.orEmpty()
        return buildJsonObject {
            partial?.forEach { (key, value) ->
                if (key == "content") {
                    put(
                        "content",
                        buildJsonArray {
                            content.forEachIndexed { contentIndex, block ->
                                if (contentIndex == index) {
                                    val original = block.jsonObject
                                    add(
                                        buildJsonObject {
                                            original.forEach { (originalKey, originalValue) ->
                                                if (originalKey == field) {
                                                    put(field, originalValue.jsonPrimitive.content + delta)
                                                } else {
                                                    put(originalKey, originalValue)
                                                }
                                            }
                                        },
                                    )
                                } else {
                                    add(block)
                                }
                            }
                        },
                    )
                } else {
                    put(key, value)
                }
            }
        }
    }

    private fun replaceAssistantContent(
        partial: JsonObject?,
        index: Int,
        replacement: JsonObject,
    ): JsonObject {
        val content = partial?.get("content")?.jsonArray.orEmpty()
        return buildJsonObject {
            partial?.forEach { (key, value) ->
                if (key == "content") {
                    put(
                        "content",
                        buildJsonArray {
                            content.forEachIndexed { contentIndex, block ->
                                add(if (contentIndex == index) replacement else block)
                            }
                        },
                    )
                } else {
                    put(key, value)
                }
            }
        }
    }

    private fun normalizeMessage(
        message: AgentMessage,
        suite: String,
    ): JsonObject =
        when (message) {
            is UserMessage ->
                buildJsonObject {
                    put("role", "user")
                    put("content", normalizeUserContent(message.content))
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
                            message.content.forEach { add(normalizeContentBlock(it)) }
                        },
                    )
                    message.details?.let { put("details", it) }
                }
            is AssistantMessage ->
                buildJsonObject {
                    put("role", "assistant")
                    put("stopReason", message.stopReason.name.lowercase())
                    put(
                        "content",
                        buildJsonArray {
                            message.content.forEach { add(normalizeContentBlock(it)) }
                        },
                    )
                    message.errorMessage?.let { put("errorMessage", it) }
                }
            is CustomMessage ->
                buildJsonObject {
                    put("role", "custom")
                    put("customType", message.customType)
                    put("content", normalizeUserContent(message.content))
                    put("display", message.display)
                    message.details?.let { put("details", it) }
                }
            is BranchSummaryMessage ->
                buildJsonObject {
                    put("role", "branchSummary")
                    put("summary", message.summary)
                    put("fromId", message.fromId)
                }
            is CompactionSummaryMessage ->
                buildJsonObject {
                    put("role", "compactionSummary")
                    put("summary", message.summary)
                }
            is BashExecutionMessage ->
                buildJsonObject {
                    put("role", "bashExecution")
                    put("command", message.command)
                    put("output", message.output)
                    message.exitCode?.let { put("exitCode", it) }
                    put("cancelled", message.cancelled)
                    put("truncated", message.truncated)
                    message.fullOutputPath?.let { put("fullOutputPath", it) }
                    put("excludeFromContext", message.excludeFromContext)
                }
            else -> error("Unsupported coding-agent message type for $suite: ${message::class.simpleName}")
        }

    private fun normalizeContentBlock(block: Any): JsonObject =
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

    private fun normalizeUserContent(content: UserMessageContent): JsonElement =
        when (content) {
            is UserMessageContent.Text -> JsonPrimitive(content.value)
            is UserMessageContent.Structured ->
                buildJsonArray {
                    content.parts.forEach { part ->
                        add(
                            when (part) {
                                is TextContent ->
                                    buildJsonObject {
                                        put("type", "text")
                                        put("text", part.text)
                                    }
                                is ImageContent ->
                                    buildJsonObject {
                                        put("type", "image")
                                        put("data", part.data)
                                        put("mimeType", part.mimeType)
                                    }
                                else -> error("Unsupported user content part: ${part::class.simpleName}")
                            },
                        )
                    }
                }
        }

    private fun createWorkingDir(id: String): Path {
        val dir = parityRootDir().resolve("build/parity-working/$id-kt")
        if (dir.exists()) {
            dir.toFile().deleteRecursively()
        }
        dir.createDirectories()
        return dir
    }

    private fun createEnvironment(
        tempDir: Path,
        scenario: JsonObject,
        provider: FauxCodingProvider,
        cwdOverride: String? = null,
        agentDirOverride: String? = null,
    ): ScenarioEnvironment {
        val cwd = Path.of(cwdOverride ?: tempDir.resolve("cwd").toString())
        val agentDir = Path.of(agentDirOverride ?: tempDir.resolve("agent").toString())
        cwd.createDirectories()
        agentDir.createDirectories()

        val authStorage = AuthStorage.create(tempDir.resolve("auth.json").toString())
        val modelRegistry = ModelRegistry.create(authStorage)
        val models = scenario.modelCatalog()
        authStorage.setApiKey(models.first().provider, "faux-key")
        modelRegistry.registerProvider(models.first().provider, models)

        val settingsManager = SettingsManager.create(cwd.toString(), agentDir.toString())
        applyScenarioSettings(settingsManager, scenario["settings"]?.jsonObject)

        val resourceLoader: ResourceLoader =
            DefaultResourceLoader(
                DefaultResourceLoaderOptions(
                    cwd = cwd.toString(),
                    agentDir = agentDir.toString(),
                    systemPrompt = scenario.optionalString("systemPrompt") ?: "You are a parity test assistant.",
                ),
            )

        return ScenarioEnvironment(
            cwd = cwd,
            agentDir = agentDir,
            authStorage = authStorage,
            modelRegistry = modelRegistry,
            settingsManager = settingsManager,
            resourceLoader = resourceLoader,
            resolveModel = { requestedId ->
                models.firstOrNull { it.id == (requestedId ?: models.first().id) }
                    ?: error("Unknown model id: $requestedId")
            },
            resolveScopedModels = { scopes ->
                scopes
                    ?.map { scope ->
                        ModelScope(
                            model =
                                models.firstOrNull { it.id == scope.jsonObject.string("model") }
                                    ?: error("Unknown scoped model: ${scope.jsonObject.string("model")}"),
                            thinkingLevel = scope.jsonObject.optionalString("thinkingLevel")?.toThinkingLevel(),
                        )
                    }.orEmpty()
            },
        )
    }

    private fun applyScenarioSettings(
        settingsManager: SettingsManager,
        settings: JsonObject?,
    ) {
        settings ?: return
        settings["compaction"]?.jsonObject?.let { compaction ->
            settingsManager.setCompactionSettings(
                CompactionSettings(
                    enabled = compaction["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
                    reserveTokens = compaction["reserveTokens"]?.jsonPrimitive?.int ?: 16384,
                    keepRecentTokens = compaction["keepRecentTokens"]?.jsonPrimitive?.int ?: 20000,
                ),
            )
        }
        settings["branchSummary"]?.jsonObject?.let { branchSummary ->
            settingsManager.setBranchSummarySettings(
                BranchSummarySettings(
                    reserveTokens = branchSummary["reserveTokens"]?.jsonPrimitive?.int ?: 16384,
                ),
            )
        }
    }

    private fun JsonObject.modelCatalog(): List<Model<String>> =
        scenarioModels(this)
            .map { model ->
                Model(
                    id = model.string("id"),
                    name = model.string("name"),
                    api = model.string("api"),
                    provider = model.string("provider"),
                    baseUrl = "https://example.invalid",
                    reasoning = model.boolean("reasoning"),
                    input = model["input"]!!.jsonArray.map { InputModality.valueOf(it.jsonPrimitive.content.uppercase()) }.toSet(),
                    cost = ModelCost(0.0, 0.0, 0.0, 0.0),
                    contextWindow = model["contextWindow"]!!.jsonPrimitive.int,
                    maxTokens = model["maxTokens"]!!.jsonPrimitive.int,
                )
            }

    private fun scenarioModels(scenario: JsonObject): List<JsonObject> =
        (scenario["modelCatalog"]?.jsonArray ?: scenario["model"]?.let { JsonArray(listOf(it)) } ?: JsonArray(emptyList()))
            .map { it.jsonObject }

    private fun JsonElement.toUserMessageContent(): UserMessageContent =
        when (this) {
            is JsonPrimitive -> UserMessageContent.Text(content)
            else ->
                UserMessageContent.Structured(
                    jsonArray.map { block ->
                        val value = block.jsonObject
                        when (value.string("type")) {
                            "text" -> TextContent(value.string("text"))
                            "image" ->
                                ImageContent(
                                    data = value.string("data"),
                                    mimeType = value.string("mimeType"),
                                )
                            else -> error("Unsupported user content block type: ${value.string("type")}")
                        }
                    },
                )
        }

    private fun createAssistantMessage(
        model: Model<String>,
        response: JsonObject,
        timestamp: Long,
    ): AssistantMessage =
        AssistantMessage(
            content =
                response["content"]!!
                    .jsonArray
                    .map { block ->
                        val value = block.jsonObject
                        when (value.string("type")) {
                            "text" -> TextContent(value.string("text"))
                            "thinking" -> ThinkingContent(value.string("thinking"))
                            "toolCall" -> ToolCall(value.string("id"), value.string("name"), value["arguments"]!!.jsonObject)
                            else -> error("Unsupported assistant block type: ${value.string("type")}")
                        }
                    }.toMutableList(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            usage = Usage(),
            stopReason = response["stopReason"]!!.jsonPrimitive.content.toStopReason(),
            errorMessage = response.optionalString("errorMessage"),
            timestamp = timestamp,
            responseId = response.optionalString("responseId"),
        )

    private fun JsonObject.string(name: String): String = this[name]!!.jsonPrimitive.content

    private fun JsonObject.optionalString(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.boolean(name: String): Boolean = this[name]!!.jsonPrimitive.content.toBooleanStrict()

    private fun parityRootDir(): Path = Path.of(System.getProperty("parity.rootDir") ?: error("Missing parity.rootDir system property"))
}

private data class ScenarioEnvironment(
    val cwd: Path,
    val agentDir: Path,
    val authStorage: AuthStorage,
    val modelRegistry: ModelRegistry,
    val settingsManager: SettingsManager,
    val resourceLoader: ResourceLoader,
    val resolveModel: (String?) -> Model<String>,
    val resolveScopedModels: (JsonArray?) -> List<ModelScope>,
)

private class CodingNormalizer {
    private val entryIds: LinkedHashMap<String, String> = linkedMapOf()
    private val sessionIds: LinkedHashMap<String, String> = linkedMapOf()
    private val paths: LinkedHashMap<String, String> = linkedMapOf()

    fun entryRef(value: String?): JsonElement =
        when (value) {
            null -> JsonNull
            "root" -> JsonPrimitive("root")
            else -> JsonPrimitive(entryIds.getOrPut(value) { "entry-${entryIds.size + 1}" })
        }

    fun pathRef(value: String?): JsonElement = value?.let { JsonPrimitive(paths.getOrPut(it) { "path-${paths.size + 1}" }) } ?: JsonNull

    private fun sessionRef(value: String?): JsonElement =
        value?.let { JsonPrimitive(sessionIds.getOrPut(it) { "session-${sessionIds.size + 1}" }) } ?: JsonNull

    fun normalizeSessionContext(context: pi.coding.agent.core.SessionContext): JsonObject =
        buildJsonObject {
            put(
                "messages",
                buildJsonArray {
                    context.messages.forEach { add(normalizeMessage(it)) }
                },
            )
            put("thinkingLevel", context.thinkingLevel)
            put(
                "model",
                context.model?.let {
                    buildJsonObject {
                        put("provider", it.provider)
                        put("modelId", it.modelId)
                    }
                } ?: JsonNull,
            )
        }

    fun normalizeTreeNavigationResult(result: pi.coding.agent.core.TreeNavigationResult): JsonObject =
        buildJsonObject {
            put("cancelled", result.cancelled)
            if (result.aborted) put("aborted", true)
            result.editorText?.let { put("editorText", it) }
            result.summaryEntry?.let { put("summaryEntry", normalizeSessionEntry(it)) }
        }

    fun normalizeCompactionResult(result: pi.coding.agent.core.compaction.CompactionResult<JsonElement>): JsonObject =
        buildJsonObject {
            put("summary", result.summary)
            put("firstKeptEntryId", entryRef(result.firstKeptEntryId))
            result.details?.let { put("details", it) }
        }

    fun normalizeSessionSnapshot(session: AgentSession): JsonObject =
        buildJsonObject {
            put("sessionId", sessionRef(session.sessionId))
            if (session.sessionFile != null) {
                put("sessionFile", pathRef(session.sessionFile))
            }
            put("sessionName", session.sessionName?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "model",
                buildJsonObject {
                    put("provider", session.model.provider)
                    put("modelId", session.model.id)
                },
            )
            put("thinkingLevel", session.thinkingLevel.name.lowercase())
            put("autoCompactionEnabled", session.autoCompactionEnabledValue)
            put("isCompacting", session.isCompacting)
            put(
                "steeringMode",
                session.steeringMode.name
                    .lowercase()
                    .replace('_', '-'),
            )
            put(
                "followUpMode",
                session.followUpMode.name
                    .lowercase()
                    .replace('_', '-'),
            )
            put(
                "steeringMessages",
                buildJsonArray {
                    session.getSteeringMessages().forEach { add(JsonPrimitive(it)) }
                },
            )
            put(
                "followUpMessages",
                buildJsonArray {
                    session.getFollowUpMessages().forEach { add(JsonPrimitive(it)) }
                },
            )
            put(
                "messages",
                buildJsonArray {
                    session.messages.forEach { add(normalizeMessage(it)) }
                },
            )
            put(
                "entries",
                buildJsonArray {
                    session.sessionManager.getEntries().forEach { add(normalizeSessionEntry(it)) }
                },
            )
            put(
                "tree",
                buildJsonArray {
                    session.sessionManager.getTree().forEach { add(normalizeTreeNode(it)) }
                },
            )
            put("leafId", entryRef(session.sessionManager.getLeafId()))
            put("stats", normalizeSessionStats(session))
        }

    private fun normalizeSessionStats(session: AgentSession): JsonObject {
        val stats = session.getSessionStats()
        return buildJsonObject {
            if (stats.sessionFile != null) {
                put("sessionFile", pathRef(stats.sessionFile))
            }
            put("sessionId", sessionRef(stats.sessionId))
            put("userMessages", stats.userMessages)
            put("assistantMessages", stats.assistantMessages)
            put("toolResults", stats.toolResults)
            put("totalMessages", stats.totalMessages)
            put(
                "contextUsage",
                buildJsonObject {
                    put("tokens", JsonNull)
                    put("contextWindow", session.model.contextWindow)
                },
            )
        }
    }

    fun normalizeTreeNode(node: pi.coding.agent.core.SessionTreeNode): JsonObject =
        buildJsonObject {
            put("entry", normalizeSessionEntry(node.entry))
            put(
                "children",
                buildJsonArray {
                    node.children.forEach { add(normalizeTreeNode(it)) }
                },
            )
            node.label?.let { put("label", it) }
        }

    fun normalizeSessionEntry(entry: pi.coding.agent.core.SessionEntry): JsonObject =
        buildJsonObject {
            put("type", entry.type)
            put("id", entryRef(entry.id))
            put("parentId", entryRef(entry.parentId))
            when (entry) {
                is SessionMessageEntry -> put("message", normalizeMessage(entry.message))
                is ThinkingLevelChangeEntry -> put("thinkingLevel", entry.thinkingLevel)
                is ModelChangeEntry -> {
                    put("provider", entry.provider)
                    put("modelId", entry.modelId)
                }
                is CompactionEntry -> {
                    put("summary", entry.summary)
                    put("firstKeptEntryId", entryRef(entry.firstKeptEntryId))
                    if (entry.details == null) {
                        put("tokensBefore", entry.tokensBefore)
                    }
                    entry.details?.let { put("details", it) }
                    put("fromHook", entry.fromHook ?: false)
                }
                is BranchSummaryEntry -> {
                    put("summary", entry.summary)
                    put("fromId", entryRef(entry.fromId))
                    entry.details?.let { put("details", it) }
                    entry.fromHook?.let { put("fromHook", it) }
                }
                is CustomEntry -> {
                    put("customType", entry.customType)
                    entry.data?.let { put("data", it) }
                }
                is CustomMessageEntry -> {
                    put("customType", entry.customType)
                    put("content", normalizeUserContent(entry.content))
                    put("display", entry.display)
                    entry.details?.let { put("details", it) }
                }
                is LabelEntry -> {
                    put("targetId", entryRef(entry.targetId))
                    entry.label?.let { put("label", it) }
                }
                is SessionInfoEntry -> entry.name?.let { put("name", it) }
            }
        }

    private fun normalizeMessage(message: AgentMessage): JsonObject =
        when (message) {
            is UserMessage ->
                buildJsonObject {
                    put("role", "user")
                    put("content", normalizeUserContent(message.content))
                }
            is AssistantMessage ->
                buildJsonObject {
                    put("role", "assistant")
                    put("stopReason", message.stopReason.name.lowercase())
                    put(
                        "content",
                        buildJsonArray {
                            message.content.forEach { add(normalizeContentBlock(it)) }
                        },
                    )
                    message.errorMessage?.let { put("errorMessage", it) }
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
                            message.content.forEach { add(normalizeContentBlock(it)) }
                        },
                    )
                    message.details?.let { put("details", it) }
                }
            is CustomMessage ->
                buildJsonObject {
                    put("role", "custom")
                    put("customType", message.customType)
                    put("content", normalizeUserContent(message.content))
                    put("display", message.display)
                    message.details?.let { put("details", it) }
                }
            is BranchSummaryMessage ->
                buildJsonObject {
                    put("role", "branchSummary")
                    put("summary", message.summary)
                    put("fromId", entryRef(message.fromId))
                }
            is CompactionSummaryMessage ->
                buildJsonObject {
                    put("role", "compactionSummary")
                    put("summary", message.summary)
                }
            is BashExecutionMessage ->
                buildJsonObject {
                    put("role", "bashExecution")
                    put("command", message.command)
                    put("output", message.output)
                    message.exitCode?.let { put("exitCode", it) }
                    put("cancelled", message.cancelled)
                    put("truncated", message.truncated)
                    message.fullOutputPath?.let { put("fullOutputPath", pathRef(it)) }
                    put("excludeFromContext", message.excludeFromContext)
                }
            else -> error("Unsupported message type: ${message::class.simpleName}")
        }

    private fun normalizeUserContent(content: UserMessageContent): JsonElement =
        when (content) {
            is UserMessageContent.Text -> JsonPrimitive(content.value)
            is UserMessageContent.Structured ->
                buildJsonArray {
                    content.parts.forEach { part ->
                        add(normalizeUserPart(part))
                    }
                }
        }

    private fun normalizeUserPart(part: UserContentPart): JsonObject =
        when (part) {
            is TextContent ->
                buildJsonObject {
                    put("type", "text")
                    put("text", part.text)
                }
            is ImageContent ->
                buildJsonObject {
                    put("type", "image")
                    put("data", part.data)
                    put("mimeType", part.mimeType)
                }
            else -> error("Unsupported user content part: ${part::class.simpleName}")
        }

    private fun normalizeContentBlock(block: Any): JsonObject =
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
            else -> error("Unsupported assistant content block: ${block::class.simpleName}")
        }
}

private class FauxCodingProvider(
    private val models: List<Model<String>>,
    responses: JsonArray,
    private val tokenSize: Int,
) : ApiProvider {
    override val api: String = models.first().api
    private val pendingResponses: MutableList<JsonObject> = responses.map { it.jsonObject }.toMutableList()

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
        val response = pendingResponses.removeFirstOrNull()
        val resolvedModel = models.firstOrNull { it.id == model.id } ?: models.first()
        val message =
            if (response == null) {
                createErrorMessage(resolvedModel)
            } else {
                createAssistantMessage(resolvedModel, response, System.currentTimeMillis()).withUsageEstimate(context)
            }
        emitAssistantStream(stream, message, tokenSize)
        return stream
    }

    private fun createErrorMessage(model: Model<String>): AssistantMessage =
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

    private fun createAssistantMessage(
        model: Model<String>,
        response: JsonObject,
        timestamp: Long,
    ): AssistantMessage =
        AssistantMessage(
            content =
                response["content"]!!
                    .jsonArray
                    .map { block ->
                        val value = block.jsonObject
                        when (value.string("type")) {
                            "text" -> TextContent(value.string("text"))
                            "thinking" -> ThinkingContent(value.string("thinking"))
                            "toolCall" -> ToolCall(value.string("id"), value.string("name"), value["arguments"]!!.jsonObject)
                            else -> error("Unsupported faux block type: ${value.string("type")}")
                        }
                    }.toMutableList(),
            api = model.api,
            provider = model.provider,
            model = model.id,
            usage = Usage(),
            stopReason = response["stopReason"]!!.jsonPrimitive.content.toStopReason(),
            errorMessage = response.optionalString("errorMessage"),
            timestamp = timestamp,
            responseId = response.optionalString("responseId"),
        )

    private fun AssistantMessage.withUsageEstimate(context: Context): AssistantMessage {
        val promptText = serializeContext(context)
        val input = estimateTokens(promptText)
        val output = estimateTokens(assistantContentText(content))
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
        context.systemPrompt?.let { parts += "system:$it" }
        context.messages.forEach { message ->
            parts +=
                when (message) {
                    is UserMessage -> "user:${when (val content = message.content) {
                        is UserMessageContent.Text -> content.value
                        is UserMessageContent.Structured ->
                            content.parts.filterIsInstance<TextContent>().joinToString(
                                separator = "\n",
                            ) { it.text }
                    }}"
                    is AssistantMessage ->
                        "assistant:${assistantContentText(message.content)}"
                    is ToolResultMessage ->
                        "toolResult:${message.toolName}:${message.content.filterIsInstance<TextContent>().joinToString(
                            separator = "\n",
                        ) { it.text }}"
                    else -> message.role
                }
        }
        return parts.joinToString(separator = "\n\n")
    }

    private fun assistantContentText(content: List<AssistantContentBlock>): String =
        content.joinToString(separator = "\n") { block ->
            when (block) {
                is TextContent -> block.text
                is ThinkingContent -> block.thinking
                is ToolCall -> "${block.name}:${block.arguments}"
                else -> ""
            }
        }

    private fun estimateTokens(text: String): Int = kotlin.math.ceil(text.length / 4.0).toInt()

    private fun emitAssistantStream(
        stream: AssistantMessageEventStream,
        message: AssistantMessage,
        tokenSize: Int,
    ) {
        var partial = message.copy(content = mutableListOf())
        stream.push(AssistantMessageEvent.Start(partial))

        message.content.forEachIndexed { index, block ->
            when (block) {
                is TextContent -> {
                    partial = partial.copy(content = (partial.content + TextContent("")).toMutableList())
                    stream.push(AssistantMessageEvent.TextStart(index, partial))
                    splitStringByTokenSize(block.text, tokenSize).forEach { chunk ->
                        val current = partial.content[index] as TextContent
                        val updated = current.copy(text = current.text + chunk)
                        partial = partial.copy(content = partial.content.toMutableList().also { it[index] = updated })
                        stream.push(AssistantMessageEvent.TextDelta(index, chunk, partial))
                    }
                    stream.push(AssistantMessageEvent.TextEnd(index, block.text, partial))
                }
                is ThinkingContent -> {
                    partial = partial.copy(content = (partial.content + ThinkingContent("")).toMutableList())
                    stream.push(AssistantMessageEvent.ThinkingStart(index, partial))
                    splitStringByTokenSize(block.thinking, tokenSize).forEach { chunk ->
                        val current = partial.content[index] as ThinkingContent
                        val updated = current.copy(thinking = current.thinking + chunk)
                        partial = partial.copy(content = partial.content.toMutableList().also { it[index] = updated })
                        stream.push(AssistantMessageEvent.ThinkingDelta(index, chunk, partial))
                    }
                    stream.push(AssistantMessageEvent.ThinkingEnd(index, block.thinking, partial))
                }
                is ToolCall -> {
                    partial = partial.copy(content = (partial.content + ToolCall(block.id, block.name, buildJsonObject {})).toMutableList())
                    stream.push(AssistantMessageEvent.ToolCallStart(index, partial))
                    splitStringByTokenSize(block.arguments.toString(), tokenSize).forEach { chunk ->
                        stream.push(AssistantMessageEvent.ToolCallDelta(index, chunk, partial))
                    }
                    partial = partial.copy(content = partial.content.toMutableList().also { it[index] = block })
                    stream.push(AssistantMessageEvent.ToolCallEnd(index, block, partial))
                }
                else -> Unit
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

    private fun splitStringByTokenSize(
        text: String,
        tokenSize: Int,
    ): List<String> {
        val size = maxOf(1, tokenSize) * 4
        if (text.isEmpty()) return listOf("")
        return text.chunked(size)
    }
}

private fun String.toThinkingLevel(): AgentThinkingLevel =
    when (lowercase()) {
        "off" -> AgentThinkingLevel.OFF
        "minimal" -> AgentThinkingLevel.MINIMAL
        "low" -> AgentThinkingLevel.LOW
        "medium" -> AgentThinkingLevel.MEDIUM
        "high" -> AgentThinkingLevel.HIGH
        "xhigh" -> AgentThinkingLevel.XHIGH
        else -> error("Unsupported thinking level: $this")
    }

private fun String.toStopReason(): StopReason =
    when (lowercase()) {
        "stop" -> StopReason.STOP
        "tooluse", "tool_use" -> StopReason.TOOL_USE
        "aborted" -> StopReason.ABORTED
        "error" -> StopReason.ERROR
        "maxlength", "max_length", "length" -> StopReason.LENGTH
        else -> error("Unsupported stopReason: $this")
    }

private fun JsonObject.string(name: String): String = this[name]!!.jsonPrimitive.content

private fun JsonObject.optionalString(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.tokenSize(): Int =
    this["provider"]!!
        .jsonObject["tokenSize"]!!
        .jsonObject["min"]!!
        .jsonPrimitive.int

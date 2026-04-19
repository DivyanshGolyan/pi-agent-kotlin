package pi.coding.agent.core

import pi.agent.core.Agent
import pi.agent.core.AgentOptions
import pi.agent.core.AgentThinkingLevel
import pi.agent.core.AgentTool
import pi.agent.core.InitialAgentState
import pi.ai.core.Model
import pi.ai.core.getModel

public data class CreateAgentSessionOptions(
    val cwd: String =
        java.nio.file.Paths
            .get("")
            .toAbsolutePath()
            .toString(),
    val agentDir: String = getAgentDir(),
    val authStorage: AuthStorage? = null,
    val modelRegistry: ModelRegistry? = null,
    val model: Model<*>? = null,
    val thinkingLevel: AgentThinkingLevel? = null,
    val scopedModels: List<ModelScope> = emptyList(),
    val tools: List<AgentTool<*>> = emptyList(),
    val resourceLoader: ResourceLoader? = null,
    val sessionManager: SessionManager? = null,
    val settingsManager: SettingsManager? = null,
    val streamFn: pi.agent.core.StreamFn? = null,
)

public data class CreateAgentSessionResult(
    val session: AgentSession,
    val modelFallbackMessage: String? = null,
)

public suspend fun createAgentSession(options: CreateAgentSessionOptions = CreateAgentSessionOptions()): CreateAgentSessionResult {
    val authStorage = options.authStorage ?: AuthStorage.create()
    val modelRegistry = options.modelRegistry ?: ModelRegistry.create(authStorage)
    val settingsManager = options.settingsManager ?: SettingsManager.create(options.cwd, options.agentDir)
    val sessionManager = options.sessionManager ?: SessionManager.create(options.cwd, getDefaultSessionDir(options.cwd, options.agentDir))
    val resourceLoader =
        options.resourceLoader
            ?: DefaultResourceLoader(
                DefaultResourceLoaderOptions(
                    cwd = options.cwd,
                    agentDir = options.agentDir,
                ),
            )
    resourceLoader.reload()

    val existingSession = sessionManager.buildSessionContext()
    val restoredModel =
        existingSession.model?.let { restored ->
            modelRegistry.find(restored.provider, restored.modelId)
        }

    val fallbackModel = getModel("anthropic", "claude-sonnet-4-5") ?: modelRegistry.getAvailable().firstOrNull()
    val resolvedModel = options.model ?: restoredModel ?: modelRegistry.getAvailable().firstOrNull() ?: fallbackModel
    requireNotNull(resolvedModel) { "No model available to create AgentSession" }

    val resolvedThinkingLevel =
        options.thinkingLevel
            ?: parseThinkingLevel(existingSession.thinkingLevel)
            ?: settingsManager.getDefaultThinkingLevel()
            ?: DEFAULT_THINKING_LEVEL

    val agent =
        Agent(
            AgentOptions(
                initialState =
                    InitialAgentState(
                        systemPrompt = resourceLoader.getSystemPrompt(),
                        model = resolvedModel,
                        thinkingLevel = if (resolvedModel.reasoning) resolvedThinkingLevel else AgentThinkingLevel.OFF,
                        tools = options.tools,
                        messages = existingSession.messages,
                    ),
                convertToLlm = ::convertToLlm,
                getApiKey = modelRegistry::getApiKey,
                streamFn = options.streamFn,
                steeringMode = settingsManager.getSteeringMode(),
                followUpMode = settingsManager.getFollowUpMode(),
                transport = settingsManager.getTransport(),
                thinkingBudgets = settingsManager.getThinkingBudgets(),
                maxRetryDelayMs = settingsManager.getRetrySettings().maxDelayMs,
            ),
        )

    if (existingSession.messages.isEmpty()) {
        sessionManager.appendModelChange(resolvedModel.provider, resolvedModel.id)
        sessionManager.appendThinkingLevelChange(
            agent.state.thinkingLevel.name
                .lowercase(),
        )
    }

    val session =
        AgentSession(
            AgentSessionConfig(
                agent = agent,
                sessionManager = sessionManager,
                settingsManager = settingsManager,
                cwd = options.cwd,
                resourceLoader = resourceLoader,
                modelRegistry = modelRegistry,
                scopedModels = options.scopedModels,
            ),
        )

    val modelFallbackMessage =
        if (options.model == null && existingSession.model != null && restoredModel == null) {
            "Could not restore model ${existingSession.model.provider}/${existingSession.model.modelId}; using ${resolvedModel.provider}/${resolvedModel.id}"
        } else {
            null
        }

    return CreateAgentSessionResult(
        session = session,
        modelFallbackMessage = modelFallbackMessage,
    )
}

private fun parseThinkingLevel(value: String?): AgentThinkingLevel? =
    when (value?.lowercase()) {
        "off" -> AgentThinkingLevel.OFF
        "minimal" -> AgentThinkingLevel.MINIMAL
        "low" -> AgentThinkingLevel.LOW
        "medium" -> AgentThinkingLevel.MEDIUM
        "high" -> AgentThinkingLevel.HIGH
        "xhigh" -> AgentThinkingLevel.XHIGH
        else -> null
    }

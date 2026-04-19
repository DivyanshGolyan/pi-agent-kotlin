package pi.coding.agent.core

import pi.agent.core.AgentThinkingLevel
import pi.agent.core.AgentTool
import pi.ai.core.Model

public data class AgentSessionRuntimeDiagnostic(
    val type: DiagnosticType,
    val message: String,
)

public enum class DiagnosticType {
    INFO,
    WARNING,
    ERROR,
}

public data class CreateAgentSessionServicesOptions(
    val cwd: String,
    val agentDir: String = getAgentDir(),
    val authStorage: AuthStorage? = null,
    val settingsManager: SettingsManager? = null,
    val modelRegistry: ModelRegistry? = null,
    val resourceLoader: ResourceLoader? = null,
)

public data class AgentSessionServices(
    val cwd: String,
    val agentDir: String,
    val authStorage: AuthStorage,
    val settingsManager: SettingsManager,
    val modelRegistry: ModelRegistry,
    val resourceLoader: ResourceLoader,
    val diagnostics: List<AgentSessionRuntimeDiagnostic> = emptyList(),
)

public data class CreateAgentSessionFromServicesOptions(
    val services: AgentSessionServices,
    val sessionManager: SessionManager,
    val model: Model<*>? = null,
    val thinkingLevel: AgentThinkingLevel? = null,
    val scopedModels: List<ModelScope> = emptyList(),
    val tools: List<AgentTool<*>> = emptyList(),
    val streamFn: pi.agent.core.StreamFn? = null,
)

public suspend fun createAgentSessionServices(options: CreateAgentSessionServicesOptions): AgentSessionServices {
    val authStorage = options.authStorage ?: AuthStorage.create()
    val settingsManager = options.settingsManager ?: SettingsManager.create(options.cwd, options.agentDir)
    val modelRegistry = options.modelRegistry ?: ModelRegistry.create(authStorage)
    val resourceLoader =
        options.resourceLoader
            ?: DefaultResourceLoader(
                DefaultResourceLoaderOptions(
                    cwd = options.cwd,
                    agentDir = options.agentDir,
                ),
            )
    resourceLoader.reload()
    return AgentSessionServices(
        cwd = options.cwd,
        agentDir = options.agentDir,
        authStorage = authStorage,
        settingsManager = settingsManager,
        modelRegistry = modelRegistry,
        resourceLoader = resourceLoader,
    )
}

public suspend fun createAgentSessionFromServices(options: CreateAgentSessionFromServicesOptions): CreateAgentSessionResult =
    createAgentSession(
        CreateAgentSessionOptions(
            cwd = options.services.cwd,
            agentDir = options.services.agentDir,
            authStorage = options.services.authStorage,
            settingsManager = options.services.settingsManager,
            modelRegistry = options.services.modelRegistry,
            resourceLoader = options.services.resourceLoader,
            sessionManager = options.sessionManager,
            model = options.model,
            thinkingLevel = options.thinkingLevel,
            scopedModels = options.scopedModels,
            tools = options.tools,
            streamFn = options.streamFn,
        ),
    )

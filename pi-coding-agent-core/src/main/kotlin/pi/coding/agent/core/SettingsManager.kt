package pi.coding.agent.core

import pi.agent.core.AgentThinkingLevel
import pi.agent.core.QueueMode
import pi.ai.core.ThinkingBudgets
import pi.ai.core.Transport
import pi.coding.agent.core.compaction.BranchSummarySettings
import pi.coding.agent.core.compaction.CompactionSettings
import pi.coding.agent.core.compaction.DEFAULT_BRANCH_SUMMARY_SETTINGS
import pi.coding.agent.core.compaction.DEFAULT_COMPACTION_SETTINGS

public data class RetrySettings(
    val enabled: Boolean = false,
    val maxDelayMs: Long = 30_000L,
)

public class SettingsManager private constructor(
    public val cwd: String,
    public val agentDir: String,
) {
    private var defaultProvider: String? = null
    private var defaultModel: String? = null
    private var defaultThinkingLevel: AgentThinkingLevel? = null
    private var steeringMode: QueueMode = QueueMode.ONE_AT_A_TIME
    private var followUpMode: QueueMode = QueueMode.ONE_AT_A_TIME
    private var transport: Transport = Transport.SSE
    private var thinkingBudgets: ThinkingBudgets? = null
    private var retrySettings: RetrySettings = RetrySettings()
    private var compactionSettings: CompactionSettings = DEFAULT_COMPACTION_SETTINGS
    private var branchSummarySettings: BranchSummarySettings = DEFAULT_BRANCH_SUMMARY_SETTINGS
    private var blockImages: Boolean = false

    public fun getDefaultProvider(): String? = defaultProvider

    public fun getDefaultModel(): String? = defaultModel

    public fun getDefaultThinkingLevel(): AgentThinkingLevel? = defaultThinkingLevel

    public fun setDefaultThinkingLevel(level: AgentThinkingLevel) {
        defaultThinkingLevel = level
    }

    public fun setDefaultModelAndProvider(
        provider: String,
        modelId: String,
    ) {
        defaultProvider = provider
        defaultModel = modelId
    }

    public fun getSteeringMode(): QueueMode = steeringMode

    public fun setSteeringMode(mode: QueueMode) {
        steeringMode = mode
    }

    public fun getFollowUpMode(): QueueMode = followUpMode

    public fun setFollowUpMode(mode: QueueMode) {
        followUpMode = mode
    }

    public fun getTransport(): Transport = transport

    public fun setTransport(transport: Transport) {
        this.transport = transport
    }

    public fun getThinkingBudgets(): ThinkingBudgets? = thinkingBudgets

    public fun setThinkingBudgets(thinkingBudgets: ThinkingBudgets?) {
        this.thinkingBudgets = thinkingBudgets
    }

    public fun getRetrySettings(): RetrySettings = retrySettings

    public fun setRetrySettings(settings: RetrySettings) {
        retrySettings = settings
    }

    public fun getCompactionSettings(): CompactionSettings = compactionSettings

    public fun setCompactionSettings(settings: CompactionSettings) {
        compactionSettings = settings
    }

    public fun getBranchSummarySettings(): BranchSummarySettings = branchSummarySettings

    public fun setBranchSummarySettings(settings: BranchSummarySettings) {
        branchSummarySettings = settings
    }

    public fun getBlockImages(): Boolean = blockImages

    public fun setBlockImages(blockImages: Boolean) {
        this.blockImages = blockImages
    }

    public companion object {
        public fun create(
            cwd: String =
                java.nio.file.Paths
                    .get("")
                    .toAbsolutePath()
                    .toString(),
            agentDir: String = getAgentDir(),
        ): SettingsManager = SettingsManager(cwd = cwd, agentDir = agentDir)
    }
}

package pi.coding.agent.core

public data class PromptTemplate(
    val name: String,
    val content: String,
)

public data class Skill(
    val name: String,
    val filePath: String,
    val baseDir: String,
)

public data class AgentsFile(
    val path: String,
    val content: String,
)

public data class PromptResources(
    val prompts: List<PromptTemplate> = emptyList(),
)

public data class SkillResources(
    val skills: List<Skill> = emptyList(),
)

public data class AgentsFileResources(
    val agentsFiles: List<AgentsFile> = emptyList(),
)

public interface ResourceLoader {
    public suspend fun reload()

    public fun getSystemPrompt(): String

    public fun getAppendSystemPrompt(): List<String>

    public fun getPrompts(): PromptResources

    public fun getSkills(): SkillResources

    public fun getAgentsFiles(): AgentsFileResources
}

public data class DefaultResourceLoaderOptions(
    val cwd: String,
    val agentDir: String,
    val systemPrompt: String = "",
    val appendSystemPrompt: List<String> = emptyList(),
)

public class DefaultResourceLoader(
    private val options: DefaultResourceLoaderOptions,
) : ResourceLoader {
    @Suppress("EmptyFunctionBlock")
    override suspend fun reload() {
    }

    override fun getSystemPrompt(): String = options.systemPrompt

    override fun getAppendSystemPrompt(): List<String> = options.appendSystemPrompt

    override fun getPrompts(): PromptResources = PromptResources()

    override fun getSkills(): SkillResources = SkillResources()

    override fun getAgentsFiles(): AgentsFileResources = AgentsFileResources()
}

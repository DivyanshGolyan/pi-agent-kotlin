package pi.coding.agent.core

import java.nio.file.Files
import java.nio.file.Paths

public const val PI_CODING_AGENT_DIR_ENV: String = "PI_CODING_AGENT_DIR"

public fun getAgentDir(): String {
    val envDir = System.getenv(PI_CODING_AGENT_DIR_ENV)
    if (!envDir.isNullOrBlank()) {
        return expandHome(envDir)
    }

    val userHome = System.getProperty("user.home")
    return Paths.get(userHome, ".pi", "agent").toString()
}

public fun getSessionsDir(): String = Paths.get(getAgentDir(), "sessions").toString()

internal fun ensureDirectory(path: String): String {
    Files.createDirectories(Paths.get(path))
    return path
}

private fun expandHome(path: String): String {
    val userHome = System.getProperty("user.home")
    return when {
        path == "~" -> userHome
        path.startsWith("~/") -> userHome + path.removePrefix("~")
        else -> path
    }
}

package pi.coding.agent.core

import java.nio.file.Files
import java.nio.file.Paths

public data class SessionCwdIssue(
    val sessionFile: String? = null,
    val sessionCwd: String,
    val fallbackCwd: String,
)

public fun getMissingSessionCwdIssue(
    sessionManager: ReadonlySessionManager,
    fallbackCwd: String,
): SessionCwdIssue? {
    val sessionFile = sessionManager.getSessionFile()
    if (sessionFile == null) {
        return null
    }

    val sessionCwd = sessionManager.getCwd()
    if (sessionCwd.isBlank() || Files.exists(Paths.get(sessionCwd))) {
        return null
    }

    return SessionCwdIssue(
        sessionFile = sessionFile,
        sessionCwd = sessionCwd,
        fallbackCwd = fallbackCwd,
    )
}

public fun formatMissingSessionCwdError(issue: SessionCwdIssue): String {
    val sessionFile = issue.sessionFile?.let { "\nSession file: $it" }.orEmpty()
    return buildString {
        append("Stored session working directory does not exist: ")
        append(issue.sessionCwd)
        append(sessionFile)
        append("\nCurrent working directory: ")
        append(issue.fallbackCwd)
    }
}

public class MissingSessionCwdError(
    public val issue: SessionCwdIssue,
) : IllegalStateException(formatMissingSessionCwdError(issue))

public fun assertSessionCwdExists(
    sessionManager: ReadonlySessionManager,
    fallbackCwd: String,
) {
    val issue = getMissingSessionCwdIssue(sessionManager, fallbackCwd) ?: return
    throw MissingSessionCwdError(issue)
}

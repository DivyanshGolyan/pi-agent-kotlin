package pi.coding.agent.core

import pi.ai.core.TextContent
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import java.nio.file.Paths
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.io.path.exists

public data class SessionStartEvent(
    val reason: SessionStartReason,
    val previousSessionFile: String? = null,
)

public enum class SessionStartReason {
    STARTUP,
    NEW,
    RESUME,
    FORK,
}

public enum class ForkPosition {
    BEFORE,
    AT,
}

public data class ForkSessionOptions(
    val position: ForkPosition = ForkPosition.BEFORE,
)

public data class CreateAgentSessionRuntimeResult(
    val session: AgentSession,
    val services: AgentSessionServices,
    val diagnostics: List<AgentSessionRuntimeDiagnostic> = emptyList(),
    val modelFallbackMessage: String? = null,
)

public typealias CreateAgentSessionRuntimeFactory =
    suspend (
        cwd: String,
        agentDir: String,
        sessionManager: SessionManager,
        sessionStartEvent: SessionStartEvent?,
    ) -> CreateAgentSessionRuntimeResult

public class SessionImportFileNotFoundError(
    public val filePath: String,
) : IllegalArgumentException("File not found: $filePath")

public class AgentSessionRuntime internal constructor(
    private var sessionBacking: AgentSession,
    private var servicesBacking: AgentSessionServices,
    private val createRuntime: CreateAgentSessionRuntimeFactory,
    private var diagnosticsBacking: List<AgentSessionRuntimeDiagnostic>,
    private var fallbackMessage: String? = null,
) {
    public val session: AgentSession
        get() = sessionBacking

    public val services: AgentSessionServices
        get() = servicesBacking

    public val cwd: String
        get() = servicesBacking.cwd

    public val diagnostics: List<AgentSessionRuntimeDiagnostic>
        get() = diagnosticsBacking

    public val modelFallbackMessage: String?
        get() = fallbackMessage

    private suspend fun replace(
        cwd: String,
        agentDir: String,
        sessionManager: SessionManager,
        reason: SessionStartReason,
    ) {
        val previousSessionFile = sessionBacking.sessionFile
        sessionBacking.dispose()
        val result =
            createRuntime(
                cwd,
                agentDir,
                sessionManager,
                SessionStartEvent(
                    reason = reason,
                    previousSessionFile = previousSessionFile,
                ),
            )
        sessionBacking = result.session
        servicesBacking = result.services
        diagnosticsBacking = result.diagnostics
        fallbackMessage = result.modelFallbackMessage
    }

    public suspend fun switchSession(
        sessionPath: String,
        cwdOverride: String? = null,
    ): Pair<Boolean, Unit?> {
        val sessionManager = SessionManager.open(sessionPath, cwdOverride = cwdOverride)
        assertSessionCwdExists(sessionManager, cwd)
        replace(
            cwd = sessionManager.getCwd(),
            agentDir = services.agentDir,
            sessionManager = sessionManager,
            reason = SessionStartReason.RESUME,
        )
        return false to null
    }

    public suspend fun newSession(
        parentSession: String? = null,
        setup: (suspend (SessionManager) -> Unit)? = null,
    ): Pair<Boolean, Unit?> {
        val sessionManager = SessionManager.create(cwd, session.sessionManager.getSessionDir())
        if (parentSession != null) {
            sessionManager.newSession(NewSessionOptions(parentSession = parentSession))
        }
        replace(
            cwd = cwd,
            agentDir = services.agentDir,
            sessionManager = sessionManager,
            reason = SessionStartReason.NEW,
        )
        if (setup != null) {
            setup(session.sessionManager)
            session.agent.state.messages = session.sessionManager.buildSessionContext().messages
        }
        return false to null
    }

    public suspend fun fork(entryId: String): Pair<Boolean, String?> = fork(entryId, ForkSessionOptions())

    public suspend fun fork(
        entryId: String,
        options: ForkSessionOptions,
    ): Pair<Boolean, String?> {
        val selectedEntry = session.sessionManager.getEntry(entryId) ?: error("Invalid entry ID for forking")
        val targetLeafId: String?
        val selectedText: String?
        if (options.position == ForkPosition.AT) {
            targetLeafId = selectedEntry.id
            selectedText = null
        } else {
            val messageEntry = selectedEntry as? SessionMessageEntry
            require(messageEntry?.message is UserMessage) { "Invalid entry ID for forking" }
            targetLeafId = messageEntry.parentId
            selectedText = extractUserMessageText(messageEntry.message.content)
        }
        val currentFile = session.sessionFile
        val sessionManager =
            if (session.sessionManager.isPersisted() && currentFile != null) {
                val sourceManager = SessionManager.open(currentFile, session.sessionManager.getSessionDir())
                if (targetLeafId == null) {
                    SessionManager.create(cwd, session.sessionManager.getSessionDir()).also {
                        it.newSession(NewSessionOptions(parentSession = currentFile))
                    }
                } else {
                    val forked = sourceManager.createBranchedSession(targetLeafId)
                    requireNotNull(forked) { "Failed to create forked session" }
                    SessionManager.open(forked, session.sessionManager.getSessionDir())
                }
            } else {
                session.sessionManager.apply {
                    if (targetLeafId == null) {
                        newSession(NewSessionOptions(parentSession = currentFile))
                    } else {
                        createBranchedSession(targetLeafId)
                    }
                }
            }

        replace(
            cwd = sessionManager.getCwd(),
            agentDir = services.agentDir,
            sessionManager = sessionManager,
            reason = SessionStartReason.FORK,
        )
        return false to selectedText
    }

    public suspend fun importFromJsonl(
        inputPath: String,
        cwdOverride: String? = null,
    ): Pair<Boolean, Unit?> {
        val resolved = Paths.get(inputPath).toAbsolutePath()
        if (!resolved.exists()) {
            throw SessionImportFileNotFoundError(resolved.toString())
        }
        val sessionDir = Paths.get(session.sessionManager.getSessionDir())
        sessionDir.createDirectories()
        val destination = sessionDir.resolve(resolved.fileName)
        if (destination != resolved) {
            resolved.copyTo(destination, overwrite = true)
        }
        val sessionManager = SessionManager.open(destination.toString(), sessionDir.toString(), cwdOverride)
        assertSessionCwdExists(sessionManager, cwd)
        replace(
            cwd = sessionManager.getCwd(),
            agentDir = services.agentDir,
            sessionManager = sessionManager,
            reason = SessionStartReason.RESUME,
        )
        return false to null
    }

    public suspend fun dispose() {
        sessionBacking.dispose()
    }

    private fun extractUserMessageText(content: UserMessageContent): String =
        when (content) {
            is UserMessageContent.Text -> content.value
            is UserMessageContent.Structured -> content.parts.filterIsInstance<TextContent>().joinToString(separator = "") { it.text }
        }
}

public suspend fun createAgentSessionRuntime(
    createRuntime: CreateAgentSessionRuntimeFactory,
    cwd: String,
    agentDir: String,
    sessionManager: SessionManager,
    sessionStartEvent: SessionStartEvent? = null,
): AgentSessionRuntime {
    assertSessionCwdExists(sessionManager, cwd)
    val result = createRuntime(cwd, agentDir, sessionManager, sessionStartEvent)
    return AgentSessionRuntime(
        sessionBacking = result.session,
        servicesBacking = result.services,
        createRuntime = createRuntime,
        diagnosticsBacking = result.diagnostics,
        fallbackMessage = result.modelFallbackMessage,
    )
}

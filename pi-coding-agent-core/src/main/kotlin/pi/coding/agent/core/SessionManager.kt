package pi.coding.agent.core

import kotlinx.serialization.json.JsonElement
import pi.agent.core.AgentMessage
import pi.ai.core.AssistantMessage
import pi.ai.core.Message
import pi.ai.core.TextContent
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.LinkedHashMap
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

public const val CURRENT_SESSION_VERSION: Int = 3

public data class SessionHeader(
    val version: Int? = null,
    val id: String,
    val timestamp: String,
    val cwd: String,
    val parentSession: String? = null,
) : FileEntry {
    override val type: String = "session"
}

public data class NewSessionOptions(
    val id: String? = null,
    val parentSession: String? = null,
)

public sealed interface FileEntry {
    public val type: String
}

public sealed interface SessionEntry : FileEntry {
    public val id: String
    public val parentId: String?
    public val timestamp: String
}

public data class SessionMessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val message: Message,
) : SessionEntry {
    override val type: String = "message"
}

public data class ThinkingLevelChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val thinkingLevel: String,
) : SessionEntry {
    override val type: String = "thinking_level_change"
}

public data class ModelChangeEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val provider: String,
    val modelId: String,
) : SessionEntry {
    override val type: String = "model_change"
}

public data class CompactionEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val summary: String,
    val firstKeptEntryId: String,
    val tokensBefore: Int,
    val details: JsonElement? = null,
    val fromHook: Boolean? = null,
) : SessionEntry {
    override val type: String = "compaction"
}

public data class BranchSummaryEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val fromId: String,
    val summary: String,
    val details: JsonElement? = null,
    val fromHook: Boolean? = null,
) : SessionEntry {
    override val type: String = "branch_summary"
}

public data class CustomEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val customType: String,
    val data: JsonElement? = null,
) : SessionEntry {
    override val type: String = "custom"
}

public data class LabelEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val targetId: String,
    val label: String? = null,
) : SessionEntry {
    override val type: String = "label"
}

public data class SessionInfoEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val name: String? = null,
) : SessionEntry {
    override val type: String = "session_info"
}

public data class CustomMessageEntry(
    override val id: String,
    override val parentId: String?,
    override val timestamp: String,
    val customType: String,
    val content: UserMessageContent,
    val details: JsonElement? = null,
    val display: Boolean,
) : SessionEntry {
    override val type: String = "custom_message"
}

public data class SessionTreeNode(
    val entry: SessionEntry,
    val children: MutableList<SessionTreeNode> = mutableListOf(),
    val label: String? = null,
    val labelTimestamp: String? = null,
)

public data class SessionModel(
    val provider: String,
    val modelId: String,
)

public data class SessionContext(
    val messages: List<AgentMessage>,
    val thinkingLevel: String,
    val model: SessionModel?,
)

public data class SessionInfo(
    val path: String,
    val id: String,
    val cwd: String,
    val name: String? = null,
    val parentSessionPath: String? = null,
    val created: Instant,
    val modified: Instant,
    val messageCount: Int,
    val firstMessage: String,
    val allMessagesText: String,
)

public typealias SessionListProgress = (loaded: Int, total: Int) -> Unit

public interface ReadonlySessionManager {
    public fun getCwd(): String

    public fun getSessionDir(): String

    public fun getSessionId(): String

    public fun getSessionFile(): String?

    public fun getLeafId(): String?

    public fun getLeafEntry(): SessionEntry?

    public fun getEntry(id: String): SessionEntry?

    public fun getLabel(id: String): String?

    public fun getBranch(fromId: String? = null): List<SessionEntry>

    public fun getHeader(): SessionHeader?

    public fun getEntries(): List<SessionEntry>

    public fun getTree(): List<SessionTreeNode>

    public fun getSessionName(): String?
}

public fun migrateSessionEntries(entries: MutableList<FileEntry>) {
    migrateToCurrentVersion(entries)
}

public fun parseSessionEntries(content: String): MutableList<FileEntry> =
    content
        .lineSequence()
        .filter { it.isNotBlank() }
        .mapNotNull(::parseFileEntry)
        .toMutableList()

public fun getLatestCompactionEntry(entries: List<SessionEntry>): CompactionEntry? =
    entries.asReversed().firstOrNull { it is CompactionEntry } as CompactionEntry?

public fun buildSessionContext(entries: List<SessionEntry>): SessionContext =
    buildSessionContextInternal(entries = entries, leafId = null, byId = null, fallbackToLast = true)

public fun buildSessionContext(
    entries: List<SessionEntry>,
    leafId: String?,
    byId: Map<String, SessionEntry>? = null,
): SessionContext = buildSessionContextInternal(entries = entries, leafId = leafId, byId = byId, fallbackToLast = false)

public fun getDefaultSessionDir(
    cwd: String,
    agentDir: String = getAgentDir(),
): String {
    val safePath = "--" + cwd.removePrefix("/").removePrefix("\\").replace(Regex("""[/\\:]"""), "-") + "--"
    return ensureDirectory(Paths.get(agentDir, "sessions", safePath).toString())
}

public fun loadEntriesFromFile(filePath: String): MutableList<FileEntry> {
    val path = Paths.get(filePath)
    if (!path.exists()) {
        return mutableListOf()
    }

    val entries = parseSessionEntries(Files.readString(path))
    if (entries.isEmpty()) {
        return entries
    }

    val header = entries.first()
    return if (header is SessionHeader && header.id.isNotBlank()) entries else mutableListOf()
}

public fun findMostRecentSession(sessionDir: String): String? {
    val dir = Paths.get(sessionDir)
    if (!dir.exists()) {
        return null
    }

    return runCatching {
        dir
            .listDirectoryEntries("*.jsonl")
            .filter(::isValidSessionFile)
            .maxByOrNull { Files.getLastModifiedTime(it).toMillis() }
            ?.toString()
    }.getOrNull()
}

public class SessionManager private constructor(
    private var cwd: String,
    private var sessionDir: String,
    private var sessionFile: String?,
    private val persist: Boolean,
) : ReadonlySessionManager {
    private var sessionId: String = ""
    private var flushed: Boolean = false
    private var fileEntries: MutableList<FileEntry> = mutableListOf()
    private val byId: MutableMap<String, SessionEntry> = LinkedHashMap()
    private val labelsById: MutableMap<String, String> = LinkedHashMap()
    private val labelTimestampsById: MutableMap<String, String> = LinkedHashMap()
    private var leafId: String? = null

    init {
        if (persist && sessionDir.isNotBlank()) {
            ensureDirectory(sessionDir)
        }

        if (sessionFile != null) {
            setSessionFile(sessionFile!!)
        } else {
            newSession()
        }
    }

    public fun setSessionFile(sessionFile: String) {
        this.sessionFile = Paths.get(sessionFile).toAbsolutePath().toString()
        val path = Paths.get(this.sessionFile!!)
        if (!path.exists()) {
            val explicitPath = this.sessionFile
            newSession()
            this.sessionFile = explicitPath
            return
        }

        fileEntries = loadEntriesFromFile(this.sessionFile!!)
        if (fileEntries.isEmpty()) {
            val explicitPath = this.sessionFile
            newSession()
            this.sessionFile = explicitPath
            rewriteFile()
            flushed = true
            return
        }

        val header = fileEntries.firstOrNull() as? SessionHeader
        sessionId = header?.id ?: createSessionId()

        if (migrateToCurrentVersion(fileEntries)) {
            rewriteFile()
        }

        buildIndex()
        flushed = true
    }

    public fun newSession(options: NewSessionOptions? = null): String? {
        sessionId = options?.id ?: createSessionId()
        val timestamp = Instant.now().toString()
        val header =
            SessionHeader(
                version = CURRENT_SESSION_VERSION,
                id = sessionId,
                timestamp = timestamp,
                cwd = cwd,
                parentSession = options?.parentSession,
            )
        fileEntries = mutableListOf(header)
        byId.clear()
        labelsById.clear()
        labelTimestampsById.clear()
        leafId = null
        flushed = false

        if (persist) {
            val fileTimestamp = timestamp.replace(":", "-").replace(".", "-")
            sessionFile = Paths.get(getSessionDir(), "${fileTimestamp}_$sessionId.jsonl").toString()
        }

        return sessionFile
    }

    public fun isPersisted(): Boolean = persist

    override fun getCwd(): String = cwd

    override fun getSessionDir(): String = sessionDir

    override fun getSessionId(): String = sessionId

    override fun getSessionFile(): String? = sessionFile

    public fun appendMessage(message: Message): String {
        require(message !is BranchSummaryMessage && message !is CompactionSummaryMessage) {
            "Use branch/session entry APIs for summary messages."
        }

        val entry =
            SessionMessageEntry(
                id = generateId(byId.keys),
                parentId = leafId,
                timestamp = Instant.now().toString(),
                message = message,
            )
        appendEntry(entry)
        return entry.id
    }

    public fun appendThinkingLevelChange(thinkingLevel: String): String {
        val entry =
            ThinkingLevelChangeEntry(
                id = generateId(byId.keys),
                parentId = leafId,
                timestamp = Instant.now().toString(),
                thinkingLevel = thinkingLevel,
            )
        appendEntry(entry)
        return entry.id
    }

    public fun appendModelChange(
        provider: String,
        modelId: String,
    ): String {
        val entry =
            ModelChangeEntry(
                id = generateId(byId.keys),
                parentId = leafId,
                timestamp = Instant.now().toString(),
                provider = provider,
                modelId = modelId,
            )
        appendEntry(entry)
        return entry.id
    }

    public fun appendCompaction(
        summary: String,
        firstKeptEntryId: String,
        tokensBefore: Int,
        details: JsonElement? = null,
        fromHook: Boolean? = null,
    ): String {
        val entry =
            CompactionEntry(
                id = generateId(byId.keys),
                parentId = leafId,
                timestamp = Instant.now().toString(),
                summary = summary,
                firstKeptEntryId = firstKeptEntryId,
                tokensBefore = tokensBefore,
                details = details,
                fromHook = fromHook,
            )
        appendEntry(entry)
        return entry.id
    }

    public fun appendCustomEntry(
        customType: String,
        data: JsonElement? = null,
    ): String {
        val entry =
            CustomEntry(
                id = generateId(byId.keys),
                parentId = leafId,
                timestamp = Instant.now().toString(),
                customType = customType,
                data = data,
            )
        appendEntry(entry)
        return entry.id
    }

    public fun appendSessionInfo(name: String): String {
        val entry =
            SessionInfoEntry(
                id = generateId(byId.keys),
                parentId = leafId,
                timestamp = Instant.now().toString(),
                name = name.trim(),
            )
        appendEntry(entry)
        return entry.id
    }

    override fun getSessionName(): String? =
        getEntries()
            .asReversed()
            .filterIsInstance<SessionInfoEntry>()
            .firstOrNull()
            ?.name
            ?.trim()
            ?.ifBlank { null }

    public fun appendCustomMessageEntry(
        customType: String,
        content: UserMessageContent,
        display: Boolean,
        details: JsonElement? = null,
    ): String {
        val entry =
            CustomMessageEntry(
                id = generateId(byId.keys),
                parentId = leafId,
                timestamp = Instant.now().toString(),
                customType = customType,
                content = content,
                display = display,
                details = details,
            )
        appendEntry(entry)
        return entry.id
    }

    public fun appendCustomMessageEntry(
        customType: String,
        content: String,
        display: Boolean,
        details: JsonElement? = null,
    ): String = appendCustomMessageEntry(customType, UserMessageContent.Text(content), display, details)

    override fun getLeafId(): String? = leafId

    override fun getLeafEntry(): SessionEntry? = leafId?.let(byId::get)

    override fun getEntry(id: String): SessionEntry? = byId[id]

    public fun getChildren(parentId: String): List<SessionEntry> = byId.values.filter { it.parentId == parentId }

    override fun getLabel(id: String): String? = labelsById[id]

    public fun appendLabelChange(
        targetId: String,
        label: String?,
    ): String {
        require(byId.containsKey(targetId)) { "Entry $targetId not found" }

        val entry =
            LabelEntry(
                id = generateId(byId.keys),
                parentId = leafId,
                timestamp = Instant.now().toString(),
                targetId = targetId,
                label = label,
            )
        appendEntry(entry)
        if (label.isNullOrBlank()) {
            labelsById.remove(targetId)
            labelTimestampsById.remove(targetId)
        } else {
            labelsById[targetId] = label
            labelTimestampsById[targetId] = entry.timestamp
        }
        return entry.id
    }

    override fun getBranch(fromId: String?): List<SessionEntry> {
        val path = mutableListOf<SessionEntry>()
        var current = (fromId ?: leafId)?.let(byId::get)
        while (current != null) {
            path.add(0, current)
            current = current.parentId?.let(byId::get)
        }
        return path
    }

    public fun buildSessionContext(): SessionContext = buildSessionContext(getEntries(), leafId, byId)

    override fun getHeader(): SessionHeader? = fileEntries.firstOrNull() as? SessionHeader

    override fun getEntries(): List<SessionEntry> = fileEntries.filterIsInstance<SessionEntry>()

    override fun getTree(): List<SessionTreeNode> {
        val entries = getEntries()
        val nodeMap = LinkedHashMap<String, SessionTreeNode>()
        val roots = mutableListOf<SessionTreeNode>()

        entries.forEach { entry ->
            nodeMap[entry.id] =
                SessionTreeNode(
                    entry = entry,
                    label = labelsById[entry.id],
                    labelTimestamp = labelTimestampsById[entry.id],
                )
        }

        entries.forEach { entry ->
            val node = requireNotNull(nodeMap[entry.id])
            val parentId = entry.parentId
            if (parentId == null || parentId == entry.id) {
                roots += node
            } else {
                val parent = nodeMap[parentId]
                if (parent == null) {
                    roots += node
                } else {
                    parent.children += node
                }
            }
        }

        val stack = ArrayDeque<SessionTreeNode>()
        roots.forEach(stack::addLast)
        while (stack.isNotEmpty()) {
            val node = stack.removeLast()
            node.children.sortBy { parseInstantOrEpoch(it.entry.timestamp).toEpochMilli() }
            node.children.forEach(stack::addLast)
        }

        return roots
    }

    public fun branch(branchFromId: String) {
        require(byId.containsKey(branchFromId)) { "Entry $branchFromId not found" }
        leafId = branchFromId
    }

    public fun resetLeaf() {
        leafId = null
    }

    public fun branchWithSummary(
        branchFromId: String?,
        summary: String,
        details: JsonElement? = null,
        fromHook: Boolean? = null,
    ): String {
        require(branchFromId == null || byId.containsKey(branchFromId)) { "Entry $branchFromId not found" }
        leafId = branchFromId
        val entry =
            BranchSummaryEntry(
                id = generateId(byId.keys),
                parentId = branchFromId,
                timestamp = Instant.now().toString(),
                fromId = branchFromId ?: "root",
                summary = summary,
                details = details,
                fromHook = fromHook,
            )
        appendEntry(entry)
        return entry.id
    }

    public fun createBranchedSession(leafId: String): String? {
        val previousSessionFile = sessionFile
        val path = getBranch(leafId)
        require(path.isNotEmpty()) { "Entry $leafId not found" }

        val pathWithoutLabels = path.filterNot { it is LabelEntry }
        val newSessionId = createSessionId()
        val timestamp = Instant.now().toString()
        val fileTimestamp = timestamp.replace(":", "-").replace(".", "-")
        val newSessionFile = Paths.get(getSessionDir(), "${fileTimestamp}_$newSessionId.jsonl").toString()

        val header =
            SessionHeader(
                version = CURRENT_SESSION_VERSION,
                id = newSessionId,
                timestamp = timestamp,
                cwd = cwd,
                parentSession = if (persist) previousSessionFile else null,
            )

        val pathEntryIds = pathWithoutLabels.mapTo(linkedSetOf()) { it.id }
        val labelsToWrite =
            labelsById.entries.mapNotNull { (targetId, label) ->
                if (pathEntryIds.contains(targetId)) {
                    Triple(targetId, label, requireNotNull(labelTimestampsById[targetId]))
                } else {
                    null
                }
            }

        val labelEntries = mutableListOf<LabelEntry>()
        var labelParentId = pathWithoutLabels.lastOrNull()?.id
        val usedIds = pathEntryIds.toMutableSet()
        labelsToWrite.forEach { (targetId, label, labelTimestamp) ->
            val labelId = generateId(usedIds)
            usedIds += labelId
            labelEntries +=
                LabelEntry(
                    id = labelId,
                    parentId = labelParentId,
                    timestamp = labelTimestamp,
                    targetId = targetId,
                    label = label,
                )
            labelParentId = labelId
        }

        fileEntries =
            mutableListOf<FileEntry>(header).apply {
                addAll(pathWithoutLabels)
                addAll(labelEntries)
            }
        sessionId = newSessionId
        sessionFile = if (persist) newSessionFile else null
        buildIndex()

        if (persist) {
            val hasAssistant = fileEntries.any { it is SessionMessageEntry && it.message is AssistantMessage }
            if (hasAssistant) {
                rewriteFile()
                flushed = true
            } else {
                flushed = false
            }
            return newSessionFile
        }

        return null
    }

    private fun appendEntry(entry: SessionEntry) {
        fileEntries += entry
        byId[entry.id] = entry
        leafId = entry.id
        persistEntry(entry)
    }

    private fun buildIndex() {
        byId.clear()
        labelsById.clear()
        labelTimestampsById.clear()
        leafId = null

        fileEntries.filterIsInstance<SessionEntry>().forEach { entry ->
            byId[entry.id] = entry
            leafId = entry.id
            if (entry is LabelEntry) {
                if (entry.label.isNullOrBlank()) {
                    labelsById.remove(entry.targetId)
                    labelTimestampsById.remove(entry.targetId)
                } else {
                    labelsById[entry.targetId] = entry.label
                    labelTimestampsById[entry.targetId] = entry.timestamp
                }
            }
        }
    }

    private fun rewriteFile() {
        if (!persist || sessionFile == null) {
            return
        }

        val path = Paths.get(sessionFile!!)
        path.parent?.let(Files::createDirectories)
        val content = fileEntries.joinToString(separator = "\n", postfix = "\n", transform = ::fileEntryToLine)
        Files.writeString(
            path,
            content,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
    }

    private fun persistEntry(entry: SessionEntry) {
        if (!persist || sessionFile == null) {
            return
        }

        val hasAssistant = fileEntries.any { it is SessionMessageEntry && it.message is AssistantMessage }
        if (!hasAssistant) {
            flushed = false
            return
        }

        val path = Paths.get(sessionFile!!)
        path.parent?.let(Files::createDirectories)
        if (!flushed) {
            rewriteFile()
            flushed = true
            return
        }

        Files.writeString(
            path,
            fileEntryToLine(entry) + "\n",
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
            StandardOpenOption.WRITE,
        )
    }

    public companion object {
        public fun create(
            cwd: String,
            sessionDir: String? = null,
        ): SessionManager {
            val dir = sessionDir ?: getDefaultSessionDir(cwd)
            return SessionManager(cwd = cwd, sessionDir = dir, sessionFile = null, persist = true)
        }

        public fun open(
            path: String,
            sessionDir: String? = null,
            cwdOverride: String? = null,
        ): SessionManager {
            val entries = loadEntriesFromFile(path)
            val header = entries.firstOrNull() as? SessionHeader
            val cwd = cwdOverride ?: header?.cwd ?: Paths.get("").toAbsolutePath().toString()
            val dir =
                sessionDir ?: Paths
                    .get(path)
                    .toAbsolutePath()
                    .parent
                    .toString()
            return SessionManager(cwd = cwd, sessionDir = dir, sessionFile = path, persist = true)
        }

        public fun continueRecent(
            cwd: String,
            sessionDir: String? = null,
        ): SessionManager {
            val dir = sessionDir ?: getDefaultSessionDir(cwd)
            val mostRecent = findMostRecentSession(dir)
            return SessionManager(cwd = cwd, sessionDir = dir, sessionFile = mostRecent, persist = true)
        }

        public fun inMemory(cwd: String = Paths.get("").toAbsolutePath().toString()): SessionManager =
            SessionManager(cwd = cwd, sessionDir = "", sessionFile = null, persist = false)

        public fun forkFrom(
            sourcePath: String,
            targetCwd: String,
            sessionDir: String? = null,
        ): SessionManager {
            val sourceEntries = loadEntriesFromFile(sourcePath)
            require(sourceEntries.isNotEmpty()) { "Cannot fork: source session file is empty or invalid: $sourcePath" }
            require(sourceEntries.firstOrNull() is SessionHeader) { "Cannot fork: source session has no header: $sourcePath" }

            val dir = ensureDirectory(sessionDir ?: getDefaultSessionDir(targetCwd))
            val newSessionId = createSessionId()
            val timestamp = Instant.now().toString()
            val fileTimestamp = timestamp.replace(":", "-").replace(".", "-")
            val newSessionFile = Paths.get(dir, "${fileTimestamp}_$newSessionId.jsonl")

            val header =
                SessionHeader(
                    version = CURRENT_SESSION_VERSION,
                    id = newSessionId,
                    timestamp = timestamp,
                    cwd = targetCwd,
                    parentSession = sourcePath,
                )

            val lines =
                buildList {
                    add(fileEntryToLine(header))
                    sourceEntries.filterIsInstance<SessionEntry>().forEach { add(fileEntryToLine(it)) }
                }.joinToString(separator = "\n", postfix = "\n")

            Files.writeString(
                newSessionFile,
                lines,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )

            return SessionManager(targetCwd, dir, newSessionFile.toString(), true)
        }

        public fun list(
            cwd: String,
            sessionDir: String? = null,
            onProgress: SessionListProgress? = null,
        ): List<SessionInfo> {
            val dir = sessionDir ?: getDefaultSessionDir(cwd)
            return listSessionsFromDir(dir, onProgress).sortedByDescending { it.modified }
        }

        public fun listAll(onProgress: SessionListProgress? = null): List<SessionInfo> {
            val sessionsDir = Paths.get(getSessionsDir())
            if (!sessionsDir.exists() || !sessionsDir.isDirectory()) {
                return emptyList()
            }

            return runCatching {
                val dirs = sessionsDir.listDirectoryEntries().filter(Path::isDirectory)
                val allFiles = dirs.flatMap { dir -> runCatching { dir.listDirectoryEntries("*.jsonl") }.getOrDefault(emptyList()) }
                val total = allFiles.size
                var loaded = 0
                val sessions =
                    allFiles.mapNotNull { file ->
                        val info = buildSessionInfo(file)
                        loaded += 1
                        onProgress?.invoke(loaded, total)
                        info
                    }
                sessions.sortedByDescending { it.modified }
            }.getOrDefault(emptyList())
        }
    }
}

private fun migrateToCurrentVersion(entries: MutableList<FileEntry>): Boolean {
    val headerIndex = entries.indexOfFirst { it is SessionHeader }
    val header = entries.getOrNull(headerIndex) as? SessionHeader ?: return false
    val version = header.version ?: 1
    if (version >= CURRENT_SESSION_VERSION) {
        return false
    }

    if (version < 2) {
        migrateV1ToV2(entries)
    }
    if (version < 3) {
        migrateV2ToV3(entries)
    }

    return true
}

private fun migrateV1ToV2(entries: MutableList<FileEntry>) {
    val ids = linkedSetOf<String>()
    var previousId: String? = null

    entries.indices.forEach { index ->
        val entry = entries[index]
        when (entry) {
            is SessionHeader -> entries[index] = entry.copy(version = 2)
            is SessionEntry -> {
                val id = entry.id.ifBlank { generateId(ids) }
                ids += id
                val migrated = migrateSessionEntryIdentity(entry, id, previousId)
                entries[index] = migrated
                previousId = id
            }
        }
    }
}

private fun migrateV2ToV3(entries: MutableList<FileEntry>) {
    entries.indices.forEach { index ->
        when (val entry = entries[index]) {
            is SessionHeader -> entries[index] = entry.copy(version = 3)
            is SessionMessageEntry -> {
                val message = entry.message
                if (message is CustomMessage) {
                    entries[index] = entry.copy(message = message.copy())
                }
            }
            else -> Unit
        }
    }
}

private fun migrateSessionEntryIdentity(
    entry: SessionEntry,
    id: String,
    parentId: String?,
): SessionEntry =
    when (entry) {
        is SessionMessageEntry -> entry.copy(id = id, parentId = parentId)
        is ThinkingLevelChangeEntry -> entry.copy(id = id, parentId = parentId)
        is ModelChangeEntry -> entry.copy(id = id, parentId = parentId)
        is CompactionEntry -> entry.copy(id = id, parentId = parentId)
        is BranchSummaryEntry -> entry.copy(id = id, parentId = parentId)
        is CustomEntry -> entry.copy(id = id, parentId = parentId)
        is LabelEntry -> entry.copy(id = id, parentId = parentId)
        is SessionInfoEntry -> entry.copy(id = id, parentId = parentId)
        is CustomMessageEntry -> entry.copy(id = id, parentId = parentId)
    }

private fun buildSessionContextInternal(
    entries: List<SessionEntry>,
    leafId: String?,
    byId: Map<String, SessionEntry>?,
    fallbackToLast: Boolean,
): SessionContext {
    val index =
        byId ?: entries.associateByTo(LinkedHashMap()) { it.id }

    val leaf =
        when {
            entries.isEmpty() -> null
            !fallbackToLast && leafId == null -> null
            leafId != null -> index[leafId]
            else -> entries.lastOrNull()
        } ?: return SessionContext(messages = emptyList(), thinkingLevel = "off", model = null)

    val path = mutableListOf<SessionEntry>()
    var current: SessionEntry? = leaf
    while (current != null) {
        path.add(0, current)
        current = current.parentId?.let(index::get)
    }

    var thinkingLevel = "off"
    var model: SessionModel? = null
    var compaction: CompactionEntry? = null

    path.forEach { entry ->
        when (entry) {
            is ThinkingLevelChangeEntry -> thinkingLevel = entry.thinkingLevel
            is ModelChangeEntry -> model = SessionModel(entry.provider, entry.modelId)
            is SessionMessageEntry -> {
                val message = entry.message
                if (message is AssistantMessage) {
                    model = SessionModel(message.provider, message.model)
                }
            }
            is CompactionEntry -> compaction = entry
            else -> Unit
        }
    }

    val messages = mutableListOf<AgentMessage>()
    val appendContextMessage: (SessionEntry) -> Unit = { entry ->
        when (entry) {
            is SessionMessageEntry -> messages += entry.message
            is CustomMessageEntry ->
                messages +=
                    createCustomMessage(
                        customType = entry.customType,
                        content = entry.content,
                        display = entry.display,
                        details = entry.details,
                        timestamp = entry.timestamp,
                    )
            is BranchSummaryEntry -> {
                if (entry.summary.isNotBlank()) {
                    messages += createBranchSummaryMessage(entry.summary, entry.fromId, entry.timestamp)
                }
            }
            else -> Unit
        }
    }

    if (compaction != null) {
        val activeCompaction = compaction
        messages += createCompactionSummaryMessage(activeCompaction.summary, activeCompaction.tokensBefore, activeCompaction.timestamp)
        val compactionIndex = path.indexOfFirst { it is CompactionEntry && it.id == activeCompaction.id }
        var foundFirstKept = false
        for (i in 0 until compactionIndex) {
            val entry = path[i]
            if (entry.id == activeCompaction.firstKeptEntryId) {
                foundFirstKept = true
            }
            if (foundFirstKept) {
                appendContextMessage(entry)
            }
        }
        for (i in (compactionIndex + 1) until path.size) {
            appendContextMessage(path[i])
        }
    } else {
        path.forEach(appendContextMessage)
    }

    return SessionContext(messages = messages, thinkingLevel = thinkingLevel, model = model)
}

private fun isValidSessionFile(path: Path): Boolean =
    runCatching {
        Files.newBufferedReader(path).use { reader ->
            val firstLine = reader.readLine() ?: return false
            val header = parseFileEntry(firstLine)
            header is SessionHeader && header.id.isNotBlank()
        }
    }.getOrDefault(false)

private fun listSessionsFromDir(
    dir: String,
    onProgress: SessionListProgress?,
): List<SessionInfo> {
    val path = Paths.get(dir)
    if (!path.exists()) {
        return emptyList()
    }

    return runCatching {
        val files = path.listDirectoryEntries("*.jsonl")
        val total = files.size
        var loaded = 0
        files.mapNotNull { file ->
            val info = buildSessionInfo(file)
            loaded += 1
            onProgress?.invoke(loaded, total)
            info
        }
    }.getOrDefault(emptyList())
}

private fun buildSessionInfo(filePath: Path): SessionInfo? =
    runCatching {
        val entries = parseSessionEntries(Files.readString(filePath))
        if (entries.isEmpty()) {
            return null
        }

        val header = entries.firstOrNull() as? SessionHeader ?: return null
        val entryList = entries.filterIsInstance<SessionEntry>()
        var messageCount = 0
        var firstMessage = ""
        val allMessages = mutableListOf<String>()
        var name: String? = null

        entryList.forEach { entry ->
            if (entry is SessionInfoEntry) {
                name = entry.name?.trim()?.ifBlank { null }
            }

            if (entry !is SessionMessageEntry) {
                return@forEach
            }

            messageCount += 1
            val text = extractMessageText(entry.message)
            if (text.isNotBlank()) {
                allMessages += text
                if (firstMessage.isBlank() && entry.message is UserMessage) {
                    firstMessage = text
                }
            }
        }

        SessionInfo(
            path = filePath.toString(),
            id = header.id,
            cwd = header.cwd,
            name = name,
            parentSessionPath = header.parentSession,
            created = parseInstantOrEpoch(header.timestamp),
            modified = resolveModifiedInstant(entryList, header, filePath),
            messageCount = messageCount,
            firstMessage = firstMessage.ifBlank { "(no messages)" },
            allMessagesText = allMessages.joinToString(" "),
        )
    }.getOrNull()

private fun resolveModifiedInstant(
    entries: List<SessionEntry>,
    header: SessionHeader,
    filePath: Path,
): Instant {
    val lastActivity =
        entries
            .filterIsInstance<SessionMessageEntry>()
            .mapNotNull { entry ->
                when (val message = entry.message) {
                    is UserMessage -> Instant.ofEpochMilli(message.timestamp)
                    is AssistantMessage -> Instant.ofEpochMilli(message.timestamp)
                    else -> null
                }
            }.maxOrNull()
    if (lastActivity != null) {
        return lastActivity
    }

    return runCatching { parseInstantOrEpoch(header.timestamp) }
        .getOrElse { Files.getLastModifiedTime(filePath).toInstant() }
}

private fun extractMessageText(message: Message): String =
    when (message) {
        is UserMessage ->
            when (val content = message.content) {
                is UserMessageContent.Text -> content.value
                is UserMessageContent.Structured ->
                    content.parts
                        .filterIsInstance<TextContent>()
                        .joinToString(" ") { it.text }
            }
        is AssistantMessage -> message.content.filterIsInstance<TextContent>().joinToString(" ") { it.text }
        else -> ""
    }

private fun parseInstantOrEpoch(value: String): Instant =
    runCatching { Instant.parse(value) }
        .getOrElse { Instant.EPOCH }

private fun createSessionId(): String = UUID.randomUUID().toString()

private fun generateId(existingIds: Collection<String>): String {
    repeat(100) {
        val candidate = UUID.randomUUID().toString().take(8)
        if (!existingIds.contains(candidate)) {
            return candidate
        }
    }
    return UUID.randomUUID().toString()
}

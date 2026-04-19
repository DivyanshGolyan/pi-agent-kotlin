package pi.coding.agent.core.compaction

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pi.ai.core.AssistantMessage
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.ToolResultMessage
import pi.ai.core.Usage
import pi.ai.core.UsageCost
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import pi.coding.agent.core.BranchSummaryEntry
import pi.coding.agent.core.CompactionEntry
import pi.coding.agent.core.SessionEntry
import pi.coding.agent.core.SessionManager
import pi.coding.agent.core.SessionMessageEntry

class BranchSummarizationTest {
    @BeforeEach
    fun resetEntries() {
        entryCounter = 0
        lastId = null
        entries.clear()
    }

    @Test
    fun `collect entries for branch summary returns path from old leaf to common ancestor`() {
        val session = SessionManager.inMemory()
        session.appendMessage(userMessage("1"))
        val id2 = session.appendMessage(assistantMessage("2"))
        val id3 = session.appendMessage(userMessage("3"))

        session.branch(id2)
        val id4 = session.appendMessage(userMessage("4-branch"))

        val result = collectEntriesForBranchSummary(session, oldLeafId = id3, targetId = id4)

        assertEquals(id2, result.commonAncestorId)
        assertEquals(listOf(id3), result.entries.map { it.id })
    }

    @Test
    fun `collect entries returns empty when there is no old position`() {
        val session = SessionManager.inMemory()
        val root = session.appendMessage(userMessage("root"))

        val result = collectEntriesForBranchSummary(session, oldLeafId = null, targetId = root)

        assertTrue(result.entries.isEmpty())
        assertNull(result.commonAncestorId)
    }

    @Test
    fun `prepare branch entries collects nested file tracking from branch summaries`() {
        val details =
            buildJsonObject {
                put(
                    "readFiles",
                    buildJsonArray {
                        add(JsonPrimitive("a.kt"))
                        add(JsonPrimitive("b.kt"))
                    },
                )
                put("modifiedFiles", buildJsonArray { add(JsonPrimitive("c.kt")) })
            }

        val branchSummary =
            BranchSummaryEntry(
                id = "bs-1",
                parentId = null,
                timestamp =
                    java.time.Instant
                        .now()
                        .toString(),
                fromId = "old-leaf",
                summary = "Previous branch",
                details = details,
            )

        val preparation = prepareBranchEntries(listOf(branchSummary), tokenBudget = 0)

        assertEquals(setOf("a.kt", "b.kt"), preparation.fileOps.read)
        assertEquals(setOf("c.kt"), preparation.fileOps.edited)
        assertEquals(1, preparation.messages.size)
    }

    @Test
    fun `prepare branch entries excludes tool results and keeps newest messages within budget`() {
        val oldUser = createMessageEntry(userMessage("old message that should be dropped ".repeat(20)))
        val toolResult =
            createMessageEntry(
                ToolResultMessage(
                    toolCallId = "tc1",
                    toolName = "read",
                    content = listOf(TextContent("tool output")),
                    isError = false,
                    timestamp = System.currentTimeMillis(),
                ),
            )
        val recentAssistant = createMessageEntry(assistantMessage("recent answer"))
        val compaction =
            CompactionEntry(
                id = "compaction-1",
                parentId = recentAssistant.id,
                timestamp =
                    java.time.Instant
                        .now()
                        .toString(),
                summary = "Compacted work",
                firstKeptEntryId = oldUser.id,
                tokensBefore = 1000,
            )

        val preparation = prepareBranchEntries(listOf(oldUser, toolResult, recentAssistant, compaction), tokenBudget = 10)

        assertTrue(preparation.messages.none { it is ToolResultMessage })
        assertTrue(preparation.messages.last() is pi.coding.agent.core.CompactionSummaryMessage)
        assertTrue(preparation.totalTokens > 0)
    }

    private fun userMessage(text: String): UserMessage =
        UserMessage(content = UserMessageContent.Text(text), timestamp = System.currentTimeMillis())

    private fun assistantMessage(
        text: String,
        usage: Usage = createMockUsage(100, 50),
    ): AssistantMessage =
        AssistantMessage(
            content = mutableListOf(TextContent(text)),
            api = "anthropic-messages",
            provider = "anthropic",
            model = "claude-sonnet-4-5",
            usage = usage,
            stopReason = StopReason.STOP,
            timestamp = System.currentTimeMillis(),
        )

    private fun createMockUsage(
        input: Int,
        output: Int,
        cacheRead: Int = 0,
        cacheWrite: Int = 0,
    ): Usage =
        Usage(
            input = input,
            output = output,
            cacheRead = cacheRead,
            cacheWrite = cacheWrite,
            totalTokens = input + output + cacheRead + cacheWrite,
            cost = UsageCost(),
        )

    private var entryCounter: Int = 0
    private var lastId: String? = null
    private val entries = mutableListOf<SessionEntry>()

    private fun createMessageEntry(message: pi.ai.core.Message): SessionMessageEntry {
        val id = "branch-test-id-${entryCounter++}"
        return SessionMessageEntry(
            id = id,
            parentId = lastId,
            timestamp =
                java.time.Instant
                    .now()
                    .toString(),
            message = message,
        ).also {
            lastId = id
            entries += it
        }
    }
}

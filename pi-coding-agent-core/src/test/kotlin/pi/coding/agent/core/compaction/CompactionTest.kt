package pi.coding.agent.core.compaction

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import pi.ai.core.AssistantMessage
import pi.ai.core.StopReason
import pi.ai.core.TextContent
import pi.ai.core.Usage
import pi.ai.core.UsageCost
import pi.ai.core.UserMessage
import pi.ai.core.UserMessageContent
import pi.coding.agent.core.CompactionEntry
import pi.coding.agent.core.SessionEntry
import pi.coding.agent.core.SessionMessageEntry
import pi.coding.agent.core.buildSessionContext

class CompactionTest {
    @BeforeEach
    fun resetEntries() {
        entryCounter = 0
        lastId = null
        entries.clear()
    }

    @Test
    fun `calculate context tokens prefers totalTokens`() {
        val usage = createMockUsage(input = 1000, output = 500, cacheRead = 200, cacheWrite = 100)
        assertEquals(1800, calculateContextTokens(usage))
    }

    @Test
    fun `get last assistant usage skips aborted and error messages`() {
        val aborted =
            assistantMessage(
                "Aborted",
                usage = createMockUsage(300, 150),
                stopReason = StopReason.ABORTED,
            )
        val entries =
            listOf<SessionEntry>(
                createMessageEntry(userMessage("Hello")),
                createMessageEntry(assistantMessage("Hi", createMockUsage(100, 50))),
                createMessageEntry(userMessage("How are you?")),
                createMessageEntry(aborted),
            )

        val usage = getLastAssistantUsage(entries)
        assertNotNull(usage)
        assertEquals(100, usage!!.input)
    }

    @Test
    fun `should compact only when threshold exceeded and enabled`() {
        val settings = CompactionSettings(enabled = true, reserveTokens = 10000, keepRecentTokens = 20000)
        assertTrue(shouldCompact(95000, 100000, settings))
        assertEquals(false, shouldCompact(89000, 100000, settings))
        assertEquals(false, shouldCompact(95000, 100000, settings.copy(enabled = false)))
    }

    @Test
    fun `find cut point returns split turn metadata when cutting at assistant message`() {
        val entries =
            listOf<SessionEntry>(
                createMessageEntry(userMessage("Turn 1")),
                createMessageEntry(assistantMessage("A1", createMockUsage(0, 100, 1000, 0))),
                createMessageEntry(userMessage("Turn 2")),
                createMessageEntry(assistantMessage("A2-1", createMockUsage(0, 100, 5000, 0))),
                createMessageEntry(assistantMessage("A2-2", createMockUsage(0, 100, 8000, 0))),
                createMessageEntry(assistantMessage("A2-3", createMockUsage(0, 100, 10000, 0))),
            )

        val result = findCutPoint(entries, 0, entries.size, keepRecentTokens = 3000)
        val cutEntry = entries[result.firstKeptEntryIndex] as SessionMessageEntry

        if (cutEntry.message is AssistantMessage) {
            assertTrue(result.isSplitTurn)
            assertEquals(2, result.turnStartIndex)
        }
    }

    @Test
    fun `prepare compaction preserves previous kept history when it still fits`() {
        val u1 = createMessageEntry(userMessage("user msg 1"))
        createMessageEntry(assistantMessage("assistant msg 1"))
        val u2 = createMessageEntry(userMessage("user msg 2 - kept by compaction1"))
        createMessageEntry(assistantMessage("assistant msg 2"))
        createMessageEntry(userMessage("user msg 3 - kept by compaction1"))
        createMessageEntry(assistantMessage("assistant msg 3", createMockUsage(5000, 1000)))
        val compaction1 = createCompactionEntry("First summary", u2.id)
        createMessageEntry(userMessage("user msg 4 (new after compaction1)"))
        createMessageEntry(assistantMessage("assistant msg 4", createMockUsage(8000, 2000)))

        val entries = createdEntries()
        val contextBefore = buildSessionContext(entries)
        val preparation = prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS)

        assertNotNull(preparation)
        assertEquals(u2.id, preparation!!.firstKeptEntryId)
        assertEquals("First summary", preparation.previousSummary)
        assertEquals(estimateContextTokens(contextBefore.messages).tokens, preparation.tokensBefore)

        val compaction2 =
            CompactionEntry(
                id = "compaction2-id",
                parentId = entries.last().id,
                timestamp =
                    java.time.Instant
                        .now()
                        .toString(),
                summary = "Second summary",
                firstKeptEntryId = preparation.firstKeptEntryId,
                tokensBefore = preparation.tokensBefore,
            )
        val contextAfter = buildSessionContext(entries + compaction2)
        val text = extractText(contextAfter.messages)

        assertTrue(text.contains("user msg 2 - kept by compaction1"))
        assertTrue(text.contains("user msg 3 - kept by compaction1"))
        assertTrue(!extractText(preparation.messagesToSummarize).contains("First summary"))
        assertTrue((entries.contains(u1)))
        assertTrue((entries.contains(compaction1)))
    }

    @Test
    fun `prepare compaction re-summarizes previously kept messages when window moves`() {
        createMessageEntry(userMessage("user msg 1 ".repeat(4)))
        createMessageEntry(assistantMessage("assistant msg 1 ".repeat(4)))
        val u2 = createMessageEntry(userMessage("user msg 2 - kept by compaction1 ".repeat(12)))
        createMessageEntry(assistantMessage("assistant msg 2 ".repeat(12)))
        createMessageEntry(userMessage("user msg 3 - kept by compaction1 ".repeat(12)))
        createMessageEntry(assistantMessage("assistant msg 3 ".repeat(12), createMockUsage(5000, 1000)))
        createCompactionEntry("First summary", u2.id)
        createMessageEntry(userMessage("user msg 4 (new after compaction1) ".repeat(12)))
        createMessageEntry(assistantMessage("assistant msg 4 ".repeat(12), createMockUsage(8000, 2000)))

        val entries = createdEntries()
        val preparation =
            prepareCompaction(
                entries,
                DEFAULT_COMPACTION_SETTINGS.copy(keepRecentTokens = 100),
            )

        assertNotNull(preparation)
        val summarizedText = extractText(preparation!!.messagesToSummarize)
        assertTrue(summarizedText.contains("user msg 2 - kept by compaction1"))
        assertTrue(summarizedText.contains("user msg 3 - kept by compaction1"))
        assertTrue(!summarizedText.contains("First summary"))
        assertEquals("First summary", preparation.previousSummary)
    }

    @Test
    fun `prepare compaction returns null when last entry is already compaction`() {
        createMessageEntry(userMessage("1"))
        val first = createdEntries().first().id
        val compaction = createCompactionEntry("summary", first)
        val entries = createdEntries() + compaction

        assertNull(prepareCompaction(entries, DEFAULT_COMPACTION_SETTINGS))
    }

    @Test
    fun `estimate context tokens uses usage plus trailing estimate`() {
        val messages =
            listOf(
                userMessage("hello"),
                assistantMessage("response", createMockUsage(100, 50)),
                userMessage("follow up"),
            )

        val estimate = estimateContextTokens(messages)

        assertEquals(150, estimate.usageTokens)
        assertTrue(estimate.trailingTokens > 0)
        assertEquals(1, estimate.lastUsageIndex)
        assertEquals(estimate.usageTokens + estimate.trailingTokens, estimate.tokens)
    }

    private fun extractText(messages: List<pi.agent.core.AgentMessage>): String =
        messages.joinToString(separator = "\n") { message ->
            when (message) {
                is UserMessage ->
                    when (val content = message.content) {
                        is UserMessageContent.Text -> content.value
                        is UserMessageContent.Structured ->
                            content.parts.filterIsInstance<TextContent>().joinToString(separator = " ") { it.text }
                    }
                is AssistantMessage -> message.content.filterIsInstance<TextContent>().joinToString(separator = " ") { it.text }
                is pi.coding.agent.core.BranchSummaryMessage -> message.summary
                is pi.coding.agent.core.CompactionSummaryMessage -> message.summary
                is pi.coding.agent.core.CustomMessage ->
                    when (val content = message.content) {
                        is UserMessageContent.Text -> content.value
                        is UserMessageContent.Structured ->
                            content.parts.filterIsInstance<TextContent>().joinToString(separator = " ") { it.text }
                    }
                is pi.coding.agent.core.BashExecutionMessage -> "${message.command}\n${message.output}"
                is pi.ai.core.ToolResultMessage -> message.content.filterIsInstance<TextContent>().joinToString(separator = " ") { it.text }
                else -> ""
            }
        }

    private fun userMessage(text: String): UserMessage =
        UserMessage(content = UserMessageContent.Text(text), timestamp = System.currentTimeMillis())

    private fun assistantMessage(
        text: String,
        usage: Usage = createMockUsage(100, 50),
        stopReason: StopReason = StopReason.STOP,
    ): AssistantMessage =
        AssistantMessage(
            content = mutableListOf(TextContent(text)),
            api = "anthropic-messages",
            provider = "anthropic",
            model = "claude-sonnet-4-5",
            usage = usage,
            stopReason = stopReason,
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
        val id = "test-id-${entryCounter++}"
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

    private fun createCompactionEntry(
        summary: String,
        firstKeptEntryId: String,
    ): CompactionEntry =
        CompactionEntry(
            id = "test-id-${entryCounter++}",
            parentId = lastId,
            timestamp =
                java.time.Instant
                    .now()
                    .toString(),
            summary = summary,
            firstKeptEntryId = firstKeptEntryId,
            tokensBefore = 10000,
        ).also {
            lastId = it.id
            entries += it
        }

    private fun createdEntries(): List<SessionEntry> = entries.toList()
}
